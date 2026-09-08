-- =============================================================================================
-- V12: Positions (derived holdings)
-- =============================================================================================
-- FR-DEP-001/002/DM-24/RULE-027: current holding per (account, security), fully derived from the
-- transaction ledger + snapshots (FR-DAT-008) and rebuilt by the position-rebuild job whenever an
-- upstream transaction changes (FR-JOB-006). Stored rather than computed on every read because
-- allocation, GICS and concentration views (section 24) query it constantly and it is cheap to
-- keep current incrementally. It is never written to directly from an API request.
-- =============================================================================================

CREATE TABLE position (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id         UUID NOT NULL REFERENCES workspace(id),
    account_id               UUID NOT NULL REFERENCES account(id),
    security_id                  UUID NOT NULL REFERENCES security(id),
    quantity                        NUMERIC(28,10) NOT NULL,
    average_cost_basis                 NUMERIC(20,10),
    cost_basis_currency                   CHAR(3),
    as_of_date                               DATE NOT NULL,
    is_estimated                                BOOLEAN NOT NULL DEFAULT FALSE, -- PR-011: e.g. missing cost basis on transfer-in
    computed_at                                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_id, security_id)
);
CREATE INDEX idx_position_workspace ON position(workspace_id);
CREATE INDEX idx_position_security ON position(security_id);

COMMENT ON TABLE position IS
    'FR-DEP-003: the same security held in multiple depots consolidates into one exposure at query time (SUM over position rows joined by security_id) while each row here still represents one account''s own position.';

-- FR-DEP-007: a position reaching zero and later re-established must not lose earlier history.
-- position_history retains a full time series (one row per rebuild-relevant date), while
-- `position` above always holds only the current snapshot for fast reads.
CREATE TABLE position_history (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id            UUID NOT NULL REFERENCES account(id),
    security_id               UUID NOT NULL REFERENCES security(id),
    as_of_date                    DATE NOT NULL,
    quantity                         NUMERIC(28,10) NOT NULL,
    average_cost_basis                  NUMERIC(20,10),
    cost_basis_currency                    CHAR(3),
    computed_at                               TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_id, security_id, as_of_date)
);
CREATE INDEX idx_position_history_account_security ON position_history(account_id, security_id, as_of_date DESC);
