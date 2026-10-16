-- US-28-05 (#331, ADR 0005): hands the TrackMyWealth database to the migration role and grants the
-- runtime role what the running application needs. Run by role-split.sh as the PostgreSQL
-- superuser, connected to the TrackMyWealth database, after role-split-cluster.sql and after the
-- database itself was given to trackmywealth_migration. Idempotent, and all or nothing (one
-- transaction); the roles and the database's owner, set before it, are rerun safely.
--
-- On a fresh database there is nothing to hand over yet; on an existing single-role installation
-- every object of schema public still belongs to the old role. REASSIGN OWNED cannot be used: that
-- role is usually the bootstrap superuser, which also owns system objects. Members of an extension
-- (citext's functions, ...) keep their owner, and a sequence owned by a column follows its table.

BEGIN;

ALTER SCHEMA public OWNER TO trackmywealth_migration;

DO $$
DECLARE
    migration CONSTANT OID := 'trackmywealth_migration'::REGROLE;
    object RECORD;
BEGIN
    FOR object IN
        SELECT
            c.oid::REGCLASS AS name,
            CASE c.relkind
                WHEN 'S' THEN 'SEQUENCE'
                WHEN 'v' THEN 'VIEW'
                WHEN 'm' THEN 'MATERIALIZED VIEW'
                WHEN 'f' THEN 'FOREIGN TABLE'
                ELSE 'TABLE'
            END AS kind
        FROM pg_class AS c
        WHERE c.relnamespace = 'public'::REGNAMESPACE
            AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
            AND c.relowner <> migration
            AND NOT EXISTS (
                SELECT 1
                FROM pg_depend AS d
                WHERE d.classid = 'pg_class'::REGCLASS
                    AND d.objid = c.oid
                    AND (d.deptype = 'e' OR (c.relkind = 'S' AND d.deptype IN ('a', 'i')))
            )
    LOOP
        EXECUTE format('ALTER %s %s OWNER TO trackmywealth_migration', object.kind, object.name);
    END LOOP;

    FOR object IN
        SELECT
            p.oid::REGPROCEDURE AS name,
            CASE p.prokind WHEN 'p' THEN 'PROCEDURE' WHEN 'a' THEN 'AGGREGATE' ELSE 'FUNCTION' END
                AS kind
        FROM pg_proc AS p
        WHERE p.pronamespace = 'public'::REGNAMESPACE
            AND p.proowner <> migration
            AND NOT EXISTS (
                SELECT 1
                FROM pg_depend AS d
                WHERE d.classid = 'pg_proc'::REGCLASS AND d.objid = p.oid AND d.deptype = 'e'
            )
    LOOP
        EXECUTE format('ALTER %s %s OWNER TO trackmywealth_migration', object.kind, object.name);
    END LOOP;

    -- Domains, enums, ranges and standalone composite types; a table's row type follows the table.
    FOR object IN
        SELECT
            t.oid::REGTYPE AS name,
            CASE t.typtype WHEN 'd' THEN 'DOMAIN' ELSE 'TYPE' END AS kind
        FROM pg_type AS t
        LEFT JOIN pg_class AS c ON c.oid = t.typrelid
        WHERE t.typnamespace = 'public'::REGNAMESPACE
            AND t.typowner <> migration
            AND t.typtype IN ('d', 'e', 'r', 'c')
            AND (t.typtype <> 'c' OR c.relkind = 'c')
            AND NOT EXISTS (
                SELECT 1
                FROM pg_depend AS d
                WHERE d.classid = 'pg_type'::REGCLASS AND d.objid = t.oid AND d.deptype = 'e'
            )
    LOOP
        EXECUTE format('ALTER %s %s OWNER TO trackmywealth_migration', object.kind, object.name);
    END LOOP;
END;
$$;

-- Nobody but the owner creates objects in public (PostgreSQL 15+ default, made explicit).
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO trackmywealth_runtime;

-- Only the two roles connect, and only the owner creates temporary tables (migrations such as
-- V37 do; the application never does). PostgreSQL grants both to PUBLIC by default; the runtime
-- role would otherwise be able to create temporary objects that shadow the schema's for its own
-- session. The superuser keeps both regardless.
DO $$
BEGIN
    EXECUTE format('REVOKE CONNECT, TEMPORARY ON DATABASE %I FROM PUBLIC', current_database());
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO trackmywealth_runtime', current_database());
END;
$$;

-- The runtime role reads and writes rows, under each table's RLS policies, and nothing else: no
-- DDL, no TRUNCATE, no ownership. Default privileges extend this to every table and sequence a
-- later migration creates.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO trackmywealth_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO trackmywealth_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE trackmywealth_migration IN SCHEMA public
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO trackmywealth_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE trackmywealth_migration IN SCHEMA public
GRANT USAGE, SELECT ON SEQUENCES TO trackmywealth_runtime;

DO $$
BEGIN
    -- Flyway's history is the migration role's alone; the runtime role may read it (V86 does the
    -- same on a fresh database, where the table does not exist yet at this point).
    IF to_regclass('public.flyway_schema_history') IS NOT NULL THEN
        REVOKE INSERT, UPDATE, DELETE ON public.flyway_schema_history FROM trackmywealth_runtime;
    END IF;
    -- V81's authentication functions, when this database has them already (an existing
    -- installation); on a fresh one V81 grants this itself, since the role exists by then.
    IF to_regprocedure('public.auth_user_snapshot(uuid, uuid)') IS NOT NULL THEN
        GRANT EXECUTE ON FUNCTION public.auth_user_snapshot(UUID, UUID) TO trackmywealth_runtime;
    END IF;
    IF to_regprocedure('public.auth_workspace_member_status(uuid)') IS NOT NULL THEN
        GRANT EXECUTE ON FUNCTION public.auth_workspace_member_status(UUID)
        TO trackmywealth_runtime;
    END IF;
END;
$$;

COMMIT;
