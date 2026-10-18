-- =============================================================================================
-- V96: The shipped statement PDF templates name their account (US-07-20, #323)
-- =============================================================================================
-- Since #339 a PDF template's PREAMBLE_LINE rule searches every text line of the statement; the
-- parser takes the first line that starts with the label and holds a valid identifier. So:
--
-- 1. Sparkasse loan statement (family fe678117-..., version 3): the IBAN after the loan number,
--    "Darlehen <number>, DE.. .... .... .... .... ..".
-- 2. Yuh statement (family a2820365-..., version 2): "IBAN : CH.. .... .... .... .... .".
-- 3. LBS building-savings statement (family 0ac5fd45-..., version 2): the contract number,
--    "Bausparvertrag 1 234 567 890" (CONTRACT_NUMBER, V95).
--
-- Each is a new version of its family (V64): a copy of the current version with a fixed id of
-- its own and the rule; the previous version stays, no longer current, for the batches that used
-- it. A statement whose text names no identifier imports into the account the member picked
-- (ImportBatchService). Fixed ids and RLS handling: see V75.
-- =============================================================================================

ALTER TABLE import_template NO FORCE ROW LEVEL SECURITY;

-- uq_import_template_family_current allows one current version per family.
UPDATE import_template
SET is_current = FALSE
WHERE id IN (
    '72805b69-3fa5-4922-b02a-af25e48dbd29'::UUID,
    'a2820365-0b08-4474-bdb9-f2c419a91a25'::UUID,
    '0ac5fd45-d7f5-4c7b-895d-cc8233d07e7b'::UUID
);

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
    new_version.id,
    t.template_family_id,
    t.institution_catalogue_id,
    t.workspace_id,
    t.name,
    t.template_class,
    new_version.template_version,
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
    t.column_mapping,
    t.type_mapping,
    t.description_type_patterns,
    'PREAMBLE_LINE' AS account_identification_strategy,
    t.header_columns,
    t.header_fingerprint,
    t.is_system_provided,
    t.fixture_reference,
    t.file_format,
    t.pdf_layout,
    t.row_filter,
    new_version.account_identifier_rule,
    t.statement_balance_rule
FROM import_template AS t
INNER JOIN (
    VALUES
    (
        '72805b69-3fa5-4922-b02a-af25e48dbd29'::UUID,
        'db261cc9-5ae3-4eef-b8b4-8529a243abcf'::UUID,
        '3',
        '{"kind": "IBAN", "label": "Darlehen",'
        ' "pattern": "(?:[0-9]+,[ ]*)?(DE[0-9]{2}(?:[ ]?[0-9]{4}){4}[ ]?[0-9]{2})"}'::JSONB
    ),
    (
        'a2820365-0b08-4474-bdb9-f2c419a91a25'::UUID,
        'a82dbfd8-abeb-4fad-af4e-0f9070b3111e'::UUID,
        '2',
        '{"kind": "IBAN", "label": "IBAN",'
        ' "pattern": "(CH[0-9]{2}(?:[ ]?[0-9]{4}){4}[ ]?[0-9])"}'::JSONB
    ),
    (
        '0ac5fd45-d7f5-4c7b-895d-cc8233d07e7b'::UUID,
        'd2971d6e-e091-48c5-96dd-d59c45c8dbd4'::UUID,
        '2',
        '{"kind": "CONTRACT_NUMBER", "label": "Bausparvertrag",'
        ' "pattern": "([0-9][ ]+[0-9]{3}[ ]+[0-9]{3}[ ]+[0-9]{3})"}'::JSONB
    )
) AS new_version (previous_id, id, template_version, account_identifier_rule)
    ON t.id = new_version.previous_id;

ALTER TABLE import_template FORCE ROW LEVEL SECURITY;
