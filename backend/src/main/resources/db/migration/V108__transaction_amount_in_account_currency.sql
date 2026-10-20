-- =============================================================================================
-- V108: A transaction's amount in its account's own currency (US-29-03, #373, PR #388 review)
-- =============================================================================================
-- amount is stored in the booking's own currency; fx_rate_to_account_currency converts it into
-- the account's currency by multiplication and is null for a same-currency row (US-09-04,
-- DM-06). A list sorted by "amount" must compare what each booking cost the account, so the
-- converted value is a generated column: every query that reads transaction rows sees it,
-- native ones included, and it can never disagree with amount and rate - both are frozen by
-- trg_transaction_append_only (V10, V21), so the value never changes after the insert.
-- =============================================================================================

ALTER TABLE transaction
ADD COLUMN amount_in_account_currency NUMERIC(20, 4) GENERATED ALWAYS AS (
    round(amount * coalesce(fx_rate_to_account_currency, 1), 4)
) STORED;
