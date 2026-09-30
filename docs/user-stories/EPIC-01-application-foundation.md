# EPIC 01 — Application Foundation & Configuration

Establishes that the application can be stood up against a bare PostgreSQL server with zero
manual database steps, and that reference data (institution catalogue, categories, templates)
ships with the release rather than starting empty. See
`docs/architecture/adr/0001-database-auto-migration.md` for the mechanism these stories verify.

---

## US-01-01 — Application creates its own database on first startup

**Actor:** Developer / self-hosting operator
**Objective:** PR-002 (deployment sovereignty) requires the application to be usable without a
dependency on project-operated infrastructure, including manual database provisioning.
**Story:** As a developer or self-hosting operator, I want the application to create its target
PostgreSQL database automatically on first startup, so that running the application against a
bare PostgreSQL server requires no manual `createdb` step.

**Preconditions:** A running PostgreSQL 16 server, reachable from the application, whose
credentials have `CREATEDB` privilege. No database matching `spring.datasource.url`'s database
name exists yet.

**Acceptance criteria:**
- Given a bare PostgreSQL server with no `trackmywealth` database, when the application starts,
  then `DatabaseBootstrapInitializer` creates the database before the primary `DataSource` bean
  is created, and startup proceeds without error.
- Given a PostgreSQL server where the target database already exists, when the application
  starts, then no `CREATE DATABASE` statement is issued and startup proceeds normally.
- Given `app.database.auto-create=false`, when the application starts against a bare server, then
  no bootstrap attempt is made and the application fails with the standard Spring Boot
  "connection refused / database does not exist" error (not a bootstrap-specific one).

**Applicable business rules:** PR-002, NFR-DEP-005.
**Data requirements:** None (infrastructure-level story).
**Error/edge cases:** Non-standard JDBC URL (e.g. a connection string with extra driver
parameters or a non-`jdbc:postgresql://` scheme) — bootstrap must skip silently rather than
throw, per `DatabaseBootstrapInitializer`'s documented behaviour. Database role without
`CREATEDB` — must fail soft and let the subsequent connection attempt produce the real error.
**Authorization/privacy:** None (pre-authentication, infrastructure step).
**Dependencies:** None — this is the first story in the whole backlog.
**Priority:** MUST.
**Definition of Done:** Verified against a real PostgreSQL 16 instance (not mocked) in an
integration test using Testcontainers, both for the "database does not exist" and "database
already exists" paths.
**Data-quality behaviour:** N/A (infrastructure story, precedes any user data).

---

## US-01-02 — Schema is fully created and versioned by Flyway on first startup

**Actor:** Developer / self-hosting operator
**Objective:** NFR-TEC-004 / NFR-DEP-005 — migrations are version-controlled and applied
automatically on deployment.
**Story:** As a developer, I want every Flyway migration under `db/migration` to be applied
automatically, in order, the first time the application connects to an empty database, so that
the full schema exists with no manual `psql -f` step.

**Preconditions:** US-01-01 complete (database exists, possibly just created).

**Acceptance criteria:**
- Given an empty (schema-less) database, when the application starts, then all migrations from
  `V1` through the current highest version are applied in order and `flyway_schema_history`
  records every one as successful.
- Given a database that already has some migrations applied (e.g. `V1`-`V15`), when the
  application starts after a release that adds `V16`-`V20`, then only `V16`-`V20` are applied.
- Given `spring.jpa.hibernate.ddl-auto=validate` (the fixed configuration), when Hibernate starts
  after Flyway, then entity mappings are validated against the migrated schema and startup fails
  loudly if they disagree (protects against drift between JPA entities and migrations).

**Applicable business rules:** NFR-TEC-004, NFR-DEP-001/002.
**Data requirements:** None.
**Error/edge cases:** A migration file is edited after being applied to any environment — Flyway
must fail startup with a checksum mismatch (this is default Flyway behaviour; the story is to
confirm the team's process treats that failure as "never edit an applied migration," per the
codebase convention already stated in the migration files' own comments, and per DB-08's
forward-only requirement).
**Authorization/privacy:** None.
**Dependencies:** US-01-01.
**Priority:** MUST.
**Definition of Done:** CI pipeline includes a job that starts the application against a fresh
Testcontainers PostgreSQL instance and asserts a 200 from `/actuator/health`.
**Data-quality behaviour:** N/A.

---

## US-01-03 — Initial administrator / first workspace bootstrap

**Actor:** New self-hosting operator, or the hosted service's first user
**Objective:** FR-USR-002 — initial setup shall establish at least one System Administrator.
**Story:** As the first person to run TrackMyWealth against a fresh database, I want a guided
setup step that creates the initial administrator account and their workspace, so that the
system is usable immediately without a pre-seeded user.

**Preconditions:** Migrated, empty database (no rows in `app_user`).

