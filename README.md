# TrackMyWealth

Privacy-first personal finance and wealth-management platform for individuals and households in
Switzerland and Germany. This repository contains the database design and the Spring Boot backend
scaffold derived from the project's requirements baseline (see the `TrackMyWealth` Claude project
for `requirements-personal-wealth-platform.md` and
`TrackMyWealth_Consolidated_Requirements_Specification_v5.docx`).

## What's here

```
.github/workflows/backend-ci.yml    Backend tests, formatting/static analysis, SQL lint, Docker build
.github/workflows/mobile-web-ci.yml Type-check, lint, format-check, test, web export build
.github/workflows/semgrep.yml       SAST (Java + JS/TS), every push/PR
.github/workflows/gitleaks.yml      Secret scanning, every push/PR
.github/dependabot.yml              Weekly dependency-update PRs (Maven, npm, Docker, Actions)
.sqlfluff / .sqlfluffignore         SQL lint config - see docs/architecture/development-standards.md
backend/                         Spring Boot 3 / Java 17 backend
  mvnw / mvnw.cmd / .mvn/          Maven Wrapper - always build via ./mvnw, not a local mvn
  Dockerfile                     Multi-stage build -> runtime image (amd64/arm64/armv7)
  pom.xml                        Spotless/PMD/SpotBugs/JaCoCo wired into the verify phase
  src/main/java/.../TrackMyWealthApplication.java
  src/main/java/.../config/DatabaseBootstrapInitializer.java   <- see "Database" below
  src/main/java/.../config/SecurityConfig.java                  <- placeholder posture + CORS, see EPIC-02/28
  src/main/resources/application.yml
  src/main/resources/db/migration/V1..V21__*.sql               <- the full MVP schema
  src/main/resources/db/migration/V90__quartz_schema.sql       <- background-job store (EPIC 30)
  src/test/java/.../architecture/ArchitectureTest.java           <- layering rules (ArchUnit)
  src/test/java/.../db/SchemaConventionsTest.java                 <- US-28-03 (no SERIAL PKs)
  src/test/java/.../TrackMyWealthApplicationStartupTest.java    <- US-01-02
mobile/                          React Native + Expo (SDK 57) client - iOS, Android, AND web
  app.json
  eslint.config.js / .prettierrc.json  Lint + format config
  src/app/                       File-based routes, shared across all three platforms
  src/api/client.ts               Fetch wrapper around the backend (EXPO_PUBLIC_API_URL), tested
  README.md                      Mobile + web setup, run, and deploy instructions
docs/
  architecture/
    database-schema.md          Design rationale, migration index, ERD
    development-standards.md    Coding standards per language, what's automated vs. convention
    adr/0001-database-auto-migration.md
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

All 20 migrations have been run end-to-end against a real PostgreSQL 16 instance as part of
producing this repository, including a positive/negative row-level-security isolation test run as
a non-superuser database role.

## Running locally

Two ways to run the backend, depending on what you're doing:

**Actively developing the backend** (fast rebuild/reload):

```bash
docker compose up -d postgres          # bare PostgreSQL server, no database pre-created
cd backend
mvn spring-boot:run                    # creates the database, runs all migrations, starts the API
```

**Just running the whole stack** (this is also what a NAS deployment uses - see below):

```bash
docker compose up -d --build           # builds backend/Dockerfile, starts postgres + backend
```

Either way, the API comes up on `:8080`; OpenAPI UI at `/api-docs/ui` once the controller layer is
built out (see `docs/user-stories/EPIC-29-*` in the backlog).

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

This project targets self-hosted, single-household use (a laptop or a home NAS), not a
multi-tenant hosted service — see `docs/architecture/adr/0001-database-auto-migration.md` for what
that does and doesn't simplify (the production DB role split it describes is a hosted-multi-tenant
concern; not needed here).

```bash
docker compose up -d --build
```

That's the whole deployment: `docker-compose.yml` builds `backend/Dockerfile` (multi-stage Maven
build -> a plain JRE runtime image) and starts it alongside `postgres`, wired together on Docker's
internal network. Both images are multi-arch (`postgres:16` and `eclipse-temurin:17-jre` each ship
amd64, arm64, and 32-bit ARM variants), so this should run unchanged regardless of whether your
NAS is Intel/AMD or ARM-based - point Synology's Container Manager (or any Docker host) at this
`docker-compose.yml` the same way.

The web build (`mobile/`, see above) is not part of this compose file - export it
(`npx expo export -p web`) and serve `mobile/dist/` from any static file host, including one
running on the same NAS if you want everything on one box.

## Testing & CI

```bash
cd backend
./mvnw verify        # requires a local Docker daemon - Testcontainers starts real PostgreSQL 16
                     # instances for the DB-level tests, and this also runs formatting/static
                     # analysis (Spotless, PMD, SpotBugs) - see docs/architecture/development-standards.md
```

`.github/workflows/backend-ci.yml` runs the same command on every push/PR touching `backend/**` —
GitHub-hosted runners have Docker preinstalled, so no extra CI setup is needed for Testcontainers.
This is the first slice of test coverage; most epics' Definition of Done (see
`docs/user-stories/`) still needs its corresponding implementation and tests written.

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

## Where to start as a developer

Read `docs/user-stories/README.md` first — it lists every epic with development-ready stories,
which migrations each depends on, and a suggested build sequence (foundation → tenancy/auth →
household/institution/account → transaction ledger/imports/reconciliation → investment
core → budgeting/net worth/consolidated reporting).

## Loading the backlog into GitHub Issues

`scripts/create_github_issues.py` creates a real GitHub Issue for every one of the 87 user
stories in `docs/user-stories/EPIC-*.md` (plus one issue per not-yet-decomposed backlog epic in
`BACKLOG-remaining-epics.md`), labelled by epic and priority. It uses your own `gh` CLI login, so
it must be run from a machine where you're authenticated to GitHub — it is not run as part of
producing this repository.

```bash
gh auth login                      # once, if you haven't already
python3 scripts/create_github_issues.py --repo <owner>/<repo> --dry-run   # preview, creates nothing
python3 scripts/create_github_issues.py --repo <owner>/<repo>             # actually creates 96 issues
```

See the script's own docstring (`python3 scripts/create_github_issues.py --help`) for resuming a
partial run, running a single epic, and label/priority conventions.

## Status

This is architecture and schema design output: the database schema (20 Flyway migrations,
validated against a live PostgreSQL instance), the automatic-provisioning mechanism, and the full
MVP user-story backlog are complete. The Spring Boot application currently contains only the
startup/migration scaffolding described above (plus a placeholder security posture and CORS
config), and the Expo app (`mobile/`) is currently only the generated navigation/theming shell
plus a backend API client stub — controllers, services, repositories, and real screens are not yet
implemented; they are what the user stories in `docs/user-stories/` hand off to a development team
to build. The web app is real and independently deployable (static export, own CI build), but it
is exactly the mobile scaffold's screens reflowed into a browser, not a web-native layout — that's
a deliberate simplification, not yet revisited.

Deployment target is self-hosted, single-household (a laptop or a home NAS) — not a multi-tenant
hosted service, per current scope. `docker compose up -d --build` runs the whole backend + database
stack this way today, verified end-to-end (built the image, started both containers, confirmed
`/actuator/health` returns `200 UP` through the containerized backend talking to the containerized
Postgres); see "Running on a NAS" above. Swiss bLink / PSD2 bank connectors (EPIC 24) are out of
scope entirely — CSV import via the template-driven framework (EPIC 07) is the only import path.
