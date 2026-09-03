# ADR 0001: Automatic database creation and schema migration on application startup

## Status
Accepted

## Context

The user requested two things explicitly:

1. The database shall be deployed automatically when the Spring Boot application starts, if it
   does not already exist in PostgreSQL.
2. Future database changes (new/updated migrations) shall also be deployed automatically on
   application startup.

This also happens to be a hard requirement of the specification itself:

- **NFR-DEP-005** — "Container images and a documented, reproducible deployment procedure
  including database migration shall be part of every release, not an afterthought."
- **NFR-TEC-004** — "Schema migrations shall be version-controlled, applied automatically on
  deployment, and never destructive to user-visible balances without in-application explanation."
- **PR-002 (deployment sovereignty)** — a self-hosting household must be able to run the entire
  system on infrastructure it controls, with no dependency on the project's own infrastructure.
  A manual `psql -f schema.sql` step run by a non-technical household is not compatible with that
  goal; the application must be able to stand itself up against a bare PostgreSQL server.

## Decision

**Flyway** owns the schema, in `spring-boot-starter`'s default configuration
(`spring.flyway.enabled=true`, migrations under `src/main/resources/db/migration`), with
`spring.jpa.hibernate.ddl-auto=validate` so Hibernate never mutates the schema itself. This is
what satisfies requirement 2: every new `V<n>__description.sql` file a developer adds is applied,
in order, the next time the application starts, anywhere from a laptop to production. Nothing
Flyway-specific needs to be built for this - it is the framework's default behaviour once the
dependency is present and `ddl-auto` is not left at a mutating value.

Requirement 1 - creating the *database itself*, not just its schema - is not something Flyway can
do, because by the time Flyway runs, Spring Boot's own `DataSource` auto-configuration has already
opened (or failed to open) a connection to the target database. On a bare PostgreSQL server (the
expected starting point for a self-hosting household, per PR-002), that connection fails before
Flyway is ever reached.

`com.trackmywealth.backend.config.DatabaseBootstrapInitializer` closes that gap. It is a Spring
`ApplicationContextInitializer` (registered via
`META-INF/spring/org.springframework.boot.ApplicationContextInitializer.imports`, so it does not
depend on any bean being defined yet), ordered to run before `DataSourceAutoConfiguration`. It:

1. reads `spring.datasource.url` and parses out host, port and target database name;
2. opens a short-lived connection to the PostgreSQL **maintenance database**
   (`app.database.maintenance-database`, default `postgres`) using the same credentials;
3. issues `CREATE DATABASE` only if the target database is not already listed in `pg_database`;
4. lets normal startup continue - the primary `DataSource` then connects successfully, and Flyway
   runs against it as usual.

Combined, a single `docker run`/`java -jar` against a completely bare PostgreSQL server is
sufficient: the database is created, then every migration up to the current version is applied,
with no manual step in either topology (self-hosted or hosted, per section 48.1 - the two
topologies run the identical application, database schema and migration path; NFR-DEP-001/002).

## Consequences

- The database role the application connects as must have `CREATEDB` privilege (or be a
  superuser) for step 3 to succeed. A hosted deployment that provisions the database out of band
  via infrastructure-as-code, and deliberately runs the application role without `CREATEDB`, can
  disable this behaviour with `app.database.auto-create=false` (env `DB_AUTO_CREATE=false`) - see
  `DatabaseBootstrapInitializer`'s Javadoc.
- `DatabaseBootstrapInitializer` fails soft (logs and continues) rather than failing hard, so that
  a hosted deployment with a deliberately restricted role gets the *normal* Spring Boot startup
  failure message on the next connection attempt, rather than a confusing bootstrap-specific one.
- Because Flyway migrations run on every startup, they must remain forward-only and safe to apply
  to a database that may already be a few versions behind (this is Flyway's own model, not
  something this project has to build). NFR-REL-003 additionally requires that no migration
  silently relocate or alter a user-visible balance without an in-app explanation - this is a
  review discipline for future migrations, not something the tooling enforces automatically.
- Row-level security (`V20__tenancy_row_level_security.sql`) is enabled with `FORCE ROW LEVEL
  SECURITY`. In this scaffold, the same database role runs both migrations and the running
  application, which is adequate for local development and for a single-household self-hosted
  deployment, but should be split into a migration-owner role and a lower-privilege runtime role
  before a multi-household hosted deployment goes live - see the commented-out role split at the
  bottom of that migration file and `docs/architecture/database-schema.md`.

## Alternatives considered

- **Liquibase instead of Flyway.** Both satisfy the "automatic on startup" requirement equally
  well; Flyway's plain-SQL migrations were chosen because the schema leans heavily on PostgreSQL-
  specific features (declarative partitioning, row-level security, generated columns, trigger-
  based invariants) that are more natural to express directly in SQL than through Liquibase's
  changelog abstraction.
- **A Testcontainers-only / Docker-Compose-`POSTGRES_DB`-only approach** (letting the Postgres
  image itself create the database via its `POSTGRES_DB` environment variable) was rejected as
  the *primary* mechanism because it only works when the operator controls how PostgreSQL itself
  is started - which is not guaranteed in a self-hosted deployment where a household may point the
  application at an existing, separately-managed PostgreSQL server (see `docker-compose.yml`'s
  comment for how it interacts with `DatabaseBootstrapInitializer` in local development).
