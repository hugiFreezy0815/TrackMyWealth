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
-- transaction-local setting app.import_rollback_batch_id (set_config(..., TRUE)) first. TRUNCATE
-- fires no row trigger, so trg_transaction_no_truncate refuses it outright. The guard catches
-- application bugs; it is no boundary against SQL run as the application's own role, which can
-- set the same setting.
--
-- Until now no transaction was ever deleted, so the columns that reference one had no index of
-- their own. A delete checks every such foreign key per deleted row, and the rollback's
-- "modified" queries look them up per transaction: without an index each is a full scan.
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

CREATE FUNCTION trg_transaction_no_truncate()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'transaction_no_hard_delete: the ledger cannot be truncated (FR-LIF-001).'
        USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER transaction_no_truncate
BEFORE TRUNCATE ON transaction
FOR EACH STATEMENT EXECUTE FUNCTION trg_transaction_no_truncate();

-- The referencing columns no earlier migration indexed (the others: V10, V13, V30, V39, V49, V50).
CREATE INDEX idx_transaction_related_transaction
ON transaction (related_transaction_id)
WHERE related_transaction_id IS NOT NULL;

CREATE INDEX idx_import_row_raw_resulting_transaction
ON import_row_raw (resulting_transaction_id)
WHERE resulting_transaction_id IS NOT NULL;

CREATE INDEX idx_import_row_raw_duplicate_of_transaction
ON import_row_raw (duplicate_of_transaction_id)
WHERE duplicate_of_transaction_id IS NOT NULL;

CREATE INDEX idx_reconciliation_result_resolution_transaction
ON reconciliation_result (resolution_transaction_id)
WHERE resolution_transaction_id IS NOT NULL;

CREATE INDEX idx_tax_lot_acquisition_transaction
ON tax_lot (acquisition_transaction_id)
WHERE acquisition_transaction_id IS NOT NULL;
