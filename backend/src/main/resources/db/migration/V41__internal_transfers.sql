-- =============================================================================================
-- V41: Internal transfers between own accounts (US-10-01, FR-CF-001/002/003/005, DM-05)
-- =============================================================================================
-- Two things:
--   1. A declared capability, counts_as_saving (FR-ACC-010 style): money moved into such an
--      account is saving or investing in cash flow, not a neutral internal transfer. On by default
--      for savings, depot, mandate, crypto, pension and vested-benefits accounts; the member may
--      override it per account. Existing accounts get the default of their type.
--   2. settlement_match (V30) now also records own-account transfers, not only card settlements
--      (decision on issue #147: one matching table). match_kind tells them apart. For a TRANSFER
--      match, payment_transaction_id is the debit leg, card_transaction_id the credit leg and
--      card_account_id the credit leg's account - the column names stay, their meaning widens.
--      A transfer is always a pair of legs (LEG_PAIR); a leg without a counterpart is resolved on
--      the transaction itself (is_internal_transfer with no counterparty_account_id), not here.
-- =============================================================================================

ALTER TABLE account
ADD COLUMN counts_as_saving BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE account
SET counts_as_saving = TRUE
WHERE
    account_type IN (
        'SAVINGS', 'SECURITIES', 'MANAGED_MANDATE', 'CRYPTO', 'PENSION', 'VESTED_BENEFITS'
    );

ALTER TABLE settlement_match
ADD COLUMN match_kind TEXT NOT NULL DEFAULT 'CARD_SETTLEMENT',
ADD CONSTRAINT settlement_match_kind_check CHECK (
    match_kind IN ('CARD_SETTLEMENT', 'TRANSFER')
),
ADD CONSTRAINT settlement_match_transfer_is_a_pair CHECK (
    match_kind = 'CARD_SETTLEMENT' OR match_basis = 'LEG_PAIR'
);
