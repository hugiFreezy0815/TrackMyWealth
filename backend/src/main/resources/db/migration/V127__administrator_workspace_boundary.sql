-- US-03-08 / FR-USR-013: administrators never belong to a workspace.
-- A legacy membership may own financial history. Stop before changing any affected rows.
DO $$
DECLARE
    offending TEXT;
BEGIN
    SELECT string_agg(format('%s (member %s)', id, workspace_member_id), ', ' ORDER BY id)
    INTO offending
    FROM app_user
    WHERE role = 'SYSTEM_ADMINISTRATOR' AND workspace_member_id IS NOT NULL;

    IF offending IS NOT NULL THEN
        RAISE EXCEPTION 'administrator_workspace_boundary: administrator IDs and memberships: %. Reset the development database or prepare a data-preserving migration before retrying.',
            offending USING ERRCODE = '23514';
    END IF;
END;
$$;

ALTER TABLE app_user
ADD CONSTRAINT chk_app_user_admin_has_no_workspace_member
CHECK (role <> 'SYSTEM_ADMINISTRATOR' OR workspace_member_id IS NULL);

-- Metadata only. This uses the same owner and RLS configuration as V81's auth lookups.
-- SECURITY DEFINER functions are never inlined, so the page is cut inside the function: a caller's
-- LIMIT/OFFSET outside it would run only after the whole table was read and sorted.
CREATE FUNCTION public.admin_workspace_metadata(page_limit INTEGER, page_offset BIGINT)
RETURNS TABLE (id UUID, name TEXT)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET row_security = off
AS $$
    SELECT w.id, w.name
    FROM public.workspace AS w
    ORDER BY w.id
    LIMIT page_limit OFFSET page_offset;
$$;

CREATE FUNCTION public.admin_workspace_count()
RETURNS BIGINT
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET row_security = off
AS $$
    SELECT count(*) FROM public.workspace;
$$;

REVOKE ALL ON FUNCTION public.admin_workspace_metadata(INTEGER, BIGINT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.admin_workspace_count() FROM PUBLIC;

DO $$
BEGIN
    IF to_regrole('trackmywealth_runtime') IS NOT NULL THEN
        GRANT EXECUTE ON FUNCTION public.admin_workspace_metadata(INTEGER, BIGINT)
        TO trackmywealth_runtime;
        GRANT EXECUTE ON FUNCTION public.admin_workspace_count() TO trackmywealth_runtime;
    END IF;
END;
$$;
