-- US-07-15 (#275): the first shipped bank template, for DKB Girokonto CSV (DKB-1).
-- The reference-package importer (EPIC 32) is not available yet; Flyway ships this baseline.
-- V20/V65 FORCE RLS on import_template. A migration-only table owner needs this temporary,
-- transaction-scoped NO FORCE to insert a shared row; the final FORCE policy is restored before
-- the migration commits. ALTER TABLE takes an exclusive lock throughout.
ALTER TABLE import_template NO FORCE ROW LEVEL SECURITY;

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
    account_identification_strategy,
    header_columns,
    header_fingerprint,
    is_system_provided,
    fixture_reference,
    file_format,
    row_filter,
    account_identifier_rule,
    statement_balance_rule
)
SELECT
    seed.id,
    seed.id,
    catalogue.id,
    NULL,
    'DKB Girokonto CSV',
    'CASH_TRANSACTIONS',
    '1',
    DATE '2026-10-15',
    ';',
    'UTF-8',
    ',',
    '.',
    'dd.MM.yy',
    0,
    4,
    0,
    'SINGLE_SIGNED_COLUMN',
    'FIXED',
    'EUR',
    '{
        "bookingDate": "Buchungsdatum",
        "valueDate": "Wertstellung",
        "amount": "Betrag (€)",
        "description": "Verwendungszweck",
        "transactionType": "Umsatztyp"
    }'::JSONB,
    '{"Eingang": "INCOME", "Ausgang": "EXPENSE"}'::JSONB,
    'PREAMBLE_LINE',
    '[
        "Buchungsdatum", "Wertstellung", "Status", "Zahlungspflichtige*r",
        "Zahlungsempfänger*in", "Verwendungszweck", "Umsatztyp", "IBAN",
        "Betrag (€)", "Gläubiger-ID", "Mandatsreferenz", "Kundenreferenz"
    ]'::JSONB,
    '29cdfacca6ab88a21792bab7913310c9fde6c1c34459e7e9e34b91026ed8d9e4',
    TRUE,
    'import/dkb-girokonto-synthetic.csv',
    'CSV',
    '{"column": "Status", "acceptedValues": ["Gebucht"]}'::JSONB,
    '{"kind": "IBAN", "label": "Girokonto"}'::JSONB,
    '{
        "pattern": "Kontostand vom (?P<date>[0-9]{2}[.][0-9]{2}[.][0-9]{4}):[ ]+(?P<amount>[0-9.,-]+)",
        "dateFormat": "dd.MM.yyyy"
    }'::JSONB
FROM (SELECT gen_random_uuid() AS id) AS seed
CROSS JOIN (
    SELECT id FROM institution_catalogue WHERE name = 'DKB' AND country = 'DE'
) AS catalogue;

-- Every workspace can read shipped templates, but cannot insert/update/delete them.
ALTER TABLE import_template FORCE ROW LEVEL SECURITY;
