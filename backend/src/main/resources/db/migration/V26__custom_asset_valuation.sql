-- =============================================================================================
-- V26: custom_asset_valuation guards (US-05-05)
-- =============================================================================================
-- FR-NW-003: dated manual valuations for custom assets. custom_asset_valuation itself already
-- exists (V5) with a UNIQUE(account_id, valuation_date) constraint - each dated valuation is its
-- own immutable historical entry (same spirit as this codebase's other immutability guards); a
-- second insert for a date that already has one is rejected by that constraint alone, so this
-- migration only adds the two guards V5 didn't yet have reason to.
--
-- trg_extension_type_guard (V5) is a generic "does this row's account_id have the expected
-- account_type" check, already used for every 1:1 extension table - it works identically for
-- custom_asset_valuation despite that being a 1:many child table, since it only ever looks at
-- NEW.account_id and TG_ARGV[0]. Reused as-is rather than duplicating its logic.
--
-- No existing trigger compares a child row's own column against a value on its parent account
-- row, so this adds one: a valuation's currency must match its account's native_currency. Storing
-- a mismatched currency per valuation would leave "what currency is this account's value in"
-- ambiguous - a question this codebase's design otherwise never leaves open (native_currency
-- itself is immutable, V24) - and would push an FX conversion into every reader of this table
-- rather than resolving it once, here, at the point the value is recorded.
-- =============================================================================================

CREATE TRIGGER custom_asset_valuation_type_guard BEFORE INSERT ON custom_asset_valuation
FOR EACH ROW EXECUTE FUNCTION trg_extension_type_guard('CUSTOM_ASSET');

CREATE OR REPLACE FUNCTION trg_custom_asset_valuation_currency_guard()
RETURNS TRIGGER AS $$
DECLARE
    account_currency TEXT;
BEGIN
    SELECT native_currency INTO account_currency FROM account WHERE id = NEW.account_id;
    IF NEW.currency IS DISTINCT FROM account_currency THEN
        RAISE EXCEPTION 'custom_asset_valuation_currency_mismatch: account % native_currency is % but a valuation in % was attempted (FR-ACC-002)',
            NEW.account_id, account_currency, NEW.currency USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER custom_asset_valuation_currency_guard BEFORE INSERT ON custom_asset_valuation
FOR EACH ROW EXECUTE FUNCTION trg_custom_asset_valuation_currency_guard();
