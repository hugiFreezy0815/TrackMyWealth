# TrackMyWealth

Privacy-first personal finance and wealth-management platform for individuals and households in
Switzerland and Germany. This repository contains the database schema, the Spring Boot backend
(a REST API under `/api/v1`) and the Expo client, built from the project's requirements baseline
(`docs/requirements-personal-wealth-platform.md` and
`docs/TrackMyWealth_Consolidated_Requirements_Specification_v5.docx`). See **Status** below for what is
implemented today and what is still backlog.

## What's here

```
.github/workflows/backend-ci.yml    Backend tests, formatting/static analysis, SQL lint, Docker build
.github/workflows/mobile-web-ci.yml Type-check, lint, format-check, test, web export build
.github/workflows/semgrep.yml       SAST (Java + JS/TS), every push/PR
.github/workflows/gitleaks.yml      Secret scanning, every push/PR
.github/dependabot.yml              Weekly dependency-update PRs (Maven, npm, Docker, Actions)
.sqlfluff / .sqlfluffignore         SQL lint config - see docs/architecture/development-standards.md
backend/                         Spring Boot 4.1 backend, compiled for Java 17
  mvnw / mvnw.cmd / .mvn/          Maven Wrapper (Maven 3.9.16, download checksum-verified) - always
                                 build via ./mvnw, not a local mvn
  Dockerfile                     Multi-stage build -> runtime image (amd64/arm64, see "Running on a NAS")
  pom.xml                        Spotless/PMD/SpotBugs/JaCoCo coverage gate wired into the verify phase
  src/main/java/.../controller/  REST API under /api/v1 - 23 controllers; DTOs only at the boundary
  src/main/java/.../service/     Business logic, object-level authorization (AccessControlService),
                                 denial audit, categorization, FX, transaction lifecycle
  src/main/java/.../repository/, entity/, dto/        Spring Data JPA, JPA mappings, REST shapes
  src/main/java/.../security/    JWT authentication filter and token contract, rate limiting
  src/main/java/.../web/, error/ RFC 9457 errors, If-Match/ETag, correlation ids
  src/main/java/.../config/DatabaseBootstrapInitializer.java   <- see "Database" below
  src/main/resources/application.yml
  src/main/resources/messages*.properties                       <- EN/DE validation messages
  src/main/resources/db/migration/V1..V50__*.sql               <- the schema, evolved forward-only
  src/main/resources/db/migration/V90__quartz_schema.sql       <- background-job store (EPIC 30)
  src/test/java/...              ~70 test classes: integration tests against real PostgreSQL
                                 (Testcontainers), ArchUnit layering rules, unit tests
mobile/                          React Native + Expo (SDK 57) client - iOS, Android, AND web
  app.json
  eslint.config.js / .prettierrc.json  Lint + format config
  src/app/                       File-based routes, shared across all three platforms
  src/api/client.ts               Fetch wrapper around the backend (EXPO_PUBLIC_API_URL), tested
  README.md                      Mobile + web setup, run, and deploy instructions
docs/
  requirements-personal-wealth-platform.md, TrackMyWealth_Consolidated_Requirements_Specification_v5.docx
                                Requirements baseline (FR-/NFR-/RULE- ids cited across the code)
  backlog/SPRINT-3.md           Sprint 3 plan (later sprints: GitHub sprint labels and project board)
  architecture/
    database-schema.md          Design rationale, migration index, ERD, ledger lifecycle rules
    development-standards.md    Coding standards per language, what's automated vs. convention
    calculation-methodology.md  How figures are calculated (FX date convention so far)
    adr/0001..0004-*.md         Decisions: auto-migration, workspace context, shared security
                                master, HTTP optimistic concurrency (ETag/If-Match)
  user-stories/
    README.md                   Backlog index and suggested sequencing
    EPIC-01-*.md ... EPIC-28-*.md   Development-ready stories (section-40 template)
    BACKLOG-remaining-epics.md   Scoped, not-yet-decomposed epics (20-24, 29-32)
docker-compose.yml               Full stack (postgres + backend) - laptop or NAS, see below
```

