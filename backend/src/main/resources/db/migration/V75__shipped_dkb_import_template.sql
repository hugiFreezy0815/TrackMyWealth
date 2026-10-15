-- US-07-15 (#275): the first shipped bank template, for DKB Girokonto CSV (DKB-1 in
-- docs/architecture/import-source-analysis.md, section 3.1).
-- The reference-package importer (EPIC 32) is not available yet; Flyway ships this baseline.
--
-- The id is a fixed literal, not gen_random_uuid(): every installation then knows this template
-- by the same id, so a later reference package can address it (a new version of its family).
-- The institution is looked up by a scalar subquery, so a missing catalogue entry leaves the
-- template without an institution instead of silently inserting no template at all.
--
-- V20/V65 FORCE RLS on import_template, and its insert policy requires the caller's workspace, so
-- a shared row (workspace_id IS NULL) can only be written by a role that bypasses RLS. Today the
-- migration role is a superuser; the planned migration-owner role (database-schema.md, "Production
-- hardening") is the table owner without BYPASSRLS, which only NO FORCE lets through. Both ALTERs
-- run in this migration's transaction, so no other session ever sees the table unforced.
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
VALUES (
    '752d1bc3-1951-408f-9d08-fbe4a1f1d9c5'::UUID,
    '752d1bc3-1951-408f-9d08-fbe4a1f1d9c5'::UUID,
    (
        SELECT catalogue.id
        FROM institution_catalogue AS catalogue
        WHERE catalogue.name = 'DKB' AND catalogue.country = 'DE'
    ),
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
);

-- Every workspace can read shipped templates, but cannot insert/update/delete them (V65).
ALTER TABLE import_template FORCE ROW LEVEL SECURITY;
