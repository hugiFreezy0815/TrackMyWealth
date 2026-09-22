-- =============================================================================================
-- V31: FX-rate-estimated flag and a generic transaction link (US-09-04)
-- =============================================================================================
-- FR-CC-010/PR-011: when the source data discloses neither the issuer's applied rate nor the
-- billed amount to derive it from, the service falls back to a general daily FX rate
-- (FxRateService) rather than leaving fx_rate_to_account_currency null with no indication why.
-- fx_rate_estimated distinguishes that fallback from an exact, disclosed-or-derived rate - the
-- same distinction ValueBasisValues#isApproximate already draws for account valuations.
--
-- related_transaction_id links a distinct FEE row (a disclosed foreign-transaction fee,
-- FR-CC-010) back to the purchase it was charged on. Generic by name since a future story may
-- find another use for "this row relates to that one", but its only writer today is
-- TransactionService's foreign-currency card purchase path.
-- =============================================================================================

ALTER TABLE transaction
    ADD COLUMN fx_rate_estimated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN related_transaction_id UUID REFERENCES transaction (id);

-- Both are financial-provenance fields set once at insert, frozen the same way
-- fx_rate_to_account_currency/fx_rate_date already are (V21) - the append-only guarantee
-- (RULE-024) would otherwise have a gap for exactly these two columns. V10 itself is not edited
-- (see V21's own note on why); CREATE OR REPLACE is sufficient since the existing
-- transaction_append_only trigger already points at this function by name.
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
    THEN
        RAISE EXCEPTION 'transaction_ledger_append_only: financial fields of transaction % cannot be updated in place (RULE-024/FR-TRX-007). Void and insert a replacement instead (FR-LIF-002/004).',
            OLD.id USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
