-- =============================================================================================
-- V16: Authentication, refresh tokens and sessions
-- =============================================================================================
-- Section 49 / FR-AUT-*: short-lived JWT access tokens are never stored (they are self-contained
-- and expire quickly by design); refresh tokens ARE stored, server-side, opaque and rotated, so
-- that logout / revocation / theft-detection are real rather than "wait for the JWT to expire"
-- (FR-AUT-003/004/005).
-- =============================================================================================

CREATE TABLE refresh_token (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID NOT NULL REFERENCES app_user(id),
    -- FR-AUT-004: rotation family - reuse of any token in an already-rotated family invalidates
    -- the whole family and is treated as a suspected theft event.
    family_id                 UUID NOT NULL,
    token_hash                    TEXT NOT NULL UNIQUE, -- the token itself is never stored, only its hash
    device_label                     TEXT,               -- FR-AUT-002: user-visible session listing
    issued_at                           TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at                             TIMESTAMPTZ NOT NULL,
    revoked_at                                TIMESTAMPTZ,
    replaced_by_token_id                         UUID REFERENCES refresh_token(id),
    theft_suspected                                 BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_refresh_token_user ON refresh_token(user_id);
CREATE INDEX idx_refresh_token_family ON refresh_token(family_id);

COMMENT ON TABLE refresh_token IS
    'FR-AUT-005: revocation must take effect within the access-token lifetime at worst, immediately for security-significant events. The access-token validator checks a per-user token_version/session marker on every request rather than relying on JWT statelessness.';

-- FR-AUT-005: a monotonic per-user counter. Incrementing it invalidates every previously issued
-- access token immediately, independent of that token's own expiry - the mechanism referenced in
-- the comment above.
ALTER TABLE app_user ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;

CREATE TABLE user_session (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID NOT NULL REFERENCES app_user(id),
    refresh_token_id          UUID REFERENCES refresh_token(id),
    -- FR-STA-006
    status                       TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'EXPIRED', 'REVOKED')),
    device_label                    TEXT,
    ip_address_hash                    TEXT, -- NFR-OPS-005: no raw client identifiers in observability data
    created_at                            TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at                             TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at                                  TIMESTAMPTZ
);
CREATE INDEX idx_user_session_user ON user_session(user_id, status);

-- FR-TEN-005/006: externally visible identifiers must be non-sequential and non-guessable, and
-- an unauthorized-vs-nonexistent request must be indistinguishable. Every primary key in this
-- schema is already a UUIDv4 (V1 convention) which satisfies non-enumerability; this table exists
-- so object-level authorization failures are logged uniformly for the FR-TEN-010 cross-tenant
-- test suite to assert against.
CREATE TABLE authorization_denial_log (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_user_id    UUID REFERENCES app_user(id),
    requested_entity_type TEXT NOT NULL,
    requested_entity_id      UUID,
    reason                      TEXT NOT NULL CHECK (reason IN ('NOT_FOUND', 'NOT_AUTHORIZED')),
    occurred_at                    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_authorization_denial_log_principal ON authorization_denial_log(principal_user_id);
