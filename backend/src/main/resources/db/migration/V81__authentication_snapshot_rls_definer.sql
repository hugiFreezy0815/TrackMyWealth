-- =============================================================================================
-- V81: authentication lookups before a request has a workspace RLS context (#321)
-- =============================================================================================
-- Authentication must know the caller's membership before WorkspaceContextTransactionExecution-
-- Listener can set app.current_workspace_id: the principal is what this lookup builds. Under
-- V20's FORCE ROW LEVEL SECURITY, a NOSUPERUSER NOBYPASSRLS runtime role sees no workspace_member
-- row at that point, so every principal would lose its workspace and an INACTIVE member would
-- authenticate like a user without membership.
--
-- Two SECURITY DEFINER functions are the only exception to RLS, each returning the least its
-- caller needs:
--   auth_user_snapshot(user, session)    JwtAuthenticationFilter, per request: the user's
--                                        authentication fields, its membership's workspace and
--                                        status, and the presented session's status. Only when
--                                        that session exists and belongs to that user; no row
--                                        otherwise, which the filter treats as unauthenticated.
--   auth_workspace_member_status(user)   login, MFA verification and refresh (US-02-06): the
--                                        linked member's status only, NULL without a membership.
-- Neither returns financial data, member display data or another user's rows.
--
-- FORCE RLS binds even a table owner, so the functions' OWNER must bypass RLS (a superuser today;
-- in production a dedicated NOLOGIN BYPASSRLS role, see database-schema.md, "Authentication
-- lookup with a hardened runtime role"). SET row_security = off makes a wrongly provisioned owner
-- fail with an error instead of silently reading no membership. search_path is pinned and every
-- table qualified, so no caller-controlled schema can shadow them.
-- =============================================================================================

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
        u.id,
        u.role,
        u.status,
        u.token_version,
        wm.workspace_id,
        wm.status,
        s.status,
        u.language
    FROM public.app_user AS u
    INNER JOIN public.user_session AS s
        ON s.id = p_session_id AND s.user_id = u.id
    LEFT JOIN public.workspace_member AS wm ON wm.id = u.workspace_member_id
    WHERE u.id = p_user_id;
$$;

CREATE FUNCTION public.auth_workspace_member_status(p_user_id UUID)
RETURNS TEXT
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET row_security = off
AS $$
    SELECT wm.status
    FROM public.app_user AS u
    INNER JOIN public.workspace_member AS wm ON wm.id = u.workspace_member_id
    WHERE u.id = p_user_id;
$$;

-- PostgreSQL grants EXECUTE on a new function to PUBLIC. Only the runtime role may call these.
REVOKE ALL ON FUNCTION public.auth_user_snapshot(UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.auth_workspace_member_status(UUID) FROM PUBLIC;

-- The runtime role of V20's role-split template, when provisioning created it before Flyway.
-- A role created later is granted EXECUTE by hand (database-schema.md); never grant to PUBLIC.
DO $$
BEGIN
    IF to_regrole('trackmywealth_runtime') IS NOT NULL THEN
        GRANT EXECUTE ON FUNCTION public.auth_user_snapshot(UUID, UUID)
        TO trackmywealth_runtime;
        GRANT EXECUTE ON FUNCTION public.auth_workspace_member_status(UUID)
        TO trackmywealth_runtime;
    END IF;
END;
$$;

COMMENT ON FUNCTION public.auth_user_snapshot(UUID, UUID) IS
'Authentication only (#321): the user and its own presented session; owner must bypass RLS.';

COMMENT ON FUNCTION public.auth_workspace_member_status(UUID) IS
'Authentication only (#321): the user''s workspace_member status; owner must bypass RLS.';
