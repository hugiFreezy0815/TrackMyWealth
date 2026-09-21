-- =============================================================================================
-- V30: settlement matching (US-09-02)
-- =============================================================================================
-- FR-CC-004/005/007, FR-CF-001/004/005, DM-05: the monthly payment from a current account to a
-- credit card is an internal transfer, not a second expense. Matching links the two legs and
-- records who decided it, so a wrong match can be rejected and never re-proposed.
--
-- The ledger itself is not touched: transaction.is_internal_transfer and
-- transaction.counterparty_account_id (V10) are already mutable columns, and
-- trg_transaction_append_only freezes transaction_type, so a match never re-types a leg. This
-- table only holds the decision and its state.
--
-- Uniqueness is what keeps matching idempotent and rejections sticky:
--   * a payment (and a card credit) can be part of at most one CONFIRMED match;
--   * the same payment/credit pair, or the same one-sided payment/card, exists at most once in ANY
--     status - so a REJECTED row is never proposed again and a re-run adds nothing.
-- =============================================================================================

-- A card cannot be its own settlement source.
ALTER TABLE account_credit_card
ADD CONSTRAINT account_credit_card_settlement_source_not_self
CHECK (settlement_source_account_id IS DISTINCT FROM account_id);

CREATE TABLE settlement_match (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace (id),
    card_account_id UUID NOT NULL REFERENCES account (id),
    -- The debit on the card's settlement-source account.
    payment_transaction_id UUID NOT NULL REFERENCES transaction (id),
    -- The matching credit on the card. NULL for a one-sided candidate (FR-CF-005: only one leg has
    -- been imported so far) and set once the second leg arrives.
    card_transaction_id UUID REFERENCES transaction (id),
    status TEXT NOT NULL CHECK (status IN ('PROPOSED', 'CONFIRMED', 'REJECTED')),
    match_basis TEXT NOT NULL CHECK (match_basis IN ('LEG_PAIR', 'BALANCE_EQUALS_PAYMENT')),
    -- NULL when the system decided (an unambiguous exact pair, or a competing proposal that lost).
    decided_by UUID REFERENCES app_user (id),
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A pair has both legs; only a balance-based candidate lacks the card leg.
    CONSTRAINT settlement_match_basis_matches_legs
    CHECK ((match_basis = 'LEG_PAIR') = (card_transaction_id IS NOT NULL))
);

CREATE INDEX idx_settlement_match_workspace_status
ON settlement_match (workspace_id, status);
CREATE INDEX idx_settlement_match_card ON settlement_match (card_account_id);
CREATE INDEX idx_settlement_match_card_transaction
ON settlement_match (card_transaction_id) WHERE card_transaction_id IS NOT NULL;

CREATE UNIQUE INDEX uq_settlement_match_confirmed_payment
ON settlement_match (payment_transaction_id) WHERE status = 'CONFIRMED';
CREATE UNIQUE INDEX uq_settlement_match_confirmed_card_transaction
ON settlement_match (card_transaction_id)
WHERE status = 'CONFIRMED' AND card_transaction_id IS NOT NULL;
CREATE UNIQUE INDEX uq_settlement_match_pair
ON settlement_match (payment_transaction_id, card_transaction_id)
WHERE card_transaction_id IS NOT NULL;
CREATE UNIQUE INDEX uq_settlement_match_one_sided
ON settlement_match (payment_transaction_id, card_account_id)
WHERE card_transaction_id IS NULL;

CREATE TRIGGER settlement_match_set_updated_at BEFORE UPDATE ON settlement_match
FOR EACH ROW EXECUTE FUNCTION trg_set_updated_at();

-- Same "own workspace only" policy V20 applies to every table carrying workspace_id.
ALTER TABLE settlement_match ENABLE ROW LEVEL SECURITY;
ALTER TABLE settlement_match FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON settlement_match
USING (workspace_id = current_workspace_id());
