-- =============================================================================================
-- V91: Transaction correction lineage (US-07-06, FR-LIF-004)
-- =============================================================================================
-- A correction never mutates a committed financial field. The old row is soft-deleted (manual,
-- T1) or voided/reversed (imported, T2), and the effective replacement points to the row it
-- corrects. This link is intentionally distinct from replaces_transaction_id, which means
-- "reversing entry of a void" and is used by balance/figure logic.
-- =============================================================================================

ALTER TABLE transaction
ADD COLUMN corrects_transaction_id UUID REFERENCES transaction (id);

-- One effective replacement per old row. Further corrections form a chain:
-- original <- correction-1 <- correction-2.
CREATE UNIQUE INDEX uq_transaction_correction
ON transaction (corrects_transaction_id)
WHERE corrects_transaction_id IS NOT NULL;

-- Keep the latest append-only protection from V31 and freeze correction/void lineage as well.
-- Both lineage ids are set when a row is inserted; changing either later would rewrite history.
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
    THEN
        RAISE EXCEPTION 'transaction_ledger_append_only: financial or lineage fields of transaction % cannot be updated in place (RULE-024/FR-TRX-007). Void and insert a replacement instead (FR-LIF-002/004).',
            OLD.id USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
