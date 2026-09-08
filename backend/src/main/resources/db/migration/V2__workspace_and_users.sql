-- =============================================================================================
-- V2: Workspaces, system users and workspace members
-- =============================================================================================
-- RULE-018 / FR-USR-*, FR-HOU-*: a System User (can authenticate) and a Workspace Member
-- (represented in the financial model) are deliberately separate concepts. A workspace member
-- may exist with no login (a child, a dependent); a system user may exist with no financial
-- representation (a pure administrator). Ownership of financial data always references the
-- member, never the user (FR-USR-011).
-- =============================================================================================

CREATE TABLE workspace (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL,
    -- FR-HHL-013: structurally many-to-many between member and workspace (see workspace_member
    -- below) even though the application enforces "one workspace per person" in the MVP.
    status          TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         INTEGER NOT NULL DEFAULT 0
);
CREATE TRIGGER workspace_set_updated_at BEFORE UPDATE ON workspace FOR EACH ROW EXECUTE FUNCTION trg_set_updated_at();
CREATE TRIGGER workspace_bump_version BEFORE UPDATE ON workspace FOR EACH ROW EXECUTE FUNCTION trg_bump_version();

-- FR-HOU-001, RULE-018: a person represented financially. May or may not have a login.
CREATE TABLE workspace_member (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspace(id),
    display_name    TEXT NOT NULL,
    is_dependent    BOOLEAN NOT NULL DEFAULT FALSE,
    -- FR-STA-007: ACTIVE -> INACTIVE reversible; DELETED only reachable from a member who has
    -- never owned anything (FR-LIF-001).
    status          TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE', 'DELETED')),
    -- FR-HHL-004: membership history retained via effective dates rather than overwritten.
    member_since    DATE NOT NULL DEFAULT CURRENT_DATE,
    member_until    DATE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         INTEGER NOT NULL DEFAULT 0,
    CHECK (member_until IS NULL OR member_until >= member_since)
);
CREATE INDEX idx_workspace_member_workspace ON workspace_member(workspace_id);
CREATE TRIGGER workspace_member_set_updated_at BEFORE UPDATE ON workspace_member FOR EACH ROW EXECUTE FUNCTION trg_set_updated_at();
CREATE TRIGGER workspace_member_bump_version BEFORE UPDATE ON workspace_member FOR EACH ROW EXECUTE FUNCTION trg_bump_version();

-- FR-USR-*: a person who can authenticate. FR-USR-010: system administration rights and
-- financial-data access are distinct permission concerns - `role` below governs administration
-- only; financial access is governed entirely by workspace_member + account_ownership /
-- sharing grants (see V6 and FR-TEN-008), never by this table.
CREATE TABLE app_user (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email                   CITEXT NOT NULL UNIQUE,
    password_hash           TEXT NOT NULL, -- Argon2id / bcrypt, never plaintext (FR-AUT-007)
    -- FR-USR-004: initial roles are SYSTEM_ADMINISTRATOR and STANDARD_USER; open for extension.
    role                    TEXT NOT NULL DEFAULT 'STANDARD_USER' CHECK (role IN ('SYSTEM_ADMINISTRATOR', 'STANDARD_USER')),
    status                  TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    -- FR-USR-006 / NFR-I18N-001
    language                TEXT NOT NULL DEFAULT 'EN' CHECK (language IN ('EN', 'DE')),
    -- FR-USR-007: reporting currency used by this user's consolidated views.
    reporting_currency      CHAR(3) NOT NULL DEFAULT 'CHF',
    -- FR-USR-008: optional link to the financial-model representation of this person.
    workspace_member_id    UUID REFERENCES workspace_member(id),
    mfa_totp_secret         TEXT,           -- FR-AUT-008, encrypted at the application layer
    mfa_enabled             BOOLEAN NOT NULL DEFAULT FALSE,
    failed_login_count      INTEGER NOT NULL DEFAULT 0,
    locked_until            TIMESTAMPTZ,    -- FR-AUT-010 rate limiting / lockout
    last_login_at           TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                 INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_app_user_workspace_member ON app_user(workspace_member_id);
CREATE TRIGGER app_user_set_updated_at BEFORE UPDATE ON app_user FOR EACH ROW EXECUTE FUNCTION trg_set_updated_at();
CREATE TRIGGER app_user_bump_version BEFORE UPDATE ON app_user FOR EACH ROW EXECUTE FUNCTION trg_bump_version();

-- FR-USR-005 / FR-HHL-015: "last active administrator" / "last active workspace member"
-- protection is enforced in the service layer (it requires counting siblings, which a row-level
-- CHECK/trigger cannot do safely under concurrent writes) - see EPIC 02 user stories. Recorded
-- here so the rule is not lost between the requirements document and the code.
COMMENT ON TABLE app_user IS
    'FR-USR-005: the last active SYSTEM_ADMINISTRATOR must not be disabled/deleted/demoted without another active administrator. Enforced in the service layer, covered by an integration test, not by a DB constraint.';
COMMENT ON TABLE workspace_member IS
    'FR-HHL-015: a workspace must always retain at least one active member with full access. Enforced in the service layer.';
