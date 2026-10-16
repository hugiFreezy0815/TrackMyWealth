-- #323: account identification from all three shipped PDF statement families.
-- PDF PREAMBLE_LINE searches every text line (#339); copied templates keep the former versions.
ALTER TABLE import_template NO FORCE ROW LEVEL SECURITY;

UPDATE import_template SET is_current = FALSE WHERE id IN (
    '72805b69-3fa5-4922-b02a-af25e48dbd29'::UUID,
    'a2820365-0b08-4474-bdb9-f2c419a91a25'::UUID,
    '0ac5fd45-d7f5-4c7b-895d-cc8233d07e7b'::UUID
);

INSERT INTO import_template (
    id, template_family_id, institution_catalogue_id, workspace_id, name,
    template_class, template_version, effective_from, delimiter, encoding,
    decimal_separator, thousands_separator, date_format, header_row_index,
    preamble_row_count, trailing_summary_row_count, amount_representation,
    currency_mode, fixed_currency, column_mapping, type_mapping, description_type_patterns,
    account_identification_strategy, header_columns, header_fingerprint,
    is_system_provided, fixture_reference, file_format, pdf_layout, row_filter,
    account_identifier_rule, statement_balance_rule
)
SELECT
    new_version.id, old.template_family_id, old.institution_catalogue_id,
    old.workspace_id, old.name, old.template_class, new_version.version,
    DATE '2026-10-16', old.delimiter, old.encoding, old.decimal_separator,
    old.thousands_separator, old.date_format, old.header_row_index,
    old.preamble_row_count, old.trailing_summary_row_count, old.amount_representation,
    old.currency_mode, old.fixed_currency, old.column_mapping, old.type_mapping,
    old.description_type_patterns, 'PREAMBLE_LINE', old.header_columns,
    old.header_fingerprint, old.is_system_provided, old.fixture_reference, old.file_format,
    old.pdf_layout, old.row_filter, new_version.identifier_rule, old.statement_balance_rule
FROM import_template old
JOIN (
    VALUES
    (
        '72805b69-3fa5-4922-b02a-af25e48dbd29'::UUID,
        'db261cc9-5ae3-4eef-b8b4-8529a243abcf'::UUID,
        '3',
        '{"kind":"IBAN","label":"Darlehen","pattern":"(?:[0-9]+,[ ]*)?(DE[0-9A-Z ]{20,30})"}'::JSONB
    ),
    (
        'a2820365-0b08-4474-bdb9-f2c419a91a25'::UUID,
        'a82dbfd8-abeb-4fad-af4e-0f9070b3111e'::UUID,
        '2',
        '{"kind":"IBAN","label":"IBAN","pattern":"(CH[0-9A-Z ]{18,28})"}'::JSONB
    ),
    (
        '0ac5fd45-d7f5-4c7b-895d-cc8233d07e7b'::UUID,
        'd2971d6e-e091-48c5-96dd-d59c45c8dbd4'::UUID,
        '2',
        '{"kind":"CONTRACT_NUMBER","label":"Bausparvertrag","pattern":"([0-9]{1}[ ]+[0-9]{3}[ ]+[0-9]{3}[ ]+[0-9]{3})"}'::JSONB
    )
) AS new_version (previous_id, id, version, identifier_rule)
    ON old.id = new_version.previous_id;

ALTER TABLE import_template FORCE ROW LEVEL SECURITY;
