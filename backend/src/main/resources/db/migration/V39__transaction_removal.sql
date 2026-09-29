-- =============================================================================================
-- V39: Removing a transaction - soft delete (T1) and void (T2) (US-07-02, FR-LIF-002/002a/003/006)
-- =============================================================================================
-- The ledger stays append-only (RULE-024): neither removal touches a financial field, which
-- trg_transaction_append_only keeps enforcing. Which removal applies is decided by the system from
-- the row's provenance (FR-LIF-002b):
--   - T1, a manually entered row: soft delete. deleted_at/deleted_by hide it from every view and
--     figure. It is restorable for 30 days (FR-LIF-006) and never purged, because FR-LIF-001 allows
--     no hard delete of a transaction (decision on issue #143, closing OPEN-032).
--   - T2, an imported row: void. voided_at/voided_by/void_reason mark the original (V10), and a
--     reversing row with negated amounts is inserted, linked by replaces_transaction_id.
-- =============================================================================================

ALTER TABLE transaction
ADD COLUMN deleted_at TIMESTAMPTZ,
ADD COLUMN deleted_by UUID REFERENCES app_user (id);

-- A removed row is removed one way only: a voided row is never also soft-deleted, or the other way
-- round, and a reversing row (it carries replaces_transaction_id) is never removed itself - it
-- goes with its original.
ALTER TABLE transaction
ADD CONSTRAINT transaction_removed_one_way CHECK (deleted_at IS NULL OR voided_at IS NULL),
ADD CONSTRAINT transaction_reversal_not_removed CHECK (
    replaces_transaction_id IS NULL OR (deleted_at IS NULL AND voided_at IS NULL)
),
-- FR-LIF-002: a void always says when and why; soft delete records when and by whom.
ADD CONSTRAINT transaction_void_has_reason CHECK (
    (voided_at IS NULL) = (void_reason IS NULL)
    AND (void_reason IS NULL OR char_length(btrim(void_reason)) BETWEEN 1 AND 500)
),
ADD CONSTRAINT transaction_deletion_has_actor CHECK (
    deleted_at IS NULL OR deleted_by IS NOT NULL
);

-- At most one reversing row per original: a retried void can never reverse twice.
CREATE UNIQUE INDEX uq_transaction_reversal
ON transaction (replaces_transaction_id)
WHERE replaces_transaction_id IS NOT NULL;

-- The restore list: an account's soft-deleted rows, newest first.
CREATE INDEX idx_transaction_deleted
ON transaction (account_id, deleted_at DESC)
WHERE deleted_at IS NOT NULL;
