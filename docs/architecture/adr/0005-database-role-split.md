# ADR 0005: Database role split - a migration owner and an RLS-bound runtime role

## Status
Accepted (US-28-05, #331; product owner decisions of 2026-10-15)

## Context

Workspace isolation is enforced by PostgreSQL row-level security (V20, `FORCE ROW LEVEL
SECURITY` on every workspace table). Until now the application ran its migrations and its own
queries as one role, which in every shipped setup (docker compose, Testcontainers) is the
PostgreSQL superuser. A superuser bypasses every policy, so the isolation rested on the
application code alone; one missing workspace filter or an SQL injection would have exposed every
workspace. V20 and `database-schema.md` named the fix - a migration-owner role and a non-owner
runtime role - but it was not wired up. #321 (PR #329) first made authentication work under such a
runtime role (V81's `SECURITY DEFINER` lookups), which unblocked it.

ADR 0001 also has to keep holding: a self-hosting household stands the system up with one
`docker compose up`, without a manual database step.

## Decision

**Two roles**, created by the scripts in `deploy/postgres`:

- `trackmywealth_migration` (`LOGIN NOSUPERUSER BYPASSRLS`) owns the database and every schema
  object and runs Flyway. The backend switches Flyway to it when `DB_MIGRATION_USERNAME` is set
  (`DatabaseRoleConfig`, a Flyway customizer: an empty compose variable must not name a user "").
- `trackmywealth_runtime` (`LOGIN NOSUPERUSER NOBYPASSRLS`) is the backend's datasource: row
  reads and writes under RLS, `EXECUTE` on V81's lookups, read-only Flyway history (V86).

**Provisioning (decision 1): a one-time script, never the backend.** On a fresh compose volume
the postgres image runs `initdb/10-trackmywealth-roles.sh`, which runs `role-split.sh` as the
superuser when `DB_MIGRATION_PASSWORD` is set. The backend only ever receives the migration and
runtime passwords. Rejected: the backend creating the roles itself at first start, as it creates
its database (ADR 0001) - fully automatic, but the running backend would hold superuser
credentials, which defeats the point.

**Rollout (decision 2): opt-in, with a startup check.** Without `DB_MIGRATION_USERNAME` nothing
changes: one role does both, as before. At startup `DatabaseRoleConfig` warns when the runtime
role is not bound by RLS, and refuses to start with `DB_REQUIRE_RLS_BOUND_ROLE=true` or
`app.deployment.topology=hosted` (as a missing MFA key already does there). Not bound means: it,
or any role it may act as (`pg_has_role(..., 'MEMBER')`), is a superuser or `BYPASSRLS`, or owns
schema public or one of its tables - `FORCE ROW LEVEL SECURITY` binds an owner only until the
owner switches it off. The check runs once every bean exists, after Flyway and before the web
server and the Quartz scheduler start, so a refused start serves nothing. Rejected: enforcing
the split everywhere - every existing installation would have had to migrate before upgrading.

**Function owner (decision 3): the migration role.** It needs `BYPASSRLS` anyway - V37 runs with
`row_security = off` and migrations write shared rows - so it can own the `SECURITY DEFINER`
functions Flyway creates, with nothing to hand over. Rejected: a dedicated `NOLOGIN BYPASSRLS`
owner with `SELECT` on three tables only - least privilege behind the two functions, but one more
role to provision and an ownership transfer after every migration that replaces a function. The
functions' SQL is fixed, `search_path` is pinned and only the runtime role may call them, so the
broader owner does not widen what a caller can read.

**Existing installations (decision 4): an explicit upgrade.** Nothing changes until the operator
backs up and runs `role-split.sh` (README, "Database roles"). It hands every object of schema
public to the migration role object by object - `REASSIGN OWNED` fails for the bootstrap
superuser, which also owns system objects - and grants the runtime role. It is idempotent and all
or nothing. Rejected: converting existing volumes automatically on the next `docker compose up`
- transparent, but it would change ownership in a household's real database without them
deciding to.

## Consequences

- With the split, a query that misses its workspace filter can no longer read or change another
  workspace's rows, and the application's connection can neither switch RLS off nor change the
  schema; the database refuses them.
- **Residual risk: SQL injection.** The policies read the current workspace from the session
  setting `app.current_workspace_id`, which the runtime role sets itself and so may set to any
  value. Workspace ids are UUIDs, but they are not secret to the database: `workspace_id` is
  readable outside RLS in `transfer_detection_fx_pending` (`SchemaConventionsTest`'s
  `WORKSPACE_TABLES_OUTSIDE_RLS`), and an injection can call `set_config` itself. The split
  narrows an injection's reach; parameterized queries remain the defense against it. Closing
  this would need a context the runtime role cannot forge (e.g. a `SECURITY DEFINER` setter that
  checks a signed token) - not in scope here.
- The runtime role cannot change the schema, truncate tables or rewrite Flyway's history.
- The two role names are fixed: V81 and V86 grant to `trackmywealth_runtime` by name.
- A migration that needs more than `BYPASSRLS` and ownership (an untrusted extension, a new role)
  can no longer rely on Flyway running as a superuser; it needs a provisioning step.
- New grants follow the default privileges set by `role-split-database.sql`: a later migration's
  tables and sequences are usable by the runtime role without a grant of its own; a new
  `SECURITY DEFINER` function needs its `EXECUTE` grant written explicitly, as V81 does.
- Tests: `RoleSplitScriptsTest` (the scripts on existing and fresh databases),
  `HardenedRuntimeAuthenticationHttpTest` and `UpgradedRoleSplitAuthenticationHttpTest` (the
  application's HTTP path on a fresh and on an upgraded split, strict), `DatabaseRoleCheckTest`
  (the startup check against real roles, and that a refused start starts no lifecycle bean),
  `DatabaseRoleConfigTest`.
