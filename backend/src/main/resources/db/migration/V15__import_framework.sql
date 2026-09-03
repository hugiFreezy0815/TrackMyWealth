-- =============================================================================================
-- V15: Template-driven multi-bank import framework
-- =============================================================================================
-- Section 28/28.1 / FR-IMP-*: support for one institution's CSV export is a declarative template
-- (a data artifact), never institution-specific code (FR-IMP-020). This is what lets the
-- supported-institution list grow without an application release (INPUT-002).
-- =============================================================================================

CREATE TABLE import_template (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    institution_catalogue_id  UUID REFERENCES institution_catalogue(id),
    -- NULL household_id = shipped system template (FR-IMP-009); non-NULL = a household's own
    -- user-defined template for an institution with no shipped support (FR-IMP-024/FR-INS-010).
    household_id                UUID REFERENCES household(id),
    name                            TEXT NOT NULL,
    template_class                     TEXT NOT NULL DEFAULT 'CASH_TRANSACTIONS' CHECK (template_class IN ('CASH_TRANSACTIONS', 'SECURITIES_TRANSACTIONS', 'SNAPSHOT')), -- INPUT-004
    -- FR-IMP-023: versioned with an effective date so a historical import remains reproducible
    -- under the template version actually applied.
    template_version                      TEXT NOT NULL,
    effective_from                            DATE NOT NULL DEFAULT CURRENT_DATE,
    -- FR-IMP-021: the full capability set a template can express.
    delimiter                                    TEXT NOT NULL DEFAULT ',',
    encoding                                        TEXT NOT NULL DEFAULT 'UTF-8',
    decimal_separator                                  TEXT NOT NULL DEFAULT '.',
    thousands_separator                                   TEXT,
    date_format                                              TEXT NOT NULL DEFAULT 'yyyy-MM-dd',
    header_row_index                                            SMALLINT NOT NULL DEFAULT 0,
    preamble_row_count                                             SMALLINT NOT NULL DEFAULT 0,
    trailing_summary_row_count                                        SMALLINT NOT NULL DEFAULT 0,
    amount_representation                                                TEXT NOT NULL DEFAULT 'SINGLE_SIGNED_COLUMN'
                                                                          CHECK (amount_representation IN ('SINGLE_SIGNED_COLUMN', 'SEPARATE_DEBIT_CREDIT', 'NEGATIVE_IN_PARENTHESES')),
    currency_mode                                                           TEXT NOT NULL DEFAULT 'FIXED' CHECK (currency_mode IN ('FIXED', 'PER_ROW', 'FROM_ACCOUNT')),
    fixed_currency                                                             CHAR(3),
    -- column_mapping: {"booking_date":"Buchungsdatum","amount":"Betrag",...} - JSONB because the
    -- key set is genuinely open-ended and never queried by field, only interpreted by the
    -- importer for the row currently being parsed (V-19/V-20/V-21 golden-dataset cases exercise
    -- this table directly).
    column_mapping                                                                JSONB NOT NULL,
    type_mapping                                                                     JSONB NOT NULL DEFAULT '{}', -- source type strings -> canonical transaction_type
    account_identification_strategy                                                    TEXT NOT NULL DEFAULT 'USER_SELECTED'
                                                                                        CHECK (account_identification_strategy IN ('COLUMN', 'PREAMBLE_LINE', 'FILENAME', 'USER_SELECTED')),
    header_fingerprint                                                                     TEXT, -- FR-IMP-022: automatic template detection
    is_system_provided                                                                        BOOLEAN NOT NULL DEFAULT FALSE,
    -- FR-IMP-026: every shipped template must ship with a fixture; enforced by CI, not the DB,
    -- but the pointer lives here so the two never drift apart silently.
    fixture_reference                                                                            TEXT,
    created_at                                                                                       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                                                                                          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_import_template_institution ON import_template(institution_catalogue_id);
CREATE INDEX idx_import_template_household ON import_template(household_id);
CREATE TRIGGER import_template_set_updated_at BEFORE UPDATE ON import_template FOR EACH ROW EXECUTE FUNCTION trg_set_updated_at();

CREATE TABLE import_batch (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id          UUID NOT NULL REFERENCES household(id),
    account_id                UUID NOT NULL REFERENCES account(id),
    template_id                   UUID REFERENCES import_template(id),
    template_version_used             TEXT, -- FR-IMP-023: recorded even if the template later changes
    source_file_name                     TEXT,
    -- FR-IMP-013: original source retained (or at minimum referenced) for full traceability.
    source_file_storage_ref                 TEXT,
    -- FR-STA-002
    status                                     TEXT NOT NULL DEFAULT 'UPLOADED'
                                                CHECK (status IN ('UPLOADED', 'PARSED', 'COMMITTED', 'ROLLED_BACK', 'VOIDED', 'DISCARDED', 'FAILED')),
    row_count                                     INTEGER,
    imported_row_count                               INTEGER,
    duplicate_row_count                                 INTEGER,
    error_row_count                                        INTEGER,
    -- FR-LIF-010/011: an unmodified batch may be hard-deleted on rollback; once any of its
    -- records has been modified, rollback must void instead. This flag is maintained by the
    -- service layer as records are edited/categorised/reconciled.
    contains_modified_records                                  BOOLEAN NOT NULL DEFAULT FALSE,
    uploaded_at                                                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    parsed_at                                                         TIMESTAMPTZ,
    committed_at                                                         TIMESTAMPTZ,
    rolled_back_at                                                          TIMESTAMPTZ,
    uploaded_by                                                                UUID REFERENCES app_user(id)
);
CREATE INDEX idx_import_batch_household ON import_batch(household_id);
CREATE INDEX idx_import_batch_account ON import_batch(account_id);

-- Now that import_batch exists, wire up the deferred FK from V10.
ALTER TABLE transaction ADD CONSTRAINT fk_transaction_import_batch FOREIGN KEY (import_batch_id) REFERENCES import_batch(id);

-- FR-IMP-012/025: an import with unparseable rows still imports the valid rows; rejected rows
-- and unmapped columns are retained rather than discarded, so a later template revision or a
-- corrected re-import can use them without re-uploading the source file.
CREATE TABLE import_row_raw (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    import_batch_id       UUID NOT NULL REFERENCES import_batch(id),
    row_number                INTEGER NOT NULL,
    raw_data                     JSONB NOT NULL,
    parse_status                    TEXT NOT NULL CHECK (parse_status IN ('PARSED', 'DUPLICATE', 'ERROR')),
    error_message                      TEXT,
    resulting_transaction_id               UUID REFERENCES transaction(id),
    UNIQUE (import_batch_id, row_number)
);
CREATE INDEX idx_import_row_raw_batch ON import_row_raw(import_batch_id);
