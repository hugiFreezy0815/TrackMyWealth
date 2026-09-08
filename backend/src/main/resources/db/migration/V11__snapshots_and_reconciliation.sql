-- =============================================================================================
-- V11: Snapshots and the reconciliation engine
-- =============================================================================================
-- Section 12.1 - "the single most important addition to the baseline" per the requirements doc.
-- FR-REC-001/RULE-025: a Snapshot is a provider-reported *observation of state*; a Transaction is
-- an *event*. Neither is derivable from the other, and both are needed: transaction history is
-- never complete, so ledger-derived balances/holdings must be checked against what the
-- institution actually reports.
-- =============================================================================================

CREATE TABLE account_snapshot (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id         UUID NOT NULL REFERENCES workspace(id),
    account_id              UUID NOT NULL REFERENCES account(id),
    snapshot_date               DATE NOT NULL,
    balance                        NUMERIC(20,4),
    currency                          CHAR(3) NOT NULL,
    -- FR-REC-006: manual entry is fully supported - this is what gives a purely manual user
    -- correctness before any connector exists.
    source                                TEXT NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'AGGREGATOR', 'API', 'DOCUMENT')),
    is_opening_balance                      BOOLEAN NOT NULL DEFAULT FALSE, -- FR-REC-007
    created_at                                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                                    UUID REFERENCES app_user(id),
    UNIQUE (account_id, snapshot_date, source)
);
CREATE INDEX idx_account_snapshot_account_date ON account_snapshot(account_id, snapshot_date DESC);

