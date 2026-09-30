# Development Standards

What every change to this codebase is expected to follow, and — for everything marked
**automated** — what actually enforces it in CI, not just this document. "Automated" means a
pull request goes red if the rule is broken; nothing here is aspirational-only unless labelled as
a convention.

Run `git diff` after any auto-fixer below to review what changed before committing, same as any
other tool-generated diff.

## Java / Spring Boot (`backend/`)

| Concern | Tool | Enforced |
|---|---|---|
| Formatting | Spotless + google-java-format | **Automated** (`mvn verify`) |
| Static analysis (bug patterns, best practices, security) | PMD | **Automated** (`mvn verify`) |
| Static analysis (bug detection) | SpotBugs (`spotbugs-exclude.xml` - each exemption named and justified, same standard as `SchemaConventionsTest`'s) | **Automated** (`mvn verify`) |
| Architecture/layering rules | ArchUnit | **Automated** (`mvn test`, `ArchitectureTest`) |
| Vulnerability scanning (Java + JS/TS) | Semgrep | **Automated** (`.github/workflows/semgrep.yml`) |
| Dependency updates | Dependabot | **Automated** (`.github/dependabot.yml`) |
| Test coverage | JaCoCo | Reported, not gated (see below) |
| Build reproducibility | Maven Wrapper (`./mvnw`) | Always use `./mvnw`, not a locally installed `mvn`, so CI and every contributor build with the same Maven version |

**Running locally:**

```bash
cd backend
./mvnw spotless:apply   # auto-fix formatting
./mvnw verify            # formatting check + PMD + SpotBugs + tests + package
```

**Conventions** (not all mechanically enforceable, but reviewed for):

- Package layout: `controller` (REST), `service` (business logic), `repository` (Spring Data),
  `entity` (JPA), `dto` (REST request/response shapes), `config` (what exists today). `ArchitectureTest`
  encodes the allowed dependency direction between these — Controller → Service → Repository →
  Entity, Repository never called directly from a Controller, Entities never returned from a
  Controller — and starts enforcing each rule automatically the moment the first class lands in
  the relevant package (see that class's Javadoc for why rules over an empty package don't
  silently no-op).
- **DTOs only across the REST boundary, never JPA entities** (`controllers_do_not_expose_entities`
  in `ArchitectureTest`) — this is explicitly called out in `docs/user-stories/BACKLOG-remaining-epics.md`
  (EPIC 29) as a rule that "erodes easily if not enforced."
  A DTO may not reference an entity either (`dtos_do_not_carry_entities`), and
  `ArchitectureRulesBiteTest` proves both rules fail on a deliberate violation.
- **API wire conventions (EPIC-29, #149)** — mandatory for every new endpoint:
  - **Decimals travel as strings.** Every `BigDecimal` (money, quantity, price, FX rate,
    percentage) is serialized as a plain decimal string with its scale kept (`"1005.0000"`) by
    one global rule (`JacksonConfig`); requests accept strings or numbers. Never use `double` or
    `float` in a DTO. `DecimalWireFormatTest` checks every DTO automatically.
  - **One error shape.** Every error is RFC 9457 `application/problem+json` with `detail`, a
    stable `code` (`ApiErrorCode`) and the request's `correlationId`. Throw a
    `ResponseStatusException` with a user-facing reason — its class-level code is added
    automatically. Throw an `ApiException` with a specific code only where a client must branch on
    it, and add that code to `ApiErrorCode`; codes are contract and are never renamed. A 404 for
    something another workspace owns says exactly what one for a missing id says (US-28-03). A
    500 never carries exception text.
  - **Correlation id.** `CorrelationIdFilter` puts one on every request (a well-formed
    `X-Correlation-Id` is honoured) and in the log MDC; quote it when reporting an error.
- SLF4J (`LoggerFactory.getLogger`) for logging, never `System.out`/`System.err` — enforced by both
  PMD (`SystemPrintln`) and an ArchUnit general coding rule; see `DatabaseBootstrapInitializer` for
  why this is safe even in code that runs before Spring's DI container exists.
- Every externally visible identifier is a `UUID` (`gen_random_uuid()`), never a sequential
  integer (FR-TEN-005) — see the SQL section below; `SchemaConventionsTest` checks this at the
  database level, not just by code review.
- New tests go in `src/test/java`, packaged to mirror `src/main/java`. Prefer a real PostgreSQL
  instance via Testcontainers over mocking the database for anything that touches a query,
  trigger, or constraint — this project's whole design (RLS, triggers, generated columns,
  partitioning) lives in the database, not the ORM, so a mocked repository proves nothing about
  correctness here.
- **Coverage is reported, not gated.** A hard percentage threshold would be meaningless noise
  at this stage of the project (barely any application code exists yet to cover). Revisit once
  EPIC-02's services/controllers land and there's a real baseline to hold steady.

## SQL / PostgreSQL migrations (`backend/src/main/resources/db/migration/`)

| Concern | Tool | Enforced |
|---|---|---|
| Style (keyword/function casing, line length) | sqlfluff (`.sqlfluff`) | **Automated**, new/changed migrations only (`.github/workflows/backend-ci.yml`, `sql-lint` job) |
| No SERIAL/IDENTITY sequence primary keys | `SchemaConventionsTest` | **Automated** (`mvn test`) |
| Every domain table's primary key includes a UUID column | `SchemaConventionsTest` | **Automated** (`mvn test`) |
| Migrations apply cleanly end-to-end | `TrackMyWealthApplicationStartupTest` | **Automated** (`mvn test`) |

**An already-applied migration is never edited, ever** — not for a bug, not for a style fix, not
even for a one-character typo. Flyway records a checksum of every migration's content
(`flyway.validate-on-migrate: true`); editing a migration that's already run anywhere breaks
startup on every environment that already applied it. Fix forward with a new
`V<n>__description.sql` file instead — see `V21__fix_transaction_append_only_trigger_gap.sql` for
a worked example (fixes a bug in `V10` without touching `V10`), and
`database-schema.md`'s "Migration numbering and out-of-order application" section for a real
mistake (and its fix) this exact rule caused mid-project.

**One deliberate, explicitly-authorized exception**: the household→workspace rename edited V1-V21
in place rather than adding a fix-forward migration, on the basis that the project was still in
its pre-release development phase with no deployed instance anywhere carrying a
`flyway_schema_history` row for the old content (confirmed: no CI/CD deploy workflow to a
persistent environment exists in this repo). This rule resumes applying without exception from
that point on - any migration that predates it must never be edited again, regardless of how
minor the change.

This is also *why* sqlfluff only lints new/changed files in a PR (via `git diff` against the PR
base), never the whole directory: several already-shipped migrations don't match the sqlfluff
config's layout rules, and that's fine — they're frozen, not something to "fix" retroactively.
`V90` (Quartz's schema) is permanently excluded via `.sqlfluffignore`, since it's vendored
verbatim from the `quartz-2.3.2.jar` and must track that source exactly, not this project's style.

**Conventions**, formalizing what `database-schema.md` section 1 already documents:

- Money: `NUMERIC(20,4)`. Quantities (shares, units): `NUMERIC(28,10)`. Never a float/real/double.
- Primary keys: `UUID PRIMARY KEY DEFAULT gen_random_uuid()`. Exceptions require the same
  reasoning `SchemaConventionsTest` documents for shared, non-tenant reference data
  (`trading_calendar`, `gics_structure_version`) — a natural key for genuinely global, non-tenant,
  non-enumerable-concern lookup data, never for anything workspace/user-owned.
- `workspace_id` on every workspace-scoped table, protected by row-level security (`V20`) —
  directly, or transitively via a join where the table doesn't carry the column itself (see
  `database-schema.md` section 4 for which tables are transitive and why they need explicit
  cross-tenant test coverage of their own).
- Forward-only, one change per migration file, numbered `V<n>__snake_case_description.sql`.

## React Native / TypeScript / Web (`mobile/`)

| Concern | Tool | Enforced |
|---|---|---|
| Linting (React, hooks, Expo conventions) | ESLint (`eslint-config-expo`) | **Automated** (`.github/workflows/mobile-web-ci.yml`) |
| Formatting | Prettier | **Automated** (`.github/workflows/mobile-web-ci.yml`) |
| Type checking | `tsc --noEmit` (strict mode) | **Automated** (`.github/workflows/mobile-web-ci.yml`) |
| Unit tests | Jest (`jest-expo` preset) + Testing Library | **Automated** (`.github/workflows/mobile-web-ci.yml`) |
| Vulnerability scanning | Semgrep | **Automated** (`.github/workflows/semgrep.yml`) |
| Dependency updates | Dependabot | **Automated** (`.github/dependabot.yml`) |
| Web build actually succeeds | `expo export -p web` | **Automated** (`.github/workflows/mobile-web-ci.yml`) |

**Running locally:**

```bash
cd mobile
npm run format        # auto-fix formatting (prettier --write)
npm run lint           # expo lint (auto-fixes some issues; review the rest)
npm run format:check   # what CI runs
npx tsc --noEmit        # what CI runs
npm test                 # what CI runs
```

**Conventions:**

- One codebase, three platforms (iOS/Android/web) — see the root README's "Mobile and web are the
  same app." Don't fork behavior per-platform unless the underlying capability genuinely differs
  (e.g. `use-color-scheme.web.ts`'s hydration guard); prefer `Platform.OS` checks over duplicate
  components where reasonable.
- Functional components and hooks only — no class components.
- `src/api/client.ts` is the only thing that talks to `fetch` directly; new API calls go through
  it (or its eventual generated-client successor once the backend publishes an OpenAPI spec — see
  EPIC-29) rather than each screen rolling its own `fetch`.
- A lint-rule suppression (`eslint-disable`) always carries a comment explaining *why* the rule
  doesn't apply here, not just that it's disabled — see `use-color-scheme.web.ts` for the standard
  it's held to.
- New non-trivial logic gets a test alongside it (`*.test.ts`/`*.test.tsx`, colocated with the
  file it tests, matching `client.test.ts`) — prefer testing behavior (what a function returns/
  calls) over implementation detail, and prefer a real assertion over a snapshot for anything with
  meaningful logic.

## Cross-cutting

- **Secret scanning**: gitleaks runs on every push/PR (`.github/workflows/gitleaks.yml`). A
  finding blocks the PR — never silence it by editing history or excluding the path; rotate the
  credential and remove it from the diff instead.
- **Dependency updates**: Dependabot opens a PR weekly per ecosystem (Maven, npm, Docker base
  images, GitHub Actions themselves). CI runs on those PRs like any other; a passing Dependabot PR
  is still worth a skim before merging, not an auto-merge.
- **Commit messages**: explain *why*, not just *what* — the diff already shows what changed. See
  this project's own commit history for the expected level of detail on anything non-trivial
  (a one-line typo fix doesn't need three paragraphs; a schema/behavior change usually does).

## Extending these standards

When a new tool/rule is added here, it should (in order):

1. Be justified against a real, current pain point (a bug this would have caught, a style
   disagreement that cost review time) — not "because it's popular."
2. Be verified against this codebase before being made blocking — run it, read the findings, fix
   or explicitly exempt each one with a stated reason (see `SchemaConventionsTest`'s exemption
   list for the standard this project holds itself to: every exemption is named and justified,
   never a blanket "ignore this directory").
3. Update this document in the same PR that adds the tool.
