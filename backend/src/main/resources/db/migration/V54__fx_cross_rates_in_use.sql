-- =============================================================================================
-- V54: FX cross rates as master data, and transfer matching that waits for its rate (#223)
-- =============================================================================================
-- The ECB publishes EUR/<currency> only. Product owner, 2026-10-02: every rate a calculation uses
-- is master data, stored rather than worked out on each read. So the FX import stores, besides
-- the published rows, every pair between the currencies actually in use (CHF/USD, USD/CHF,
-- CHF/EUR, ...), derived from that day's published rates and flagged derived = TRUE.
--
-- fx_rate_currency_in_use lists those currencies. Triggers on every table that names a currency
-- a figure is held or shown in keep it, because account, transaction and the rest are behind
-- FORCE ROW LEVEL SECURITY (V20) and the import job has no workspace context to read them - the
-- same reasoning as V52. A currency is only ever added: a stored rate is never wrong, only unused.
--
-- transfer_detection_fx_pending: a cross-currency transfer pair cannot be judged while no rate
-- covers its booking date. Detection records the workspace and date here, and once the import has
-- stored rates the job re-runs detection there, so such a pair is still proposed (#223, finding 2
-- of the PR #225 review). Holds a workspace id and a date - no amount, account or description -
-- and only the backend reads it; it is outside RLS for the same reason as the table above.
--
-- Role split (V20): these triggers and V52's run as the inserting role, so the runtime role needs
-- INSERT/UPDATE on fx_rate_history_requirement and fx_rate_currency_in_use, or every insert into
-- the tables below fails. V20's planned GRANT ... ON ALL TABLES covers both.
-- =============================================================================================

ALTER TABLE fx_rate
ADD COLUMN derived BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN fx_rate.derived IS
'#223: TRUE for a cross rate the FX import derived from published rates of the same day.';

CREATE TABLE fx_rate_currency_in_use (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    currency CHAR(3) NOT NULL UNIQUE,
    -- FALSE until the import has derived this currency's pairs over the whole stored history.
    cross_rates_derived BOOLEAN NOT NULL DEFAULT FALSE
);

INSERT INTO fx_rate_currency_in_use (currency)
SELECT native_currency FROM account
UNION
SELECT currency FROM transaction
UNION
SELECT container_currency FROM financial_institution
UNION
SELECT reporting_currency FROM app_user
UNION
SELECT trading_currency FROM listing;

-- TG_ARGV[0] names the currency column, so one function serves every table.
CREATE OR REPLACE FUNCTION trg_fx_rate_currency_in_use() RETURNS TRIGGER AS $$
DECLARE
    used CHAR(3) := to_jsonb(NEW) ->> TG_ARGV[0];
BEGIN
    IF used IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM fx_rate_currency_in_use WHERE currency = used) THEN
        INSERT INTO fx_rate_currency_in_use (currency) VALUES (used)
        ON CONFLICT (currency) DO NOTHING;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_fx_rate_currency_in_use
AFTER INSERT OR UPDATE OF native_currency ON account
FOR EACH ROW EXECUTE FUNCTION trg_fx_rate_currency_in_use('native_currency');

CREATE TRIGGER transaction_fx_rate_currency_in_use
AFTER INSERT ON transaction
FOR EACH ROW EXECUTE FUNCTION trg_fx_rate_currency_in_use('currency');

CREATE TRIGGER financial_institution_fx_rate_currency_in_use
AFTER INSERT OR UPDATE OF container_currency ON financial_institution
FOR EACH ROW EXECUTE FUNCTION trg_fx_rate_currency_in_use('container_currency');

CREATE TRIGGER app_user_fx_rate_currency_in_use
AFTER INSERT OR UPDATE OF reporting_currency ON app_user
FOR EACH ROW EXECUTE FUNCTION trg_fx_rate_currency_in_use('reporting_currency');

CREATE TRIGGER listing_fx_rate_currency_in_use
AFTER INSERT OR UPDATE OF trading_currency ON listing
FOR EACH ROW EXECUTE FUNCTION trg_fx_rate_currency_in_use('trading_currency');

COMMENT ON TABLE fx_rate_currency_in_use IS
'#223: currencies in use anywhere - the FX import stores the cross rates between them.';

CREATE TABLE transfer_detection_fx_pending (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    booking_date DATE NOT NULL,
    UNIQUE (workspace_id, booking_date)
);

COMMENT ON TABLE transfer_detection_fx_pending IS
'#223: transfer detection to re-run once FX rates cover the date - backend only.';
