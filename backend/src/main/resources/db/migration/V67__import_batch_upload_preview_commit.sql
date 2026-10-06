-- =============================================================================================
-- V67: upload, preview and commit of an import batch (US-07-04, #230)
-- =============================================================================================
-- A member uploads a bank file for one account, sees what the import will do (new rows,
-- duplicates, errors, warnings), adjusts it and commits it explicitly (FR-IMP-004/005). Nothing
-- reaches the ledger without that confirmation, and a file is never imported twice.
--
-- import_file keeps the original bytes (product owner decision 2026-10-02), so a batch can be
-- re-parsed with a corrected template and the source evidence is kept (FR-IMP-013). It is a table
-- of its own, so reading a batch never loads the file. Discarding a batch deletes it; workspace
-- erasure (EPIC 31) must delete it too.
--
-- The duplicate check (database-schema.md, "Import batches") reads the account's ledger rows
-- between the file's first and last booking date through idx_transaction_account_date (V10); the
-- remaining columns of the match are compared in memory, so no further index is needed.
-- =============================================================================================

CREATE TABLE import_file (
    import_batch_id UUID PRIMARY KEY REFERENCES import_batch (id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspace (id),
    content BYTEA NOT NULL, -- noqa: RF04
    sha256 CHAR(64) NOT NULL,
    size_bytes INTEGER NOT NULL CHECK (size_bytes >= 0),
    -- As the client sent it. No check: a later bank sync stores camt.052 XML or MT940 text here.
    media_type TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_import_file_workspace ON import_file (workspace_id);

ALTER TABLE import_file ENABLE ROW LEVEL SECURITY;
ALTER TABLE import_file FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON import_file
USING (workspace_id = current_workspace_id());

-- ADR 0004: parse, row inclusion, commit and discard are read-modify-write on the batch.
ALTER TABLE import_batch
ADD COLUMN version INTEGER NOT NULL DEFAULT 0; -- noqa: RF04

CREATE TRIGGER import_batch_bump_version
BEFORE UPDATE ON import_batch
FOR EACH ROW EXECUTE FUNCTION trg_bump_version();

-- Kept after a discard deletes the file: the same file uploaded again is still recognised.
ALTER TABLE import_batch
ADD COLUMN file_sha256 CHAR(64);

-- Where the batch's rows come from, and so the transaction.source its commit writes: CSV for a
-- delimited file, DOCUMENT for a PDF statement (#268), API for a later bank sync (EPIC 24).
ALTER TABLE import_batch
ADD COLUMN source_kind TEXT NOT NULL DEFAULT 'CSV';

ALTER TABLE import_batch
ADD CONSTRAINT chk_import_batch_source_kind CHECK (
    source_kind IN ('CSV', 'DOCUMENT', 'API')
);

CREATE INDEX idx_import_batch_account_file
ON import_batch (account_id, file_sha256);

-- import_row_raw holds raw bank data: it gets its own workspace policy instead of relying on the
-- batch it belongs to (defense in depth). No writer existed before this migration, so the table is
-- empty everywhere; the backfill only keeps the migration safe on a hand-filled database.
ALTER TABLE import_row_raw
ADD COLUMN workspace_id UUID REFERENCES workspace (id);

UPDATE import_row_raw r
SET workspace_id = b.workspace_id
FROM import_batch AS b
WHERE b.id = r.import_batch_id;

ALTER TABLE import_row_raw
ALTER COLUMN workspace_id SET NOT NULL;

CREATE INDEX idx_import_row_raw_workspace ON import_row_raw (workspace_id);

ALTER TABLE import_row_raw ENABLE ROW LEVEL SECURITY;
ALTER TABLE import_row_raw FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON import_row_raw
USING (workspace_id = current_workspace_id());

-- Whether the commit turns the row into a transaction: a new row starts included, a duplicate
-- excluded; the member can change either, never an ERROR row.
ALTER TABLE import_row_raw
ADD COLUMN included BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE import_row_raw
ADD CONSTRAINT chk_import_row_raw_error_excluded CHECK (
    NOT (included AND parse_status = 'ERROR')
);

ALTER TABLE import_row_raw
ADD COLUMN duplicate_of_transaction_id UUID REFERENCES transaction (id);

ALTER TABLE import_row_raw
ADD COLUMN warning_codes TEXT[] NOT NULL DEFAULT '{}';

-- A stable code and its ordered message arguments instead of English text (error_message stays
-- for rows written before; nothing writes it any more). JSON, not JSONB: the arguments fill the
-- message's {0}, {1}, ... in their order, which JSONB does not keep.
ALTER TABLE import_row_raw
ADD COLUMN error_code TEXT;

ALTER TABLE import_row_raw
ADD COLUMN error_args JSON;

-- The raw cells in the file's column order, for the preview and the error export: JSON keeps it,
-- JSONB (V15) sorts the keys. Nothing queries inside them.
ALTER TABLE import_row_raw
ALTER COLUMN raw_data TYPE JSON USING raw_data::JSON;

-- The row's values in the ledger's terms (CanonicalImportRow, decimals as strings), and the three
-- of them a reconciliation looks for (MISSING_TRANSACTION, FR-REC-003). NULL for a row that could
-- not be read.
ALTER TABLE import_row_raw
ADD COLUMN canonical_data JSONB;

ALTER TABLE import_row_raw
ADD COLUMN booking_date DATE;

ALTER TABLE import_row_raw
ADD COLUMN amount NUMERIC(20, 4);

ALTER TABLE import_row_raw
ADD COLUMN currency CHAR(3);
