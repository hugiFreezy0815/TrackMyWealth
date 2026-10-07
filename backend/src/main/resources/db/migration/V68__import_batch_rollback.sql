-- =============================================================================================
-- V68: rolling back an import batch (US-07-05, #231)
-- =============================================================================================
-- A committed batch is reversible as a whole (FR-IMP-005, FR-LIF-010/011/012). While nobody has
-- worked on its rows they are hard-deleted and the batch becomes ROLLED_BACK; once someone has,
-- they are voided like any imported row (V39) and the batch becomes VOIDED. Either way the batch,
-- its rows and its file stay as evidence, and the batch records who rolled it back, when and why.
--
-- The hard delete is the one exception to "a transaction is never hard-deleted" (FR-LIF-001,
-- V39). trg_transaction_no_hard_delete keeps the rule in the database for everything else: a
-- transaction can only be deleted by the rollback of its own batch, which names that batch in the
-- transaction-local setting app.import_rollback_batch_id (set_config(..., TRUE)) first.
-- =============================================================================================

ALTER TABLE import_batch
ADD COLUMN rolled_back_by UUID REFERENCES app_user (id),
ADD COLUMN rollback_reason TEXT;

-- Safe on existing data: no batch was ever ROLLED_BACK or VOIDED, and none has rolled_back_at.
ALTER TABLE import_batch
ADD CONSTRAINT chk_import_batch_rollback_recorded CHECK (
    (status IN ('ROLLED_BACK', 'VOIDED')) = (rolled_back_at IS NOT NULL)
    AND (rolled_back_at IS NULL) = (rolled_back_by IS NULL)
    AND (rolled_back_at IS NULL) = (rollback_reason IS NULL)
    AND (
        rollback_reason IS NULL
        OR char_length(btrim(rollback_reason)) BETWEEN 1 AND 500
    )
);

CREATE FUNCTION trg_transaction_no_hard_delete()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.import_batch_id IS NULL
        OR OLD.import_batch_id::TEXT IS DISTINCT FROM
            NULLIF(current_setting('app.import_rollback_batch_id', TRUE), '')
    THEN
        RAISE EXCEPTION 'transaction_no_hard_delete: transaction % cannot be deleted (FR-LIF-001). Void it instead (FR-LIF-002); only the rollback of its own import batch may delete it (FR-LIF-010).',
            OLD.id USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER transaction_no_hard_delete
BEFORE DELETE ON transaction
FOR EACH ROW EXECUTE FUNCTION trg_transaction_no_hard_delete();
