-- US-07-15 (#275): shipped template for the Yuh account statement PDF (YUH-1 in
-- docs/architecture/import-source-analysis.md, section 6.8; Yuh offers no CSV export).
--
-- One section per currency ("Kontoauszug in CHF"), whose code is every booking's currency. The
-- amounts are unsigned: the BELASTUNG or GUTSCHRIFT column they sit under decides the direction,
-- and the running SALDO checks every booking's amount and sign against the balance before it.
-- "Saldo per", "Anfangsbestand" and "Schlussbilanz" lines are balances, never bookings. The
-- closing "Schlussbilanz" is not checked against SALDO: it can include the savings pocket, which
-- the running balance does not. Lines below a booking that start under INFORMATION (counterparty,
-- IBAN, trade details) continue its description.
-- REFERENZ is not mapped as the booking id: both legs of a currency exchange carry the same one,
-- so it would flag the second leg as a duplicate of the first.
-- The statement balance is each section's "Saldo per <date> <amount> <currency>" (US-07-12).
-- PDF templates select their account at upload: the text layer has no preamble lines to read.
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
    pdf_layout,
    statement_balance_rule
)
VALUES (
    'a2820365-0b08-4474-bdb9-f2c419a91a25'::UUID,
    'a2820365-0b08-4474-bdb9-f2c419a91a25'::UUID,
    (
        SELECT catalogue.id
        FROM institution_catalogue AS catalogue
        WHERE catalogue.name = 'Yuh' AND catalogue.country = 'CH'
    ),
    NULL,
    'Yuh Kontoauszug PDF',
    'CASH_TRANSACTIONS',
    '1',
    DATE '2026-10-15',
    ';',
    'UTF-8',
    '.',
    '''',
    'dd.MM.yyyy',
    0,
    0,
    0,
    'SEPARATE_DEBIT_CREDIT',
    'PER_ROW',
    NULL,
    '{
        "bookingDate": "DATUM",
        "valueDate": "VALUTA-DATUM",
        "debitAmount": "BELASTUNG",
        "creditAmount": "GUTSCHRIFT",
        "currency": "Waehrung",
        "description": "INFORMATION"
    }'::JSONB,
    '{}'::JSONB,
    'USER_SELECTED',
    '[
        "Zeile", "DATUM", "INFORMATION", "REFERENZ", "BELASTUNG", "GUTSCHRIFT",
        "VALUTA-DATUM", "SALDO", "Waehrung"
    ]'::JSONB,
    '784bc256082f6d800d711dbc120a5fe8482f69eec79edc6ebceceaf04663bf79',
    TRUE,
    'ShippedTemplateFixtures.yuhStatement',
    'PDF_TEXT',
    '{
        "columns": ["Zeile"],
        "rowPattern": "(.*)",
        "documentMarker": "Dein Kontoauszug",
        "recordStartPattern": "^[0-9]{2}[.][0-9]{2}[.][0-9]{4}",
        "headerLabels": [
            "DATUM", "INFORMATION", "REFERENZ", "BELASTUNG", "GUTSCHRIFT", "VALUTA-DATUM", "SALDO"
        ],
        "continuationColumn": "INFORMATION",
        "sectionPattern": "^Kontoauszug in ([A-Z]{3})$",
        "sectionColumn": "Waehrung",
        "balanceLinePattern": "(?:^Saldo per [0-9.]+|Anfangsbestand)[ ]+(-?[0-9''.]*[0-9][.][0-9]{2})|Schlussbilanz",
        "balanceColumn": "SALDO"
    }'::JSONB,
    '{
        "pattern": "^Saldo per (?P<date>[0-9]{2}[.][0-9]{2}[.][0-9]{4})[ ]+(?P<amount>-?[0-9''.]*[0-9][.][0-9]{2})[ ]+(?P<currency>[A-Z]{3})$"
    }'::JSONB
);

-- Every workspace can read shipped templates, but cannot insert/update/delete them (V65).
ALTER TABLE import_template FORCE ROW LEVEL SECURITY;
