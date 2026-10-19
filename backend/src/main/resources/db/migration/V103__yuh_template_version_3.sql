-- =============================================================================================
-- V103: Yuh statement template, version 3: exchanges and savings pocket moves (US-07-22, #325)
-- =============================================================================================
-- Version 2 (V96) types every row by its sign, so both legs of a currency exchange counted as
-- income or spending, and so did a move into the savings pocket. Version 3:
--
-- 1. maps REFERENZ as the group reference: both legs of an exchange ("Währungsumtausch",
--    "Automatisierter Währungstausch") carry the same one, so the commit links the two as one
--    exchange (ImportTransferLinkService). It is still no booking id (see V78);
-- 2. reads a "Spareinlage" as a savings pocket move: a TRANSFER whose other leg the commit books
--    on the account's savings pocket (V101), creating the pocket the first time.
--
-- Buys and sells stay typed by their sign until the securities import links them (#274).
-- A copy of version 2 with a fixed id of its own; version 2 stays, no longer current, for the
-- batches that used it. Fixed ids and RLS handling: see V75.
-- =============================================================================================

ALTER TABLE import_template NO FORCE ROW LEVEL SECURITY;

-- uq_import_template_family_current allows one current version per family.
UPDATE import_template
SET is_current = FALSE
WHERE id = 'a82dbfd8-abeb-4fad-af4e-0f9070b3111e'::UUID;

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
    statement_balance_rule,
    savings_pocket_pattern
)
SELECT
    '2548b28a-0884-4aa9-b544-557446e0963c'::UUID AS id,
    t.template_family_id,
    t.institution_catalogue_id,
    t.workspace_id,
    t.name,
    t.template_class,
    '3' AS template_version,
    DATE '2026-10-19' AS effective_from,
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
    t.column_mapping || '{"groupReference": "REFERENZ"}'::JSONB AS column_mapping,
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
    t.statement_balance_rule,
    '^Spareinlage' AS savings_pocket_pattern
FROM import_template AS t
WHERE t.id = 'a82dbfd8-abeb-4fad-af4e-0f9070b3111e'::UUID;

ALTER TABLE import_template FORCE ROW LEVEL SECURITY;
