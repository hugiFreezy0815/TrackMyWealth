-- US-07-12: a statement's balance, read from its import file, becomes an account snapshot.

-- The template's rule for where a file states its balance: one RE2 pattern with the named groups
-- date and amount (and optionally currency), versioned with the template like every parse-relevant
-- setting.
ALTER TABLE import_template
ADD COLUMN statement_balance_rule JSONB;

ALTER TABLE import_template
ADD CONSTRAINT chk_import_template_statement_balance_rule CHECK (
    statement_balance_rule IS NULL
    OR (
        jsonb_typeof(statement_balance_rule) = 'object'
        AND jsonb_typeof(statement_balance_rule -> 'pattern') = 'string'
    )
);

-- What the parsed file states as its balance. The closing balance (the latest one in the account's
-- currency) is what a commit records; the opening balance is shown in the preview only. The
-- balance follows the snapshot's convention: a liability's is the positive amount owed.
-- OUT_OF_RANGE keeps a balance read whose date the account does not admit (future, before it was
-- opened, after it was closed): it is shown, and the commit records no snapshot for it.
-- statement_balance_snapshot says what the commit, and later a rollback, did with it.
ALTER TABLE import_batch
ADD COLUMN statement_balance_outcome VARCHAR(20),
ADD COLUMN statement_balance_date DATE,
ADD COLUMN statement_balance NUMERIC(20, 4),
ADD COLUMN statement_balance_currency CHAR(3),
ADD COLUMN opening_balance_date DATE,
ADD COLUMN opening_balance NUMERIC(20, 4),
ADD COLUMN statement_balance_snapshot VARCHAR(20);

ALTER TABLE import_batch
ADD CONSTRAINT chk_import_batch_statement_balance_outcome CHECK (
    statement_balance_outcome IS NULL
    OR statement_balance_outcome IN (
        'FOUND', 'OUT_OF_RANGE', 'NOT_FOUND', 'UNREADABLE', 'AMBIGUOUS'
    )
);

ALTER TABLE import_batch
ADD CONSTRAINT chk_import_batch_statement_balance_found CHECK (
    (coalesce(statement_balance_outcome IN ('FOUND', 'OUT_OF_RANGE'), FALSE))
    = (
        statement_balance_date IS NOT NULL
        AND statement_balance IS NOT NULL
        AND statement_balance_currency IS NOT NULL
    )
);

ALTER TABLE import_batch
ADD CONSTRAINT chk_import_batch_opening_balance CHECK (
    (opening_balance_date IS NULL) = (opening_balance IS NULL)
    AND (
        opening_balance_date IS NULL
        OR statement_balance_outcome IN ('FOUND', 'OUT_OF_RANGE')
    )
);

ALTER TABLE import_batch
ADD CONSTRAINT chk_import_batch_statement_balance_snapshot CHECK (
    statement_balance_snapshot IS NULL
    OR (
        statement_balance_outcome IS NOT DISTINCT FROM 'FOUND'
        AND statement_balance_snapshot IN (
            'RECORDED', 'ALREADY_RECORDED', 'CONFLICTING', 'DELETED', 'WITHDRAWN'
        )
    )
);

-- The batch whose commit recorded the snapshot: a rollback removes it again. Only an imported
-- (DOCUMENT) snapshot has one, and a batch records at most one.
ALTER TABLE account_snapshot
ADD COLUMN import_batch_id UUID REFERENCES import_batch (id),
ADD COLUMN withdrawn_at TIMESTAMPTZ,
ADD COLUMN withdrawn_by UUID REFERENCES app_user (id);

ALTER TABLE account_snapshot
ADD CONSTRAINT chk_account_snapshot_import_batch CHECK (
    import_batch_id IS NULL OR source = 'DOCUMENT'
);

-- A rollback withdraws, rather than deletes, a snapshot whose reconciliation once had an accepted
-- adjusting entry: that entry stays in the append-only ledger and points at the result, which
-- points at the snapshot. A withdrawn snapshot is history only, never an observation.
ALTER TABLE account_snapshot
ADD CONSTRAINT chk_account_snapshot_withdrawn CHECK (
    withdrawn_at IS NULL OR import_batch_id IS NOT NULL
);

CREATE UNIQUE INDEX uq_account_snapshot_import_batch
ON account_snapshot (import_batch_id)
WHERE import_batch_id IS NOT NULL;

-- One observation per account, date and source, as before, but a withdrawn one no longer counts:
-- the statement can be imported again. The index keeps the constraint's name, which the API's
-- conflict mapping recognises.
ALTER TABLE account_snapshot
DROP CONSTRAINT account_snapshot_account_id_snapshot_date_source_key;

CREATE UNIQUE INDEX account_snapshot_account_id_snapshot_date_source_key
ON account_snapshot (account_id, snapshot_date, source)
WHERE withdrawn_at IS NULL;
