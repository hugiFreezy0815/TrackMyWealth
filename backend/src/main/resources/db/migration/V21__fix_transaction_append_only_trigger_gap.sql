-- =============================================================================================
-- V21: Fix transaction append-only trigger gap
-- =============================================================================================
-- trg_transaction_append_only (V10__transaction_ledger.sql) was missing fee_amount,
-- fx_rate_to_account_currency and fx_rate_date from its comparison, so those three columns could
-- be updated in place despite RULE-024/FR-TRX-007's append-only guarantee - the exact gap the
-- trigger exists to close for every other financial field (amount, quantity, unit_price, ...).
-- fee_amount is recorded alongside amount/quantity/unit_price for the same transaction (see
-- US-07-01) and directly affects cost-basis/tax-lot calculations; fx_rate_to_account_currency and
-- fx_rate_date are the DM-06 record of which rate produced any converted figure derived from this
-- row. Neither category belongs with the genuinely-mutable columns (category_id, notes,
-- merchant_description, void metadata, ...) that this trigger intentionally leaves untouched.
--
-- V10 itself is not edited - it has already been applied wherever this schema has been deployed,
-- and this project's own convention (see the DoD note on US-01-02) is that an applied migration is
-- never edited, only superseded by a forward migration. CREATE OR REPLACE is sufficient here since
-- the existing `transaction_append_only` trigger (V10) already points at this function by name -
-- no need to drop/recreate the trigger itself.
-- =============================================================================================

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
