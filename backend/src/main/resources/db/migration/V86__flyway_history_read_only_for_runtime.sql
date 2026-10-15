-- US-28-05 (#331, ADR 0005): with the database role split, the runtime role reads Flyway's history
-- but never writes it. role-split-database.sql grants the runtime role DML on every table through
-- default privileges, which on a fresh database include flyway_schema_history, created by Flyway
-- after that script ran; this revokes the writes. A single-role installation has no runtime role,
-- and nothing changes.
DO $$
BEGIN
    IF to_regrole('trackmywealth_runtime') IS NOT NULL
        AND to_regclass('public.flyway_schema_history') IS NOT NULL THEN
        REVOKE INSERT, UPDATE, DELETE ON public.flyway_schema_history
        FROM trackmywealth_runtime;
    END IF;
END;
$$;
