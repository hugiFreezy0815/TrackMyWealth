-- US-07-15 (#275): shipped template for PostFinance account movements CSV (PF-1/PF-2 in
-- docs/architecture/import-source-analysis.md, section 3.2), in CHF.
--
-- Six physical preamble lines (period, category, account, currency, an empty line) in Excel's
-- ="..." form, then the header, an empty record and the bookings; two trailing records (a
-- disclaimer). The currency is part of the amount columns' names, so this template reads CHF
-- accounts; a EUR account's export names other columns and needs a template of its own.
-- Credit and debit are separate columns and the debit is already negative: the column, not the
-- written sign, decides the direction (SEPARATE_DEBIT_CREDIT).
-- The file states no balance and no booking id; duplicates are found by date, amount and
-- currency (US-07-17). The account is identified by the IBAN on the "Konto:" line; its pattern
-- reads the IBAN out of the ="..." wrapper.
-- Fixed id and RLS handling: see V75.
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
    account_identifier_rule
)
VALUES (
    '8d1b8ef6-882f-46d9-87e1-a17278f8bfa3'::UUID,
    '8d1b8ef6-882f-46d9-87e1-a17278f8bfa3'::UUID,
    (
        SELECT catalogue.id
        FROM institution_catalogue AS catalogue
        WHERE catalogue.name = 'PostFinance' AND catalogue.country = 'CH'
    ),
    NULL,
    'PostFinance Kontobewegungen CSV (CHF)',
    'CASH_TRANSACTIONS',
    '1',
    DATE '2026-10-15',
    ';',
    'UTF-8',
    '.',
    '''',
    'dd.MM.yyyy',
    0,
    6,
    2,
    'SEPARATE_DEBIT_CREDIT',
    'FIXED',
    'CHF',
    '{
        "bookingDate": "Datum",
        "creditAmount": "Gutschrift in CHF",
        "debitAmount": "Lastschrift in CHF",
        "description": "Avisierungstext"
    }'::JSONB,
    '{}'::JSONB,
    'PREAMBLE_LINE',
    '[
        "Datum", "Bewegungstyp", "Avisierungstext", "Gutschrift in CHF",
        "Lastschrift in CHF", "Label", "Kategorie"
    ]'::JSONB,
    'e37ac6f48da606f736d43afd1fcc1b958d461026a14cfa6b49a63e1a0d3b61a9',
    TRUE,
    'import/postfinance-account-synthetic.csv',
    'CSV',
    '{"kind": "IBAN", "label": "Konto", "pattern": "([A-Z]{2}[0-9]{2}[A-Z0-9 ]+)"}'::JSONB
);

-- Every workspace can read shipped templates, but cannot insert/update/delete them (V65).
ALTER TABLE import_template FORCE ROW LEVEL SECURITY;
