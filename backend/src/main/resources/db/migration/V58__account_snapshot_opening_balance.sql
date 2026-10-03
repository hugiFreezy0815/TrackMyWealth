-- =============================================================================================
-- V58: Opening balances on account_snapshot (US-25-04, FR-REC-007)
-- =============================================================================================
-- V11 created account_snapshot.is_opening_balance but nothing set it. An opening balance is the
-- member's dated starting point for an account whose transaction history begins later than the
-- account itself: the account's value from that date on is the balance plus the ledger after it.
--
-- 1. At most one opening balance per account. Two would leave "where does the ledger start"
--    ambiguous. A partial unique index, so regular snapshots are unaffected.
--
-- 2. An opening balance is a MANUAL balance-only observation: the member types it in, and the
--    value source adds the ledger to it, which needs a figure. Opening holdings with a cost basis
--    are US-25-05 and need tax lots (EPIC 15); the service keeps the holdings set empty.
--
-- 3. A snapshot's currency is the account's own (ledger) currency. V33 checked native_currency,
--    which is right for every account type except a credit card: a card's ledger is summed in
--    its billing_currency (US-09-04, FR-CC-010), which may differ from native_currency. A card
--    snapshot - its opening balance first of all - is compared against, and added to, that
--    ledger, so it must be in the billing currency. account_credit_card.billing_currency is only
--    written when the card is created, so a stored snapshot cannot drift out of step with it.
-- =============================================================================================

CREATE UNIQUE INDEX uq_account_snapshot_opening_balance
ON account_snapshot (account_id)
WHERE is_opening_balance;

ALTER TABLE account_snapshot
ADD CONSTRAINT chk_account_snapshot_opening_balance_manual
CHECK (NOT is_opening_balance OR (source = 'MANUAL' AND balance IS NOT NULL));

CREATE OR REPLACE FUNCTION trg_account_snapshot_currency_guard()
RETURNS TRIGGER AS $$
DECLARE
    account_currency TEXT;
BEGIN
    SELECT coalesce(card.billing_currency, account.native_currency) INTO account_currency
    FROM account
    LEFT JOIN account_credit_card AS card ON card.account_id = account.id
    WHERE account.id = NEW.account_id;
    IF NEW.currency IS DISTINCT FROM account_currency THEN
        RAISE EXCEPTION 'account_snapshot_currency_mismatch: account % ledger currency is % but a snapshot in % was attempted (FR-ACC-002)',
            NEW.account_id, account_currency, NEW.currency USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
