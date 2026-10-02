-- =============================================================================================
-- V57: A dedicated currency-in-use trigger function for transaction (#223 review)
-- =============================================================================================
-- V54's trg_fx_rate_currency_in_use reads the currency column named in its argument through
-- to_jsonb(NEW), which serialises the whole row - raw_source_data included - on every insert.
-- That is fine for account, financial_institution, app_user and listing, written rarely, but
-- transaction takes every ledger insert and every import row. Its trigger now reads NEW.currency
-- directly; the other four keep the generic function.
-- =============================================================================================

CREATE OR REPLACE FUNCTION trg_transaction_fx_rate_currency_in_use() RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM fx_rate_currency_in_use WHERE currency = NEW.currency) THEN
        INSERT INTO fx_rate_currency_in_use (currency) VALUES (NEW.currency)
        ON CONFLICT (currency) DO NOTHING;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER transaction_fx_rate_currency_in_use ON transaction;

CREATE TRIGGER transaction_fx_rate_currency_in_use
AFTER INSERT ON transaction
FOR EACH ROW EXECUTE FUNCTION trg_transaction_fx_rate_currency_in_use();
