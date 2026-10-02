-- =============================================================================================
-- V50: Restore a voided transaction (US-07-07, FR-LIF-006)
-- =============================================================================================
-- A void stays immutable history: the original keeps voided_at/voided_by/void_reason and its
-- reversing row stays in the ledger. Restoring the void inserts an ordinary copy of the original
-- that points back at it through restores_transaction_id, so the ledger reads A, -A, A' and the
-- original's effect is back without updating a committed row (RULE-024).
--
-- The copy is a normal row in every respect - it counts in figures, can be categorized, matched,
-- corrected and removed again - so no query needs a "voided but restored" special case, and a
-- void/restore cycle can repeat: voiding A' later reverses A' and a restore copies it again.
-- =============================================================================================

ALTER TABLE transaction
ADD COLUMN restores_transaction_id UUID REFERENCES transaction (id),
-- A row has at most one lineage: a reversal, a correction replacement or a restore copy.
ADD CONSTRAINT transaction_restore_one_kind CHECK (
    num_nonnulls(replaces_transaction_id, corrects_transaction_id, restores_transaction_id) <= 1
);

-- At most one restore per voided row: a retried or concurrent restore can never copy it twice.
CREATE UNIQUE INDEX uq_transaction_restore
ON transaction (restores_transaction_id)
WHERE restores_transaction_id IS NOT NULL;

-- V49's append-only protection, now also freezing the restore lineage once written.
CREATE OR REPLACE FUNCTION trg_transaction_append_only()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.amount IS DISTINCT FROM OLD.amount
        OR NEW.currency IS DISTINCT FROM OLD.currency
        OR NEW.quantity IS DISTINCT FROM OLD.quantity
        OR NEW.unit_price IS DISTINCT FROM OLD.unit_price
        OR NEW.fee_amount IS DISTINCT FROM OLD.fee_amount
        OR NEW.fx_rate_to_account_currency IS DISTINCT FROM OLD.fx_rate_to_account_currency
        OR NEW.fx_rate_date IS DISTINCT FROM OLD.fx_rate_date
        OR NEW.fx_rate_estimated IS DISTINCT FROM OLD.fx_rate_estimated
        OR NEW.related_transaction_id IS DISTINCT FROM OLD.related_transaction_id
        OR NEW.booking_date IS DISTINCT FROM OLD.booking_date
        OR NEW.value_date IS DISTINCT FROM OLD.value_date
        OR NEW.trade_date IS DISTINCT FROM OLD.trade_date
        OR NEW.settlement_date IS DISTINCT FROM OLD.settlement_date
        OR NEW.account_id IS DISTINCT FROM OLD.account_id
        OR NEW.security_id IS DISTINCT FROM OLD.security_id
        OR NEW.transaction_type IS DISTINCT FROM OLD.transaction_type
        OR NEW.gross_amount IS DISTINCT FROM OLD.gross_amount
        OR NEW.net_amount IS DISTINCT FROM OLD.net_amount
        OR NEW.tax_withheld_amount IS DISTINCT FROM OLD.tax_withheld_amount
        OR NEW.replaces_transaction_id IS DISTINCT FROM OLD.replaces_transaction_id
        OR NEW.corrects_transaction_id IS DISTINCT FROM OLD.corrects_transaction_id
        OR NEW.restores_transaction_id IS DISTINCT FROM OLD.restores_transaction_id
    THEN
        RAISE EXCEPTION 'transaction_ledger_append_only: financial or lineage fields of transaction % cannot be updated in place (RULE-024/FR-TRX-007). Void and insert a replacement instead (FR-LIF-002/004).',
            OLD.id USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
