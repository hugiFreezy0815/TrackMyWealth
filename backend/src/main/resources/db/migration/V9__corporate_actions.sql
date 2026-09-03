-- =============================================================================================
-- V9: Corporate actions and successor linkage
-- =============================================================================================
-- FR-PRC-007/009/015, FR-SMD-010, FR-IMD-16: splits, mergers, spin-offs, ISIN changes and
-- delistings are stored as discrete events on the security, separate from the immutable raw
-- price series (V8). Adjusted price series and position-quantity adjustments are computed by
-- application logic that folds these events over the raw series - never by mutating price rows.
-- =============================================================================================

CREATE TABLE corporate_action (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    security_id             UUID NOT NULL REFERENCES security(id),
    action_type               TEXT NOT NULL CHECK (action_type IN (
                                 'SPLIT', 'REVERSE_SPLIT', 'MERGER', 'SPINOFF', 'ISIN_CHANGE',
                                 'SYMBOL_CHANGE', 'DELISTING')),
    effective_date              DATE NOT NULL,
    ratio_numerator                NUMERIC(20,10),   -- e.g. 3-for-1 split => numerator 3, denominator 1
    ratio_denominator               NUMERIC(20,10),
    successor_security_id             UUID REFERENCES security(id), -- FR-SMD-010: continuity across the event
    details                              JSONB NOT NULL DEFAULT '{}',
    -- FR-PRC-015: automatically applied actions are visible and reversible.
    applied_at                             TIMESTAMPTZ,
    is_reversed                              BOOLEAN NOT NULL DEFAULT FALSE,
    reversed_at                               TIMESTAMPTZ,
    reversed_reason                            TEXT,
    source                                       TEXT NOT NULL,
    created_at                                     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_corporate_action_security ON corporate_action(security_id, effective_date);

COMMENT ON TABLE corporate_action IS
    'V-01..V-05 in the golden verification dataset (section 47) exercise this table directly: forward split, reverse split with fractional residue, merger, spin-off with basis apportionment, ISIN change with no economic event.';
