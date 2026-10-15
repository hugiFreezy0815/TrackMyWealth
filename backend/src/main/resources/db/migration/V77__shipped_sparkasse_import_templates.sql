-- US-07-15 (#275): shipped templates for the Sparkasse (docs/architecture/import-source-analysis.md):
--
-- 1. Account transactions, "CSV-CAMT V8" (SPK-3, section 3.4): windows-1252, every cell quoted,
--    17 columns with an ASCII-transliterated header. The own IBAN is on every row (Auftragskonto),
--    the currency per row. The 18-column export of current and savings accounts (an extra
--    Kategorie column) maps the same columns, so detection offers this template for it too, as a
--    mapped-columns match. Only booked rows are imported (Info "Umsatz gebucht"); a pending one
--    would come back booked later, a duplicate. Buchungstext is mapped only where its sign is
--    unambiguous (a fee settlement, a cash withdrawal); every other code goes by the amount's
--    sign, since e.g. ABSCHLUSS is interest received or paid. No column is a unique booking id.
-- 2. Credit card transactions (SPK-5, section 3.5): the masked card number on every row identifies
--    the card. There is no type column: on a card account a row is a purchase or a settlement by
--    its sign (US-07-18). Original amount, currency and rate stay in the raw data: domestic rows
--    carry a zero rate, which the exchange rate column would reject.
-- 3. Annual loan statement PDF (SPK-6, section 6.8): signed amounts, "Kontostand am" opening and
--    closing balances (read as the statement balance, US-07-12), an optional "/ Wert:" value date.
--    The booking line start also accepts a date the text layer splits after its first digit, so
--    such a line becomes a visible row error instead of a silently missing booking. No header
--    labels: the amounts are signed, so the debit and credit columns tell nothing more.
--    Its bookings parse for the preview; recording them on a LOAN account needs the loan's own
--    transaction types (US-10-02), so today it is the statement's balances that import.
-- Fixed ids and RLS handling: see V75.
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
    row_filter,
    account_identifier_rule,
    statement_balance_rule
)
VALUES
(
    'd902ba3d-1344-4e55-8cc2-e2b0e0fe8bf5'::UUID,
    'd902ba3d-1344-4e55-8cc2-e2b0e0fe8bf5'::UUID,
    (
        SELECT catalogue.id
        FROM institution_catalogue AS catalogue
        WHERE catalogue.name = 'Sparkasse' AND catalogue.country = 'DE'
    ),
    NULL,
    'Sparkasse Umsätze CSV-CAMT V8',
    'CASH_TRANSACTIONS',
    '1',
    DATE '2026-10-15',
    ';',
    'windows-1252',
    ',',
    '.',
    'dd.MM.yy',
    0,
    0,
    0,
    'SINGLE_SIGNED_COLUMN',
    'PER_ROW',
    NULL,
    '{
        "bookingDate": "Buchungstag",
        "valueDate": "Valutadatum",
        "amount": "Betrag",
        "currency": "Waehrung",
        "description": "Verwendungszweck",
        "counterpartyName": "Beguenstigter/Zahlungspflichtiger",
        "transactionType": "Buchungstext"
    }'::JSONB,
    '{"ENTGELTABSCHLUSS": "FEE", "BARGELDAUSZAHLUNG": "WITHDRAWAL"}'::JSONB,
    'COLUMN',
    '[
        "Auftragskonto", "Buchungstag", "Valutadatum", "Buchungstext", "Verwendungszweck",
        "Glaeubiger ID", "Mandatsreferenz", "Kundenreferenz (End-to-End)", "Sammlerreferenz",
        "Lastschrift Ursprungsbetrag", "Auslagenersatz Ruecklastschrift",
        "Beguenstigter/Zahlungspflichtiger", "Kontonummer/IBAN", "BIC (SWIFT-Code)", "Betrag",
        "Waehrung", "Info"
    ]'::JSONB,
    '8734ed97908af9e7d970f3c54a3e2db876fdf64ba1dc16ffe944e9d29d680848',
    TRUE,
    'import/sparkasse-camt-synthetic.csv',
    'CSV',
    NULL,
    '{"column": "Info", "acceptedValues": ["Umsatz gebucht"]}'::JSONB,
    '{"kind": "IBAN", "column": "Auftragskonto"}'::JSONB,
    NULL
),
(
    '4a5b2ef3-9839-45c1-b173-fd9c33f57bd6'::UUID,
    '4a5b2ef3-9839-45c1-b173-fd9c33f57bd6'::UUID,
    (
        SELECT catalogue.id
        FROM institution_catalogue AS catalogue
        WHERE catalogue.name = 'Sparkasse' AND catalogue.country = 'DE'
    ),
    NULL,
    'Sparkasse Kreditkarte CSV',
    'CASH_TRANSACTIONS',
    '1',
    DATE '2026-10-15',
    ';',
    'windows-1252',
    ',',
    '.',
    'dd.MM.yy',
    0,
    0,
    0,
    'SINGLE_SIGNED_COLUMN',
    'PER_ROW',
    NULL,
    '{
        "bookingDate": "Buchungsdatum",
        "amount": "Buchungsbetrag",
        "currency": "Buchungswährung",
        "description": "Transaktionsbeschreibung",
        "notes": "Transaktionsbeschreibung Zusatz"
    }'::JSONB,
    '{}'::JSONB,
    'COLUMN',
    '[
        "Umsatz getätigt von", "Belegdatum", "Buchungsdatum", "Originalbetrag",
        "Originalwährung", "Umrechnungskurs", "Buchungsbetrag", "Buchungswährung",
        "Transaktionsbeschreibung", "Transaktionsbeschreibung Zusatz", "Buchungsreferenz",
        "Gebührenschlüssel", "Länderkennzeichen", "BAR-Entgelt+Buchungsreferenz",
        "AEE+Buchungsreferenz", "Abrechnungskennzeichen"
    ]'::JSONB,
    '092dc9d110b4ddca41b24065fbb45e3d06ac67b21191baead1e0c83b51a13537',
    TRUE,
    'import/sparkasse-card-synthetic.csv',
    'CSV',
    NULL,
    NULL,
    '{"kind": "MASKED_CARD", "column": "Umsatz getätigt von"}'::JSONB,
    NULL
),
(
    'fe678117-6fd0-4863-b271-b2a038ecb5cf'::UUID,
    'fe678117-6fd0-4863-b271-b2a038ecb5cf'::UUID,
    (
        SELECT catalogue.id
        FROM institution_catalogue AS catalogue
        WHERE catalogue.name = 'Sparkasse' AND catalogue.country = 'DE'
    ),
    NULL,
    'Sparkasse Darlehen Jahreskontoauszug PDF',
    'CASH_TRANSACTIONS',
    '1',
    DATE '2026-10-15',
    ';',
    'UTF-8',
    ',',
    '.',
    'dd.MM.yyyy',
    0,
    0,
    0,
    'SINGLE_SIGNED_COLUMN',
    'FIXED',
    'EUR',
    '{
        "bookingDate": "Datum",
        "valueDate": "Wert",
        "amount": "Betrag",
        "description": "Erläuterung"
    }'::JSONB,
    '{}'::JSONB,
    'USER_SELECTED',
    '["Datum", "Erläuterung", "Wert", "Betrag"]'::JSONB,
    NULL,
    TRUE,
    'ShippedTemplateFixtures.sparkasseLoanStatement',
    'PDF_TEXT',
    '{
        "columns": ["Datum", "Erläuterung", "Wert", "Betrag"],
        "rowPattern": "^([0-9][ ]?[0-9][.][0-9]{2}[.][0-9]{4})[ ]+(.+?)(?:[ ]+/[ ]+Wert:[ ]+([0-9]{2}[.][0-9]{2}[.][0-9]{4}))?[ ]+(-?[0-9.]*[0-9],[0-9]{2})$",
        "documentMarker": "Nachweis für Darlehen",
        "recordStartPattern": "^[0-9][ ]?[0-9][.][0-9]{2}[.][0-9]{4}[ ]",
        "headerLabels": [],
        "balanceLinePattern": "^Kontostand am [0-9.]+[ ]+(-?[0-9.]*[0-9],[0-9]{2})$"
    }'::JSONB,
    NULL,
    NULL,
    '{
        "pattern": "^Kontostand am (?P<date>[0-9]{2}[.][0-9]{2}[.][0-9]{4})[ ]+(?P<amount>-?[0-9.]*[0-9],[0-9]{2})$"
    }'::JSONB
);

-- Every workspace can read shipped templates, but cannot insert/update/delete them (V65).
ALTER TABLE import_template FORCE ROW LEVEL SECURITY;
