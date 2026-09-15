-- =============================================================================================
-- V27: fix custom_asset_valuation trigger firing order (code review follow-up on US-05-05)
-- =============================================================================================
-- PostgreSQL fires same-timing (BEFORE INSERT) triggers on one table in alphabetical order by
-- trigger name. V26 named its two triggers custom_asset_valuation_currency_guard and
-- custom_asset_valuation_type_guard - 'c' sorts before 't', so the currency check ran first. A
-- row that violates both (e.g. a non-CUSTOM_ASSET account with a mismatched currency) was
-- therefore always reported as a currency problem, masking the more fundamental wrong-account-
-- type problem the type guard exists to catch.
--
-- Renamed (not dropped and recreated with different logic) so the type check - the more
-- fundamental invariant, since a valuation on the wrong account type shouldn't be evaluated for
-- currency correctness at all - sorts and therefore fires first.
-- =============================================================================================

ALTER TRIGGER custom_asset_valuation_type_guard ON custom_asset_valuation
RENAME TO custom_asset_valuation_guard_1_type;

ALTER TRIGGER custom_asset_valuation_currency_guard ON custom_asset_valuation
RENAME TO custom_asset_valuation_guard_2_currency;
