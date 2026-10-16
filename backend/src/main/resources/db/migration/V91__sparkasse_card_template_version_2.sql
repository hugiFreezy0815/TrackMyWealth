-- =============================================================================================
-- V91: Sparkasse credit card template, version 2: a foreign purchase's original amount (US-07-24)
-- =============================================================================================
-- Version 1 (V77) left Originalbetrag, Originalwährung and Umrechnungskurs in the raw data, so a
-- foreign purchase was recorded only in EUR. Version 2 maps the original amount and currency: on a
-- card account a purchase in another currency is then recorded in it, with the billed EUR amount,
-- and the ledger derives the rate (US-09-04, US-07-18). A purchase in euros carries 0,00 and no
-- currency there, which the parser reads as no original amount, so it imports as before.
-- Umrechnungskurs stays unmapped: the bank's rate is rounded and the ledger does not use it; it
-- stays in the row's source data. The parser takes the original amount's sign from the booked
-- amount, so it does not matter which way the bank prints it.
--
-- A new version of the family (V64), as in V88; version 1 stays, no longer current.
-- Fixed ids and RLS handling: see V75.
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
    '4fccd74c-48e2-4e96-aab3-37478b3e05be'::UUID AS id,
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
    t.column_mapping
    || '{"originalAmount": "Originalbetrag", "originalCurrency": "Originalwährung"}'::JSONB
        AS column_mapping,
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
WHERE t.id = '4a5b2ef3-9839-45c1-b173-fd9c33f57bd6'::UUID;

-- Every workspace can read shipped templates, but cannot insert/update/delete them (V65).
ALTER TABLE import_template FORCE ROW LEVEL SECURITY;
