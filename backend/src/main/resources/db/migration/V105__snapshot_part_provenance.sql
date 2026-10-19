-- =============================================================================================
-- V105: an imported snapshot completed part by part (US-07-31, #366)
-- =============================================================================================
-- An imported (DOCUMENT) snapshot has two parts: its balance and its holdings. A second statement
-- of the same account and date may add the part the snapshot lacks (PostFinance's cash export
-- after its positions export), so each part records the batch that contributed it, and a rollback
-- removes only its own batch's parts. import_batch_id stays the batch that created the snapshot
-- (V74), or passes to the remaining contributor when that batch is rolled back.
-- =============================================================================================

ALTER TABLE account_snapshot
ADD COLUMN balance_import_batch_id UUID REFERENCES import_batch (id);

ALTER TABLE snapshot_holding
ADD COLUMN import_batch_id UUID REFERENCES import_batch (id);

-- Every imported snapshot so far was recorded by one batch, which contributed both its parts.
UPDATE account_snapshot
SET balance_import_batch_id = import_batch_id
WHERE import_batch_id IS NOT NULL AND balance IS NOT NULL;

UPDATE snapshot_holding AS sh
SET import_batch_id = snap.import_batch_id
FROM account_snapshot AS snap
WHERE snap.id = sh.snapshot_id AND snap.import_batch_id IS NOT NULL;

-- Only an imported snapshot's balance names a batch, and only a balance that exists.
ALTER TABLE account_snapshot
ADD CONSTRAINT chk_account_snapshot_balance_import_batch CHECK (
    balance_import_batch_id IS NULL
    OR (source = 'DOCUMENT' AND balance IS NOT NULL)
);

CREATE INDEX idx_account_snapshot_balance_import_batch
ON account_snapshot (balance_import_batch_id)
WHERE balance_import_batch_id IS NOT NULL;

CREATE INDEX idx_snapshot_holding_import_batch
ON snapshot_holding (import_batch_id)
WHERE import_batch_id IS NOT NULL;

-- What a commit, and later a rollback, did with the snapshot: COMPLETED (added parts to a snapshot
-- another batch recorded), REMOVED (a rollback took this batch's parts out; the snapshot stays
-- with another batch's).
ALTER TABLE import_batch
DROP CONSTRAINT chk_import_batch_statement_balance_snapshot;

ALTER TABLE import_batch
ADD CONSTRAINT chk_import_batch_statement_balance_snapshot CHECK (
    statement_balance_snapshot IS NULL
    OR (
        statement_balance_outcome IS NOT DISTINCT FROM 'FOUND'
        AND statement_balance_snapshot IN (
            'RECORDED', 'COMPLETED', 'ALREADY_RECORDED', 'CONFLICTING', 'DELETED', 'WITHDRAWN',
            'REMOVED'
        )
    )
);

ALTER TABLE import_batch
DROP CONSTRAINT chk_import_batch_holdings_snapshot;

ALTER TABLE import_batch
ADD CONSTRAINT chk_import_batch_holdings_snapshot CHECK (
    holdings_snapshot IS NULL
    OR (
        holdings_snapshot_date IS NOT NULL
        AND holdings_snapshot IN ('RECORDED', 'COMPLETED', 'DELETED', 'WITHDRAWN', 'REMOVED')
    )
);
