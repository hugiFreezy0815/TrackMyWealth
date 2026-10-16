-- =============================================================================================
-- V91: Sparkasse credit-card CSV, version 2: foreign purchases keep their original amount (#327)
-- =============================================================================================
-- Version 1 (V77) left "Originalbetrag" and "Originalwährung" in the raw data, so a foreign card
-- purchase was recorded only in EUR. Version 2 maps both: the ledger books such a purchase in its
-- original currency and derives the issuer rate from it and the billed EUR amount (US-09-04).
-- Domestic rows print 0,00 with an empty currency; since #327 the parser reads that as no original
-- amount, and reads an original amount's magnitude with the booked amount's sign. "Umrechnungskurs"
-- stays unmapped: the ledger derives the rate, and the printed one stays in the row's raw data.
--
-- A new version of the family (V64): a copy of version 1 with a fixed id of its own and version
-- '2'; version 1 stays, no longer current, for the batches that used it. Fixed ids and RLS
-- handling: see V75.
-- =============================================================================================

ALTER TABLE import_template NO FORCE ROW LEVEL SECURITY;

-- uq_import_template_family_current allows one current version per family.
UPDATE import_template
SET is_current = FALSE
WHERE id = '4a5b2ef3-9839-45c1-b173-fd9c33f57bd6'::UUID;

INSERT INTO import_template (
    id,
    template_family_id,
    institution_catalogue_id,
    workspace_id,
    name,
    template_class,
    template_version,
    effective_from,
    delimiter,
    encoding,
    decimal_separator,
    thousands_separator,
    date_format,
    header_row_index,
    preamble_row_count,
    trailing_summary_row_count,
    amount_representation,
    currency_mode,
    fixed_currency,
    column_mapping,
    type_mapping,
    description_type_patterns,
    account_identification_strategy,
    header_columns,
    header_fingerprint,
    is_system_provided,
    fixture_reference,
    file_format,
    pdf_layout,
    row_filter,
    account_identifier_rule,
    statement_balance_rule
)
SELECT
    version_two.id,
    t.template_family_id,
    t.institution_catalogue_id,
    t.workspace_id,
    t.name,
    t.template_class,
    '2' AS template_version,
    DATE '2026-10-16' AS effective_from,
    t.delimiter,
    t.encoding,
    t.decimal_separator,
    t.thousands_separator,
    t.date_format,
    t.header_row_index,
    t.preamble_row_count,
    t.trailing_summary_row_count,
    t.amount_representation,
    t.currency_mode,
    t.fixed_currency,
    t.column_mapping || version_two.original_columns AS column_mapping,
    t.type_mapping,
    t.description_type_patterns,
    t.account_identification_strategy,
    t.header_columns,
    t.header_fingerprint,
    t.is_system_provided,
    t.fixture_reference,
    t.file_format,
    t.pdf_layout,
    t.row_filter,
    t.account_identifier_rule,
    t.statement_balance_rule
FROM import_template AS t
INNER JOIN (
    VALUES
    (
        '4a5b2ef3-9839-45c1-b173-fd9c33f57bd6'::UUID,
        '394a7a1d-1b0a-4cf1-a63a-b0e92cd1d0a0'::UUID,
        '{"originalAmount": "Originalbetrag", "originalCurrency": "Originalwährung"}'::JSONB
    )
) AS version_two (version_one_id, id, original_columns)
    ON t.id = version_two.version_one_id;

-- Every workspace can read shipped templates, but cannot insert/update/delete them (V65).
ALTER TABLE import_template FORCE ROW LEVEL SECURITY;
