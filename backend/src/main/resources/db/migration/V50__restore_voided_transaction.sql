-- =============================================================================================
-- V50: Restore a voided transaction (US-07-07, FR-LIF-006)
-- =============================================================================================
-- A void remains immutable history: the original keeps voided_at/voided_by/void_reason and its
-- first reversing row remains in the ledger. Restoring records who undid the void on the original
-- and the service inserts a second reversing row that reverses the first reversal. The three-row
-- chain therefore has the original financial effect again without updating a committed financial
-- field (RULE-024).
-- =============================================================================================

ALTER TABLE transaction
ADD COLUMN restored_at TIMESTAMPTZ,
ADD COLUMN restored_by UUID REFERENCES app_user (id),
ADD CONSTRAINT transaction_restore_complete CHECK (
    (restored_at IS NULL) = (restored_by IS NULL)
),
ADD CONSTRAINT transaction_restore_requires_void CHECK (
    restored_at IS NULL
    OR (
        voided_at IS NOT NULL
        AND restored_at >= voided_at
        AND replaces_transaction_id IS NULL
    )
);
