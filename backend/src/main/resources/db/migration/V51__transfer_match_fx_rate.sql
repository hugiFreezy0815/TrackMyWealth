-- =============================================================================================
-- V51: The FX rate a confirmed cross-currency transfer implies (US-10-06, DM-06)
-- =============================================================================================
-- Two separately imported legs in different currencies (e.g. CHF 1,000 out, EUR 1,038.25 in) are
-- matched within a tolerance of the daily rate (#181). Once a member confirms the pair, the rate
-- the bank actually applied follows from the two amounts: credit / debit, one unit of the debit's
-- currency in the credit's (1.03825 here, the same direction as fx_rate base -> quote). It is kept
-- on the match, not on the legs: their fx_rate_to_account_currency converts a row into its own
-- account's currency, is frozen by the append-only trigger, and would rescale a balance.
-- Same-currency transfers and card settlements have none.
-- =============================================================================================

ALTER TABLE settlement_match
ADD COLUMN transfer_fx_rate NUMERIC(20, 10),
ADD CONSTRAINT settlement_match_transfer_fx_rate_check CHECK (
    transfer_fx_rate IS NULL OR (match_kind = 'TRANSFER' AND transfer_fx_rate > 0)
);