**Acceptance criteria:**
- Given a database with zero rows in `app_user`, when the setup endpoint/flow is invoked with an
  email, password and workspace name, then a `workspace` row is created, its default "Personal
  Assets" `financial_institution` is created automatically by the `V19` trigger, a
  `workspace_member` row is created for the administrator, and an `app_user` row is created with
  `role = 'SYSTEM_ADMINISTRATOR'` and linked to that member.
- Given a database that already has at least one `app_user`, when the setup endpoint is invoked
  again, then it is refused (setup is a one-time bootstrap, not a general "create admin"
  endpoint — see EPIC 02 for that).
- The application-side transaction sequence follows the documented bootstrap order in
  `V19__seed_reference_data.sql`'s trailing comment: pre-generate the workspace UUID,
  `SELECT set_config('app.current_workspace_id', ...)`, then insert — required for the
  row-level-security policies in `V20` to allow the workspace's own first rows to be written.

**Applicable business rules:** FR-USR-002/004, FR-INS-011, RULE-018.
**Data requirements:** Email (valid format, unique), password (meets FR-AUT-007 policy — see
EPIC 02), workspace display name.
**Error/edge cases:** Concurrent double-submission of the setup flow — must not create two
administrators; guard with a unique check plus a DB-level constraint (at most one setup attempt
should succeed; use a `SELECT ... FOR UPDATE` or advisory lock around the "does any app_user
exist" check).
**Authorization/privacy:** Endpoint must be unreachable once any `app_user` exists.
**Dependencies:** US-01-01, US-01-02, EPIC 02 (password hashing).
**Priority:** MUST.
**Definition of Done:** Integration test creates a fresh database, runs setup once (succeeds),
runs it again (rejected), and verifies the Personal Assets container exists for the new
workspace.
**Data-quality behaviour:** N/A (bootstrap step, no financial data yet).

---

## US-01-04 — Reference-data baseline ships with the release and is visible to administrators

**Actor:** Administrator
**Objective:** FR-REF-001/010 — every release ships a complete baseline reference-data set; its
version is visible.
**Story:** As an administrator, I want to see which reference-data baseline is currently loaded
(institution catalogue, default categories, fallback sector taxonomy), so that I know whether it
is current before relying on category or institution suggestions.

**Preconditions:** US-01-02 complete (V19 seed migration has run).

**Acceptance criteria:**
- Given a freshly migrated database, when an administrator opens the reference-data admin screen,
  then the current `reference_package.package_version` (`1.0.0-baseline` on a fresh install) and
  its `publication_date` are displayed.
- Given the shipped baseline, when a new workspace is created, then it can immediately search and
  select from the seeded `institution_catalogue` rows and see the default `category` tree
  (`workspace_id IS NULL` rows) without any additional setup.
- Given no reference package has ever been imported beyond the baseline, when the admin screen is
  viewed, then no staleness warning is shown (staleness warnings — FR-REF-011 — apply to
  effective-dated values with no entry for the current period, not to the baseline's mere age;
  see EPIC 32 for the full reference-data-distribution admin UI).

**Applicable business rules:** FR-REF-001/010, FR-CAT-002, FR-INS-007.
**Data requirements:** None beyond the seed data itself.
**Error/edge cases:** None at MVP — the full package-import/rollback flow (FR-REF-002..009) is
EPIC 32 scope, listed in `BACKLOG-remaining-epics.md`; this story only covers *reading* what
`V19` already seeded.
**Authorization/privacy:** Read access restricted to `SYSTEM_ADMINISTRATOR` (FR-TEN-007 —
administration rights, not financial-data access, so this is safe to gate purely on role).
**Dependencies:** US-01-02.
**Priority:** SHOULD.
**Definition of Done:** Endpoint returns `reference_package` row where `is_current = true`;
covered by an integration test against the seeded baseline.
**Data-quality behaviour:** N/A.

**Clarified before development (issue #148, 2026-09-29):**
- **Baseline versions:** V43 records `1.1.0-baseline` (the V37 categories and source-code mappings)
  as current. `1.0.0-baseline` stays as history, with its publication date corrected to 2026-09-03.
- **Endpoint:** `GET /api/v1/admin/reference-data` returns the package, content counts and
  staleness warnings (empty today).
- **US-02-05:** its API-level tests are added in the same story.
- **Mobile:** the admin screen is US-01-05 (#184), which needs the mobile sign-in, US-02-06 (#183).

---

## US-01-05 — Reference-data admin screen in the mobile app

Split from US-01-04 on 2026-09-29; the full story is issue #184. A read-only screen for
`SYSTEM_ADMINISTRATOR` users showing the loaded package, its content counts and staleness warnings,
backed by `GET /api/v1/admin/reference-data`. **Dependencies:** US-01-04, US-02-06 (mobile
sign-in). **Priority:** SHOULD. **Size:** S.
