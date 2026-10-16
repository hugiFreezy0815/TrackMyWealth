-- US-28-05 (#331, ADR 0005): the two TrackMyWealth database roles. Run by role-split.sh as the
-- PostgreSQL superuser, in any database. Idempotent: a second run only sets the passwords again.
--
--   trackmywealth_migration  owns the database and every schema object and runs Flyway. BYPASSRLS
--                            because migrations read and write across workspaces (e.g. V37 runs
--                            with row_security = off), and it owns V81's SECURITY DEFINER
--                            authentication functions, whose owner must bypass FORCE RLS.
--   trackmywealth_runtime    the running application: bound by row-level security, DML only.
--                            The name is fixed: V81 grants it EXECUTE on those functions.
--
-- The passwords come from the environment (TMW_MIGRATION_PASSWORD, TMW_RUNTIME_PASSWORD), never
-- from the command line, where any process listing would show them.

\getenv migration_password TMW_MIGRATION_PASSWORD
\getenv runtime_password TMW_RUNTIME_PASSWORD

DO $$
BEGIN
    IF to_regrole('trackmywealth_migration') IS NULL THEN
        CREATE ROLE trackmywealth_migration;
    END IF;
    IF to_regrole('trackmywealth_runtime') IS NULL THEN
        CREATE ROLE trackmywealth_runtime;
    END IF;
END;
$$;

ALTER ROLE trackmywealth_migration
WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE BYPASSRLS PASSWORD :'migration_password';

ALTER ROLE trackmywealth_runtime
WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD :'runtime_password';