-- Holdings reported as part of a snapshot (for position-bearing accounts).
CREATE TABLE snapshot_holding (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id          UUID NOT NULL REFERENCES account_snapshot(id),
    security_id             UUID NOT NULL REFERENCES security(id),
    quantity                   NUMERIC(28,10) NOT NULL,
    reported_cost_basis           NUMERIC(20,4), -- FR-REC-008: transferred-in positions often arrive with none
    cost_basis_is_estimated          BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_snapshot_holding_snapshot ON snapshot_holding(snapshot_id);

CREATE TABLE reconciliation_result (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id         UUID NOT NULL REFERENCES workspace(id),
    account_id              UUID NOT NULL REFERENCES account(id),
    snapshot_id                 UUID NOT NULL REFERENCES account_snapshot(id),
    affected_security_id           UUID REFERENCES security(id),
    difference_amount                 NUMERIC(20,4),
    difference_quantity                  NUMERIC(28,10),
    -- FR-REC-003: probable-cause classification.
    probable_cause                          TEXT CHECK (probable_cause IN (
                                               'MISSING_TRANSACTION', 'MISSING_CORPORATE_ACTION',
                                               'UNRECORDED_FEE', 'FX_ROUNDING',
                                               'TRANSFER_WITHOUT_COST_BASIS', 'DUPLICATE_ENTRY',
                                               'UNKNOWN')),
    -- FR-STA-003
    status                                      TEXT NOT NULL DEFAULT 'OPEN'
                                                   CHECK (status IN ('OPEN', 'RESOLVED', 'ACCEPTED', 'DISMISSED', 'SUPERSEDED')),
    resolution_note                                TEXT,
    -- FR-REC-004: an ACCEPTED resolution creates a visible adjusting transaction, never a silent
    -- correction - this points at that transaction.
    resolution_transaction_id                          UUID REFERENCES transaction(id),
    resolved_at                                            TIMESTAMPTZ,
    resolved_by                                               UUID REFERENCES app_user(id),
    created_at                                                   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reconciliation_result_account ON reconciliation_result(account_id, status);
CREATE INDEX idx_reconciliation_result_workspace ON reconciliation_result(workspace_id);

COMMENT ON TABLE reconciliation_result IS
    'FR-REC-005/FR-CON-007: an account''s reconciliation status (has an OPEN row here?) drives the visible data-quality flag shown at the account and at the consolidated headline figure (PR-011).';

-- FR-DEP-006: acquisition lots, so realised gain is inspectable/reproducible under the
-- configured cost-basis method. Derived from the transaction ledger; fully rebuildable
-- (FR-DAT-008) - never a source of truth in its own right.
CREATE TABLE tax_lot (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id               UUID NOT NULL REFERENCES account(id),
    security_id                 UUID NOT NULL REFERENCES security(id),
    acquisition_transaction_id     UUID REFERENCES transaction(id),
    acquisition_date                  DATE NOT NULL,
    original_quantity                    NUMERIC(28,10) NOT NULL,
    remaining_quantity                      NUMERIC(28,10) NOT NULL,
    unit_cost                                  NUMERIC(20,10) NOT NULL,
    currency                                      CHAR(3) NOT NULL,
    cost_basis_method                                TEXT NOT NULL CHECK (cost_basis_method IN ('FIFO', 'LIFO', 'AVERAGE_COST')),
    is_cost_basis_estimated                             BOOLEAN NOT NULL DEFAULT FALSE, -- FR-REC-008/FR-POR-06
    computed_at                                            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tax_lot_account_security ON tax_lot(account_id, security_id);

COMMENT ON TABLE tax_lot IS
    'DM-04/FR-DAT-008: derived, never stored as source of truth. Recomputed whenever the cost-basis method changes or an upstream activity in the same account+security changes (NFR-CALC-008 idempotent recomputation).';

-- Realised-gain matches (which lot(s) a SELL consumed), kept explicit so the calculation is
-- auditable rather than re-derived implicitly every time it is displayed.
CREATE TABLE tax_lot_disposal (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tax_lot_id                UUID NOT NULL REFERENCES tax_lot(id),
    disposal_transaction_id      UUID NOT NULL REFERENCES transaction(id),
    quantity_disposed                NUMERIC(28,10) NOT NULL,
    proceeds_amount                     NUMERIC(20,4) NOT NULL,
    realised_gain_amount                   NUMERIC(20,4) NOT NULL,
    currency                                  CHAR(3) NOT NULL
);
CREATE INDEX idx_tax_lot_disposal_lot ON tax_lot_disposal(tax_lot_id);
CREATE INDEX idx_tax_lot_disposal_transaction ON tax_lot_disposal(disposal_transaction_id);

-- RULE-030/FR-PERF-010: the daily valuation series is an architectural prerequisite for TWR, not
-- a reporting feature, and must exist from the MVP - retrofitting it means a full historical
-- recomputation per workspace. Partitioned like price/fx_rate (NFR-TEC-003).
-- Composite (account_id, valuation_date) is the primary key (partitioning requires the partition
-- column to be part of it). `id` is a convenience surrogate column only - PostgreSQL also
-- requires a UNIQUE constraint on a partitioned table to include the partition column, so `id`
-- cannot be independently unique-constrained here either; callers address rows by
-- (account_id, valuation_date), not by id.
CREATE TABLE daily_valuation (
    id                    UUID NOT NULL DEFAULT gen_random_uuid(),
    account_id                UUID NOT NULL,
    valuation_date               DATE NOT NULL,
    value                            NUMERIC(20,4) NOT NULL,
    currency                            CHAR(3) NOT NULL,
    -- external cash flow into/out of this account on this date - the sub-period boundary input
    -- to TWR construction (FR-PERF-011).
    external_cashflow_amount               NUMERIC(20,4) NOT NULL DEFAULT 0,
    valuation_method                          TEXT NOT NULL DEFAULT 'EXACT' CHECK (valuation_method IN ('EXACT', 'MODIFIED_DIETZ', 'CARRIED_FORWARD')),
    is_estimated                                 BOOLEAN NOT NULL DEFAULT FALSE, -- PR-011
    computed_at                                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, valuation_date)
) PARTITION BY RANGE (valuation_date);

CREATE TABLE daily_valuation_y2023 PARTITION OF daily_valuation FOR VALUES FROM ('2023-01-01') TO ('2024-01-01');
CREATE TABLE daily_valuation_y2024 PARTITION OF daily_valuation FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE daily_valuation_y2025 PARTITION OF daily_valuation FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE daily_valuation_y2026 PARTITION OF daily_valuation FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE daily_valuation_y2027 PARTITION OF daily_valuation FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE daily_valuation_default PARTITION OF daily_valuation DEFAULT;

ALTER TABLE daily_valuation ADD CONSTRAINT fk_daily_valuation_account FOREIGN KEY (account_id) REFERENCES account(id);
CREATE INDEX idx_daily_valuation_account_date ON daily_valuation(account_id, valuation_date DESC);
