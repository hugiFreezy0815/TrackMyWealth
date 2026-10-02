-- =============================================================================================
-- V49: Correcting a transaction as removal plus replacement (US-07-06, FR-LIF-004, issue #178)
-- =============================================================================================
-- Editing a financial field (amount, currency, date, quantity, price, account, ...) never changes
-- the row in place (RULE-024, trg_transaction_append_only): the original is removed the way its
-- provenance requires (V39: soft delete for a manual row, void plus reversing row for an imported
-- one) and a replacement row with the corrected values is inserted in the same transaction.
-- corrects_transaction_id links the replacement back to the original, so history shows what was
-- corrected into what.
--
-- A new column rather than replaces_transaction_id: that one marks a void's reversing row, which
-- every figure leaves out and which can never be removed on its own (V39). A replacement is the
-- opposite - an ordinary row that counts and can itself be corrected or removed again.
-- =============================================================================================

ALTER TABLE transaction
ADD COLUMN corrects_transaction_id UUID REFERENCES transaction (id);

-- A row is either a reversing entry or a replacement, never both.
ALTER TABLE transaction
ADD CONSTRAINT transaction_correction_not_reversal CHECK (
    corrects_transaction_id IS NULL OR replaces_transaction_id IS NULL
);

-- At most one replacement per original: a retried correction can never replace twice.
CREATE UNIQUE INDEX uq_transaction_correction
ON transaction (corrects_transaction_id)
WHERE corrects_transaction_id IS NOT NULL;