## Database: automatic creation and migration on startup

Two things happen automatically, every time the application starts, with **no manual database
step in any environment**:

1. If the target PostgreSQL database does not exist yet, it is created
   (`com.trackmywealth.backend.config.DatabaseBootstrapInitializer`).
2. Every Flyway migration under `backend/src/main/resources/db/migration` that has not yet been
   applied is applied, in order (Spring Boot's standard Flyway integration). Adding a new
   `V<n>__description.sql` file is the entire process for a future schema change — it is picked
   up automatically on the next deployment.

See `docs/architecture/adr/0001-database-auto-migration.md` for the full rationale, and
`docs/architecture/database-schema.md` for what the schema actually contains and why.

The test suite applies every migration from scratch to a real PostgreSQL 16 instance
(Testcontainers) on each `./mvnw verify`, so a migration that does not apply fails the build.

## Running locally

Two ways to run the backend, depending on what you're doing:

**Actively developing the backend** (fast rebuild/reload):

```bash
docker compose up -d postgres          # bare PostgreSQL server, no database pre-created
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
                                        # dev profile may use the public JWT placeholder only
```

**Just running the whole stack** (this is also what a NAS deployment uses - see below):

```bash
printf 'JWT_SECRET=%s\n' "$(openssl rand -base64 32)" > .env   # once; keep this file private
docker compose up -d --build           # builds backend/Dockerfile, starts postgres + backend
```

The backend refuses to start when `JWT_SECRET` is absent on this deployment path. Starting only
the PostgreSQL service for development still works without it. Do not commit the generated `.env`;
keep the secret with the same care as other deployment credentials.

Either way, the API comes up on `:8080` under `/api/v1`. The OpenAPI document is at `/api-docs`
and its UI at `/api-docs/ui`; both need a signed-in user, like every non-public endpoint.

In a second terminal, start the mobile app (see `mobile/README.md` for target-specific backend
URLs - Android emulators can't reach `localhost` directly):

```bash
cd mobile
npm install
cp .env.example .env
npx expo start          # then choose iOS simulator, Android emulator, or web from the CLI output
```

## Mobile and web are the same app

`mobile/` is one Expo Router codebase for iOS, Android, **and web** — the web build is deployed
independently (its own static-site build/host, separate from app-store releases), but it shares
every screen and component with the native apps, so the experience stays identical without hand-
syncing two implementations. See `mobile/README.md`'s "Web app" section for the build/deploy
command. Because a browser enforces CORS (unlike a native app), the backend's allowed origins are
configurable via `app.cors.allowed-origin-patterns` in `application.yml` (env
`CORS_ALLOWED_ORIGINS`, comma-separated, defaults to `http://localhost:*` for local dev) — add
your deployed web app's real origin there in every other environment.

## Running on a NAS (or any single-machine self-hosted setup)

This project targets self-hosted, single-workspace use (a laptop or a home NAS), not a
multi-tenant hosted service — see `docs/architecture/adr/0001-database-auto-migration.md` for what
that does and doesn't simplify (the production DB role split it describes is a hosted-multi-tenant
concern; not needed here).

Before the first real start, create a private deployment secret:

```bash
printf 'JWT_SECRET=%s\n' "$(openssl rand -base64 32)" > .env
docker compose up -d --build
```

`docker-compose.yml` passes that value to the backend, whose startup guard rejects an absent,
short, trivially weak, or public-placeholder secret. The public JWT placeholder is accepted only
when the backend is explicitly started with the Spring `dev` or `test` profile; neither is a
deployment profile.

That's the whole deployment: `docker-compose.yml` builds `backend/Dockerfile` (multi-stage Maven
build -> a plain JRE runtime image) and starts it alongside `postgres`, wired together on Docker's
internal network. Both images are multi-arch for amd64 and arm64 (`postgres:16`, and
`eclipse-temurin:25-jre` for the backend's runtime), so this runs unchanged on an Intel/AMD or a
64-bit ARM NAS - point Synology's Container Manager (or any Docker host) at this
`docker-compose.yml` the same way. **32-bit ARM (armv7) is not supported:** the Java 25 runtime
image has no such variant.

### Actuator access

The backend exposes `/actuator/health`, `/actuator/info`, and `/actuator/flyway`. Health and
info remain public so container/platform probes (`/actuator/health/liveness` and
`/actuator/health/readiness`) work without application credentials; they report status only.
Everything else is available only to an authenticated `SYSTEM_ADMINISTRATOR`: health's details
(database, disk space, certificates), the `/actuator` index, and `/actuator/flyway` with its
schema and migration metadata. The same applies to any endpoint you expose in addition.

To switch the Flyway endpoint off entirely, expose only the probes:

```bash
MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info
```

### Time zone

The backend container runs in UTC, but "today" for a balance or net worth means *your* calendar
day: a purchase you book at 00:30 on the 15th belongs to the 15th even though it is still the 14th
in UTC. `BUSINESS_ZONE` (`app.business-zone`, default `Europe/Zurich` - Germany's zone has the
same offsets) names the zone whose date is used. Set it to another `java.time.ZoneId` only if you
live outside Switzerland/Germany.

### Secrets to set before real use

The JWT default in `application.yml` is a deliberately public development placeholder. It is
accepted only under the explicit Spring `dev` or `test` profile. Every other startup fails fast
until a real secret is supplied; a Compose backend start without it fails immediately.

- `JWT_SECRET` - signs access tokens and MFA login challenges. Generate cryptographically random
  key material with `openssl rand -base64 32` (at least 32 bytes of HMAC key material) and store it
  in the deployment's private `.env`/secret manager. Never reuse the repository placeholder.
- `MFA_ENCRYPTION_KEY` - AES-256 key encrypting each user's TOTP secret at rest (US-02-04): the
  Base64 of exactly 32 random bytes, e.g. `openssl rand -base64 32`. Left unset, the backend
  logs a startup warning (and refuses to start at all when `DEPLOYMENT_TOPOLOGY=hosted`). **Back it up with the same
  care as the database.** There is no key rotation yet: changing or losing it makes every
  already-enrolled user's authenticator secret undecryptable. There is also no lost-device
  recovery path (a known gap, per the story) - no admin reset exists either, so an affected user
  can only be unlocked directly in the database:
  `UPDATE app_user SET mfa_enabled = false, mfa_totp_secret = NULL WHERE email = '...';`

### FX rates

The backend loads the European Central Bank's daily euro reference rates by itself (#223): once at
every start and daily at 16:30 Europe/Berlin, with history back to the first booked transaction.
The request carries no user data. Conversions read source `ECB` (`FX_DEFAULT_SOURCE`); a rate older
than `FX_STALE_AFTER` (default `P5D`) is shown as stale. To run without outbound calls, set
`FX_IMPORT_ENABLED=false` and `FX_DEFAULT_SOURCE=MANUAL`, and enter rates yourself. The other
`FX_IMPORT_*` settings are documented in `application.yml`.

The web build (`mobile/`, see above) is not part of this compose file - export it
(`npx expo export -p web`) and serve `mobile/dist/` from any static file host, including one
running on the same NAS if you want everything on one box.

## Running behind a reverse proxy

`docker-compose.yml` above exposes the backend directly on `:8080` - no reverse proxy is part of
this project's deployment by default. If you put one in front (e.g. to add TLS - a NAS reverse-
proxy feature like Synology's, or your own nginx/Caddy/Traefik), two things need the proxy's
address configured, or the backend can't tell your proxy apart from any other caller:

- `TRUSTED_PROXIES` (`server.tomcat.remoteip.internal-proxies`, unset by default): a regex the
  proxy's own address must match, e.g. `192\.168\.1\.10` for a single host or `10\.0\.0\.\d+` for
  a subnet. Once set, X-Forwarded-For/X-Forwarded-Proto from that address are honored - restoring
  the real client's IP for `RateLimitFilter`'s per-source limiting (#60) and the real client's
  scheme for anything that checks `isSecure()`.
- Never set this to a pattern broad enough to match a real client's own address - a client that
  the backend trusts as "the proxy" can set its own X-Forwarded-For and pick whichever rate-limit
  bucket it likes, defeating the limiter entirely. It can also forge the address hashed into
  `UserSession.ipAddressHash` at login/setup (`TokenIssuanceService`) the same way. Only the
  proxy's own fixed address(es) should match.
- Your proxy must itself set (or overwrite, never blindly append/forward) X-Forwarded-For to its
  own view of the real client address - if it passes through whatever a client sent unmodified,
  trusting it is equivalent to not having this allowlist at all.

Without `TRUSTED_PROXIES` set, every caller behind a shared proxy collapses into one IP as far as
the backend can tell - correct as a safe default (nothing is trusted until configured), but it
means one client's failed logins can throttle every other client behind the same proxy too.

## Testing & CI

```bash
cd backend
./mvnw verify        # requires a local Docker daemon - Testcontainers starts real PostgreSQL 16
                     # instances. Runs the whole suite (~930 tests) and every gate: Spotless, PMD,
                     # SpotBugs, ArchUnit and the JaCoCo coverage minimums -
                     # see docs/architecture/development-standards.md
```

`.github/workflows/backend-ci.yml` runs `./mvnw -B verify` on every push/PR touching `backend/**`,
lints new or changed migrations with sqlfluff, and builds the Docker image. GitHub-hosted runners
have Docker preinstalled, so no extra CI setup is needed for Testcontainers.

`.github/workflows/mobile-web-ci.yml` type-checks, lints, formats-checks and tests `mobile/`, then
builds its web export (`npx expo export -p web`) on every push/PR touching `mobile/**`. It does
not deploy anywhere yet — wire your chosen static host's deploy step in once you've picked one
(Vercel, Netlify, Cloudflare Pages, ...).

`.github/workflows/semgrep.yml` (SAST, both languages) and `.github/workflows/gitleaks.yml` (secret
scanning) run on every push/PR across the whole repo; `.github/dependabot.yml` opens weekly
dependency-update PRs for Maven, npm, the backend's Docker base images, and the GitHub Actions
themselves.

See **`docs/architecture/development-standards.md`** for the full picture — what's enforced
automatically vs. convention-only, per language, and how to run each check locally before pushing.
A red check does not block merging on GitHub: branch protection and rulesets, which could
require these checks, are not available on this private repository's current GitHub plan (#187),
so check the PR's results before you merge.

## Where to start as a developer

Read `docs/user-stories/README.md` first — it lists every epic with development-ready stories,
which migrations each depends on, and a suggested build sequence (foundation → tenancy/auth →
workspace/institution/account → transaction ledger/imports/reconciliation → investment
core → budgeting/net worth/consolidated reporting). The working backlog is GitHub Issues (labels
`epic-NN`, `priority-must|should`, and `sprint-N` for the current sprint) on the repository's
project board; `docs/user-stories/` keeps the full story texts, including epics not yet turned
into issues.

## Loading the backlog into GitHub Issues

`scripts/create_github_issues.py` creates a GitHub Issue for every user story in
`docs/user-stories/EPIC-*.md` (plus one issue per not-yet-decomposed backlog epic in
`BACKLOG-remaining-epics.md`), labelled by epic and priority. This repository's issues already
exist, so it is only for a fresh repository or fork. It uses your own `gh` CLI login, so it must be
run from a machine where you're authenticated to GitHub.

```bash
gh auth login                      # once, if you haven't already
python3 scripts/create_github_issues.py --repo <owner>/<repo> --dry-run   # preview, creates nothing
python3 scripts/create_github_issues.py --repo <owner>/<repo>             # actually creates 96 issues
```

See the script's own docstring (`python3 scripts/create_github_issues.py --help`) for resuming a
partial run, running a single epic, and label/priority conventions.

## Status

**Backend: implemented for the core household-finance scope, in active development.** What exists
today (closed stories on GitHub; details in `docs/architecture/database-schema.md`):

- **Foundation, authentication and tenancy** (EPIC 01, 02, 28): automatic database creation and
  migration, first-administrator setup, shipped reference data, user administration, JWT login with
  rotating refresh tokens, session management, TOTP MFA; per-transaction workspace context with
  row-level security, object-level authorization with audited, non-enumerating 404s, and a
  cross-tenant test suite.
- **Workspace, institutions and accounts** (EPIC 03-05): fractional/joint ownership, sharing grants,
  last-member protection; institutions with summaries; every account type, archive/restore,
  institution reassignment, custom assets with dated valuations.
- **Currency** (EPIC 06): dated FX rates, conversion between any two currencies (direct pair,
  inverted, or via the euro), and a daily import of the ECB's euro reference rates (#223) with
  history from the first booking on and fetch-on-missing.
- **Transaction ledger** (EPIC 07): manual recording of every supported type; append-only removal
  (soft delete or void with a reversing entry), correction as removal plus replacement, restore
  within 30 days.
- **Categorization** (EPIC 08): automatic categorization with rules and fallback, member overrides
  that are never replaced silently, a custom hierarchical taxonomy.
- **Credit cards and transfers** (EPIC 09, US-10-01): card purchases as a liability, settlement
  matching, statement cycles, foreign-currency fees; internal transfers kept out of income and
  spending; a cash-flow summary.
- **Securities, snapshots, net worth** (US-12-01, US-25-01): lazy security master, manual account
  snapshots, and net-worth and account-balance reads.
- **API conventions** (EPIC 29, #153): RFC 9457 errors with stable codes, decimals as strings,
  correlation ids, ETag/If-Match on every read-modify-write endpoint, a published OpenAPI document,
  validation messages in English and German.

**Not built yet.** The complete list of remaining work is the story texts in `docs/user-stories/`
(`EPIC-*.md`, plus `BACKLOG-remaining-epics.md` for epics not yet broken into stories) and the open
GitHub issues. In short:

- **Not started:** the CSV import framework (the rest of EPIC 07 - the only planned import path),
  budgeting (EPIC 10 beyond transfers), market prices, portfolios, performance and allocation
  (EPIC 13-17), consolidated reporting (EPIC 19), pensions (EPIC 26), calculation verification
  (EPIC 27), financial goals (EPIC 20), backup/restore (EPIC 23) and most of the Quartz-based
  background jobs (EPIC 30; today the FX import is the only Quartz job, next to a Spring-scheduled
  retention of the authorization-denial log).
- **Started, with stories still open:**
  - EPIC 11: net worth over time, real estate net of financing, the liquidity view (US-11-02..04).
  - EPIC 12: identifier resolution, funds as weighted asset classes, protected overrides
    (US-12-02..04).
  - EPIC 18: investment performance in the institution summary (US-18-01).
  - EPIC 25: reconciliation and opening balances (US-25-02..04). Until opening balances exist, a
    cash, savings or depot account's balance is unknown (`valueKnown = false`). Only a credit card
    (from its ledger), a custom asset (from its valuations) and a loan or mortgage (its original
    principal) have a value.
  - The cross-cutting backlog epics 21, 22, 29, 30, 31 and 32 are partly covered by what is listed
    above (e.g. validation messages, MFA and rate limiting, the API conventions, the Docker
    deployment, the transaction lifecycle, the admin reference-data API); their remaining stories
    are in `BACKLOG-remaining-epics.md`.

**Mobile and web:** `mobile/` is still the generated Expo Router shell with theming and a tested
backend API client (including the If-Match helpers); its first product screens - sign-in (#183)
and the reference-data admin screen (#184) - are open stories. The web app is the same codebase
exported as a static site and independently deployable, not a web-native layout.

Deployment target is self-hosted, single-workspace (a laptop or a home NAS) — not a multi-tenant
hosted service, per current scope. `docker compose up -d --build` runs the whole backend + database
stack this way; see "Running on a NAS" above. Swiss bLink / PSD2 bank connectors (EPIC 24) are out
of scope entirely — CSV import via the template-driven framework (EPIC 07) is the only planned
import path.
