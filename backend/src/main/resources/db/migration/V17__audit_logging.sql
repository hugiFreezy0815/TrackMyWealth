-- =============================================================================================
-- V17: Audit logging
-- =============================================================================================
-- FR-AUD-*, FR-AUT-013, FR-DAT-005: three distinct audit surfaces, kept separate because they
-- have different retention, visibility and content rules (financial change content must never
-- appear in the auth log, for instance - NFR-OPS-005).
-- =============================================================================================

-- FR-AUD-001/003: every create/modify/void/restore of a financial record. Append-only by
-- convention (application never issues UPDATE/DELETE against this table); enforced with a rule
-- rather than a trigger since it must remain writable by every service that touches financial
-- data.
CREATE TABLE financial_audit_log (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id          UUID NOT NULL REFERENCES workspace(id),
    entity_type               TEXT NOT NULL,
    entity_id                     UUID NOT NULL,
    action                            TEXT NOT NULL CHECK (action IN ('CREATE', 'UPDATE', 'VOID', 'RESTORE', 'DELETE')),
    actor_user_id                        UUID REFERENCES app_user(id),
    before_data                             JSONB,
    after_data                                 JSONB,
    occurred_at                                   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_financial_audit_log_entity ON financial_audit_log(entity_type, entity_id);
CREATE INDEX idx_financial_audit_log_workspace ON financial_audit_log(workspace_id, occurred_at DESC);

-- FR-DAT-005/FR-USR administration actions - who created/disabled/promoted a user, catalogue and
-- reference-data package imports, etc. Deliberately separate from financial_audit_log
-- (FR-TEN-007: administration and financial-data access are different permission domains, and
-- their audit trails should not be joined casually either).
CREATE TABLE admin_audit_log (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id         UUID REFERENCES app_user(id),
    action                    TEXT NOT NULL,
    target_user_id               UUID REFERENCES app_user(id),
    details                          JSONB,
    occurred_at                         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_admin_audit_log_actor ON admin_audit_log(actor_user_id, occurred_at DESC);

-- FR-AUT-013: authentications, token-family invalidations, grant/role changes. Financial data
-- content is never written here.
CREATE TABLE auth_audit_log (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_user_id     UUID REFERENCES app_user(id),
    event_type                TEXT NOT NULL CHECK (event_type IN (
                                'LOGIN_SUCCESS', 'LOGIN_FAILURE', 'LOGOUT', 'TOKEN_REFRESH',
                                'TOKEN_FAMILY_INVALIDATED', 'PASSWORD_CHANGED', 'MFA_ENABLED',
                                'MFA_DISABLED', 'GRANT_CHANGED', 'ROLE_CHANGED', 'ACCOUNT_LOCKED')),
    source_ip_hash                TEXT,
    occurred_at                       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_auth_audit_log_principal ON auth_audit_log(principal_user_id, occurred_at DESC);

COMMENT ON TABLE financial_audit_log IS 'FR-AUD-003: append-only. Removed only by tenant erasure (FR-LIF-020), never by application-level UPDATE/DELETE.';
