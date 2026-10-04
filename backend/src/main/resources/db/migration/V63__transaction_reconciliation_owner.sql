-- US-25-03: the reconciliation result that booked a row as its adjusting entry. Ownership is this
-- link, not the transaction type: VALUATION_ADJUSTMENT is a general ledger type that investment
-- valuation and imports will write too (V36), and those rows must stay ordinary rows. The link is
-- written on insert and never changes, so a withdrawn (soft-deleted) adjustment still names its
-- owner, while reconciliation_result.resolution_transaction_id points only at the live one.
--
-- The owner must be a result of the row's own account, and therefore of its own workspace: a plain
-- FK on the id alone would accept another tenant's result, since FK checks bypass RLS (see V34).
-- The composite FK enforces that declaratively; it needs the (account_id, id) key it references.
ALTER TABLE reconciliation_result
ADD CONSTRAINT reconciliation_result_account_id_id_key UNIQUE (account_id, id);

ALTER TABLE transaction
ADD COLUMN reconciliation_result_id UUID,
ADD CONSTRAINT transaction_reconciliation_result_same_account
FOREIGN KEY (account_id, reconciliation_result_id)
REFERENCES reconciliation_result (account_id, id),
ADD CONSTRAINT transaction_reconciliation_adjustment_shape CHECK (
    reconciliation_result_id IS NULL
    OR (transaction_type = 'VALUATION_ADJUSTMENT' AND source = 'MANUAL')
);

CREATE INDEX idx_transaction_reconciliation_result
ON transaction (reconciliation_result_id)
WHERE reconciliation_result_id IS NOT NULL;

-- V50's append-only protection, now also freezing the reconciliation owner once written.
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
        OR NEW.reconciliation_result_id IS DISTINCT FROM OLD.reconciliation_result_id
    THEN
        RAISE EXCEPTION 'transaction_ledger_append_only: financial or lineage fields of transaction % cannot be updated in place (RULE-024/FR-TRX-007). Void and insert a replacement instead (FR-LIF-002/004).',
            OLD.id USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
