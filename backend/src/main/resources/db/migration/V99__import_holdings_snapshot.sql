-- =============================================================================================
-- V99: import a holdings statement as a snapshot with positions (US-07-13, #273)
-- =============================================================================================
-- A SNAPSHOT template (INPUT-004) reads a bank's positions statement - a PDF's text lines or a
-- spreadsheet's rows - through its holdings_rule (ImportHoldingsRule). An upload stages what it
-- read on the batch (import_holding_row); the commit records one DOCUMENT snapshot of the
-- statement's date with a holding per position, and a rollback removes it again (US-07-12's
-- rules). Positions resolve to securities by ISIN, or by a ticker the workspace mapped
-- (workspace_security_ticker); the commit is refused while one does not resolve.
-- =============================================================================================

-- --- templates --------------------------------------------------------------------------------

ALTER TABLE import_template
ADD COLUMN holdings_rule JSONB;

ALTER TABLE import_template
ADD CONSTRAINT chk_import_template_holdings_rule CHECK (
    (template_class = 'SNAPSHOT') = (holdings_rule IS NOT NULL)
    AND (holdings_rule IS NULL OR jsonb_typeof(holdings_rule) = 'object')
);

-- SPREADSHEET: an .xlsx or .xls workbook, read through its first sheet. Holdings only, so far.
ALTER TABLE import_template
DROP CONSTRAINT chk_import_template_file_format;

ALTER TABLE import_template
ADD CONSTRAINT chk_import_template_file_format CHECK (
    file_format IN ('CSV', 'PDF_TEXT', 'PDF_OCR', 'SPREADSHEET')
    AND (file_format <> 'SPREADSHEET' OR template_class = 'SNAPSHOT')
);

-- The booking-line layout belongs to a cash statement PDF; a holdings PDF is read by its rule.
ALTER TABLE import_template
DROP CONSTRAINT chk_import_template_pdf_layout;

ALTER TABLE import_template
ADD CONSTRAINT chk_import_template_pdf_layout CHECK (
    (
        file_format IN ('PDF_TEXT', 'PDF_OCR')
        AND template_class = 'CASH_TRANSACTIONS'
    ) = (pdf_layout IS NOT NULL)
);

-- --- batches ----------------------------------------------------------------------------------

-- The date the snapshot is of: the statement's own (STATEMENT), or the one the member entered at
-- upload for a file that states none, such as a spreadsheet export (MEMBER). holdings_snapshot
-- says what the commit, and later a rollback, did with it.
ALTER TABLE import_batch
ADD COLUMN holdings_snapshot_date DATE,
ADD COLUMN holdings_date_source VARCHAR(10),
ADD COLUMN holdings_snapshot VARCHAR(20);

ALTER TABLE import_batch
ADD CONSTRAINT chk_import_batch_holdings_date CHECK (
    (holdings_snapshot_date IS NULL) = (holdings_date_source IS NULL)
    AND (holdings_date_source IS NULL OR holdings_date_source IN ('STATEMENT', 'MEMBER'))
);

ALTER TABLE import_batch
ADD CONSTRAINT chk_import_batch_holdings_snapshot CHECK (
    holdings_snapshot IS NULL
    OR (
        holdings_snapshot_date IS NOT NULL
        AND holdings_snapshot IN ('RECORDED', 'DELETED', 'WITHDRAWN')
    )
);

-- A batch is a cash statement's or a holdings statement's, never both.
ALTER TABLE import_batch
ADD CONSTRAINT chk_import_batch_holdings_not_balance CHECK (
    holdings_snapshot_date IS NULL OR statement_balance_outcome IS NULL
);

-- One line of a holdings statement as read: a position, a cash balance, or a line its rule's
-- pattern matched but whose values could not be read (ERROR), which blocks the commit - a
-- snapshot missing a position would be wrong, not partial. Raw bank data, so under a workspace
-- policy of its own, as import_row_raw is (V67).
CREATE TABLE import_holding_row (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace (id),
    import_batch_id UUID NOT NULL REFERENCES import_batch (id),
    row_number INTEGER NOT NULL CHECK (row_number > 0),
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('POSITION', 'CASH', 'ERROR')),
    isin CHAR(12),
    symbol VARCHAR(20),
    security_name VARCHAR(200),
    quantity NUMERIC(28, 10),
    reported_price NUMERIC(20, 10),
    reported_value NUMERIC(20, 4),
    currency CHAR(3),
    amount NUMERIC(20, 4),
    raw_text VARCHAR(500),
    error_code VARCHAR(40),
    warning_codes TEXT[] NOT NULL DEFAULT '{}',
    UNIQUE (import_batch_id, row_number),
    CONSTRAINT chk_import_holding_row_position CHECK (
        kind <> 'POSITION'
        OR (
            (isin IS NOT NULL OR symbol IS NOT NULL)
            AND quantity > 0
            AND reported_value >= 0
            AND (reported_price IS NULL OR reported_price >= 0)
            AND currency IS NOT NULL
            AND amount IS NULL
        )
    ),
    CONSTRAINT chk_import_holding_row_cash CHECK (
        kind <> 'CASH'
        OR (
            currency IS NOT NULL
            AND amount IS NOT NULL
            AND isin IS NULL
            AND symbol IS NULL
            AND quantity IS NULL
        )
    ),
    CONSTRAINT chk_import_holding_row_error CHECK (
        (kind = 'ERROR') = (error_code IS NOT NULL)
    )
);

CREATE INDEX idx_import_holding_row_workspace ON import_holding_row (workspace_id);

ALTER TABLE import_holding_row ENABLE ROW LEVEL SECURITY;
ALTER TABLE import_holding_row FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON import_holding_row
USING (workspace_id = current_workspace_id());

COMMENT ON TABLE import_holding_row IS
'US-07-13 (#273): a holdings statement line as staged by an upload; the commit makes the snapshot.';

-- --- tickers ----------------------------------------------------------------------------------

-- A ticker means a security only within one workspace: tickers repeat across exchanges, and a
-- shared mapping would let one workspace decide what another's statement holds, or find another
-- workspace's hand-entered security by guessing its ticker (NFR-LIC-007). The security master
-- itself stays unsearchable; only an id the workspace mapped is ever returned.
CREATE TABLE workspace_security_ticker (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace (id),
    ticker VARCHAR(20) NOT NULL CHECK (ticker ~ '^[A-Z0-9][A-Z0-9.:-]{0,19}$'),
    security_id UUID NOT NULL REFERENCES security (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, ticker)
);

CREATE INDEX idx_workspace_security_ticker_security
ON workspace_security_ticker (security_id);

CREATE TRIGGER workspace_security_ticker_set_updated_at
BEFORE UPDATE ON workspace_security_ticker
FOR EACH ROW EXECUTE FUNCTION trg_set_updated_at();

ALTER TABLE workspace_security_ticker ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_security_ticker FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_security_ticker
USING (workspace_id = current_workspace_id());

COMMENT ON TABLE workspace_security_ticker IS
'US-07-13 (#273): the security a ticker on this workspace''s statements means.';
