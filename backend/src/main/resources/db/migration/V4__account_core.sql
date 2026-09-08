-- =============================================================================================
-- V4: Account - class-table inheritance parent
-- =============================================================================================
-- Section 10 / DM-17..DM-21, DB-09..DB-13: `account` is the abstract parent of a disjoint, total,
-- one-level-deep type hierarchy (G1-G6, FR-ACC-005). Variation *within* a type is expressed
-- through the declared capability flags (FR-ACC-010/011), never through further sub-subtypes.
-- Per-subtype attributes that need real constraints (statement day, amortisation schedule,
-- contribution limit rule, ...) live in extension tables sharing this table's primary key
-- (V5__account_extensions.sql) - never in JSONB, and never via PostgreSQL table INHERITS
-- (DB-13: foreign keys and unique constraints are not inherited by INHERITS child tables).
-- =============================================================================================

CREATE TABLE account (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id            UUID NOT NULL REFERENCES workspace(id),
    financial_institution_id UUID NOT NULL REFERENCES financial_institution(id), -- C2: exactly one container
    account_type            TEXT NOT NULL CHECK (account_type IN (
                                'CASH', 'SAVINGS', 'SECURITIES', 'MANAGED_MANDATE', 'PENSION',
                                'VESTED_BENEFITS', 'CREDIT_CARD', 'MORTGAGE', 'LOAN', 'CRYPTO',
                                'CUSTOM_ASSET')),
    name                    TEXT NOT NULL,
    -- FR-ACC-002: the account's own currency, independent of container and user currencies.
    native_currency         CHAR(3) NOT NULL,
    -- FR-ACC-013 / DB-12: nature is derived from account_type, never independently writable, so
    -- application code cannot add a liability where it should subtract one.
    nature                  TEXT GENERATED ALWAYS AS (
                                CASE account_type
                                    WHEN 'CREDIT_CARD' THEN 'LIABILITY'
                                    WHEN 'MORTGAGE'     THEN 'LIABILITY'
                                    WHEN 'LOAN'          THEN 'LIABILITY'
                                    ELSE 'ASSET'
                                END
                             ) STORED,

    -- --- Capability model (FR-ACC-010/011/012) -------------------------------------------
    -- Declared, queryable flags. The consolidation layer, import router and UI branch on these,
    -- never on account_type (FR-ACC-012, FR-NAV-17/FR-INS-012 extensibility verification).
    holds_positions          BOOLEAN NOT NULL DEFAULT FALSE,
    has_transactions          BOOLEAN NOT NULL DEFAULT TRUE,
    has_statement_cycle       BOOLEAN NOT NULL DEFAULT FALSE,
    has_amortisation          BOOLEAN NOT NULL DEFAULT FALSE,
    has_contribution_limit    BOOLEAN NOT NULL DEFAULT FALSE,
    is_discretionary          BOOLEAN NOT NULL DEFAULT FALSE, -- FR-ACC-020..022
    manual_valuation          BOOLEAN NOT NULL DEFAULT FALSE,

    identifier_masked         TEXT,          -- masked account number, for display only
    jurisdiction               CHAR(2),
    opened_at                  DATE,
    closed_at                  DATE,

    -- FR-STA-001
    status                     TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED', 'DELETED')),

    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                    INTEGER NOT NULL DEFAULT 0,

    CHECK (closed_at IS NULL OR opened_at IS NULL OR closed_at >= opened_at)
);

CREATE INDEX idx_account_workspace ON account(workspace_id);
CREATE INDEX idx_account_institution ON account(financial_institution_id);
CREATE INDEX idx_account_type ON account(account_type);

CREATE TRIGGER account_set_updated_at BEFORE UPDATE ON account FOR EACH ROW EXECUTE FUNCTION trg_set_updated_at();
CREATE TRIGGER account_bump_version BEFORE UPDATE ON account FOR EACH ROW EXECUTE FUNCTION trg_bump_version();

-- FR-ACC-005 / DB-11 / G5: account_type is immutable after creation. A depot does not become a
-- mortgage; conversion, if ever required, is close-and-recreate with an explicit migration.
CREATE OR REPLACE FUNCTION trg_account_type_immutable()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.account_type IS DISTINCT FROM OLD.account_type THEN
        RAISE EXCEPTION 'account_type_immutable: account % account_type cannot change from % to % (FR-ACC-005/G5)',
            OLD.id, OLD.account_type, NEW.account_type USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_type_immutable BEFORE UPDATE ON account FOR EACH ROW EXECUTE FUNCTION trg_account_type_immutable();

COMMENT ON TABLE account IS
    'DM-17: every account type exposes the same interface (signed value, currency, nature, parent container) to the rest of the system. Consolidation, net worth, allocation and navigation must consume only this interface and never branch on account_type in application code - use the capability flags instead.';
