-- =============================================================================================
-- V110: workspace membership is the only access grant; every member is a user (US-03-06, #389)
-- =============================================================================================
-- FR-TEN-008/FR-HOU-007: every ACTIVE member of a workspace has full access to everything in it.
-- Account ownership (V6 account_ownership) and per-scope grants (V6 sharing_grant) are withdrawn
-- (OPEN-021 closed), so both tables go, with their RLS policies, indexes and triggers.
--
-- FR-USR-008: every member is a System User with a login. A login-less "dependent" member no
-- longer exists, so is_dependent goes, and the link becomes mandatory from the member's side:
--   * exactly one app_user links to each workspace_member (unique app_user.workspace_member_id;
--     the column itself stays nullable - a SYSTEM_ADMINISTRATOR has no membership, FR-TEN-007);
--   * a new workspace_member without a linking app_user is refused at commit (a deferred
--     constraint trigger), so a member and its user are still inserted in either order within one
--     transaction, as SetupService and AdminUserService do;
--   * an app_user that lets go of its member is refused at commit unless that member is removed
--     in the same transaction, so a member can never be left without a login later either.
--     Users are disabled, never deleted (FR-USR-011), so a DELETE of app_user is not checked.
--
-- The dev phase has no production data. A database that already holds a member without a login,
-- or two logins on one member, stops here with a message rather than being silently repaired.
-- =============================================================================================

DO $$
DECLARE
    offending TEXT;
BEGIN
    SELECT string_agg(member.id::TEXT, ', ' ORDER BY member.id) INTO offending
    FROM workspace_member AS member
    WHERE NOT EXISTS (SELECT 1 FROM app_user AS u WHERE u.workspace_member_id = member.id);
    IF offending IS NOT NULL THEN
        RAISE EXCEPTION 'workspace_member_requires_login: workspace members % have no login (V110, FR-USR-008). Every member must be a user; reset the development database or link each member to an app_user, then restart.',
            offending USING ERRCODE = '23514';
    END IF;

    SELECT string_agg(u.workspace_member_id::TEXT, ', ' ORDER BY u.workspace_member_id)
    INTO offending
    FROM app_user AS u
    WHERE u.workspace_member_id IS NOT NULL
    GROUP BY u.workspace_member_id
    HAVING count(*) > 1;
    IF offending IS NOT NULL THEN
        RAISE EXCEPTION 'workspace_member_single_login: workspace members % are linked to more than one login (V110). Reset the development database, then restart.',
            offending USING ERRCODE = '23505';
    END IF;
END;
$$;

DROP TABLE sharing_grant;
DROP TABLE account_ownership;

ALTER TABLE workspace_member DROP COLUMN is_dependent;

DROP INDEX idx_app_user_workspace_member;
CREATE UNIQUE INDEX uq_app_user_workspace_member ON app_user (workspace_member_id)
WHERE workspace_member_id IS NOT NULL;

-- SECURITY DEFINER with row_security off, like V81's authentication lookups: app_user carries no
-- workspace context, and the check must not depend on the app.current_workspace_id the inserting
-- transaction runs under. Same owner requirement as V81 (database-schema.md, "Authentication
-- lookup with a hardened runtime role").
CREATE FUNCTION public.trg_workspace_member_requires_login() RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET row_security = off
AS $$
BEGIN
    -- A member inserted and deleted again in the same transaction is gone by commit: nothing to
    -- check.
    IF EXISTS (SELECT 1 FROM public.workspace_member AS m WHERE m.id = NEW.id)
        AND NOT EXISTS (SELECT 1 FROM public.app_user AS u WHERE u.workspace_member_id = NEW.id) THEN
        RAISE EXCEPTION 'workspace_member_requires_login: workspace member % has no login (FR-USR-008). Create the member together with its user.',
            NEW.id USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

REVOKE ALL ON FUNCTION public.trg_workspace_member_requires_login() FROM PUBLIC;

CREATE CONSTRAINT TRIGGER workspace_member_requires_login
AFTER INSERT ON workspace_member
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION trg_workspace_member_requires_login();

-- Same SECURITY DEFINER shape as above, from the other side of the link.
CREATE FUNCTION public.trg_app_user_keeps_member_login() RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET row_security = off
AS $$
BEGIN
    -- Unlinking is fine when the member itself is gone by commit.
    IF EXISTS (SELECT 1 FROM public.workspace_member AS m WHERE m.id = OLD.workspace_member_id)
        AND NOT EXISTS (
            SELECT 1 FROM public.app_user AS u WHERE u.workspace_member_id = OLD.workspace_member_id
        ) THEN
        RAISE EXCEPTION 'workspace_member_requires_login: workspace member % would be left without a login (FR-USR-008). Remove the member in the same transaction.',
            OLD.workspace_member_id USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

REVOKE ALL ON FUNCTION public.trg_app_user_keeps_member_login() FROM PUBLIC;

CREATE CONSTRAINT TRIGGER app_user_keeps_member_login
AFTER UPDATE OF workspace_member_id ON app_user
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
WHEN (
    old.workspace_member_id IS NOT NULL
    AND old.workspace_member_id IS DISTINCT FROM new.workspace_member_id
)
EXECUTE FUNCTION trg_app_user_keeps_member_login();

COMMENT ON TABLE workspace_member IS
'US-03-06 (#389): a user''s membership of a workspace, its only access grant; one login each.';
