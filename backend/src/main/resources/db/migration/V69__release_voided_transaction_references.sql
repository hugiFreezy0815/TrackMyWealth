-- Voided transactions remain audit history, but must not prevent re-import after a rollback
-- (US-07-05, #231). Keep their original external_id; reserve references only while not voided.
-- Soft-deleted/corrected rows retain the existing reservation semantics.
DROP INDEX uq_transaction_external_id;

CREATE UNIQUE INDEX uq_transaction_external_id
ON transaction (account_id, source, external_id)
WHERE external_id IS NOT NULL AND voided_at IS NULL;
