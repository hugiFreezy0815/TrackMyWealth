-- =============================================================================================
-- V81: narrow authentication lookup before a request has a workspace RLS context (#321)
-- =============================================================================================
-- JWT authentication must resolve the caller's workspace before the transaction listener can
-- set app.current_workspace_id. Ordinary joins to workspace_member are therefore invisible to
-- a NOSUPERUSER NOBYPASSRLS runtime role under V20's FORCE ROW LEVEL SECURITY.
--
-- Only the authentication fields below are exposed; no financial data or member display data.
-- A non-NULL session id must belong to the user, or the function returns no row. NULL session
-- ids are for the pre-token login / MFA / refresh eligibility check only.
--
-- SECURITY DEFINER is intentional. Because V20 uses FORCE RLS, the function OWNER must have
-- BYPASSRLS (or be a superuser) even when it owns the tables. SET row_security=off makes an
-- incorrectly provisioned owner fail loudly instead of silently returning an empty membership.
-- Use a dedicated NOLOGIN BYPASSRLS function owner with SELECT only on these three tables in
-- production; see docs/architecture/database-schema.md.
CREATE FUNCTION public.auth_user_snapshot(p_user_id UUID, p_session_id UUID)
RETURNS TABLE (
    user_id UUID,
    user_role TEXT,
    user_status TEXT,
    user_token_version INTEGER,
    workspace_id UUID,
    workspace_member_status TEXT,
    session_status TEXT,
    user_language TEXT
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET row_security = off
AS $$
    SELECT
        u.id, u.role, u.status, u.token_version,
        wm.workspace_id, wm.status, s.status, u.language
    FROM public.app_user AS u
    LEFT JOIN public.workspace_member AS wm ON wm.id = u.workspace_member_id
    LEFT JOIN public.user_session AS s
        ON s.id = p_session_id AND s.user_id = u.id
    WHERE u.id = p_user_id AND (p_session_id IS NULL OR s.id IS NOT NULL);
$$;

-- PostgreSQL grants EXECUTE on new functions to PUBLIC by default. Never expose this privileged
-- lookup to arbitrary database users, including future roles created after this migration.
REVOKE ALL ON FUNCTION public.auth_user_snapshot(UUID, UUID) FROM PUBLIC;

-- Provisioning normally creates the runtime role before running Flyway. If the role is created
-- later, grant EXECUTE manually as documented; do not widen the grant to PUBLIC.
DO $$
BEGIN
    IF to_regrole('trackmywealth_runtime') IS NOT NULL THEN
        GRANT EXECUTE ON FUNCTION public.auth_user_snapshot(UUID, UUID)
            TO trackmywealth_runtime;
    END IF;
END;
$$;

COMMENT ON FUNCTION public.auth_user_snapshot(UUID, UUID) IS
    'Authentication-only SECURITY DEFINER lookup (#321). Owner must bypass FORCE RLS. Returns only the requested user and (when provided) that user''s session; no tenant finance data.';
