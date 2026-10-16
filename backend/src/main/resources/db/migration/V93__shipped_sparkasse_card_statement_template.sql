-- =============================================================================================
-- V93: shipped template for the Sparkasse credit-card statement PDF (US-07-25, #339)
-- =============================================================================================
-- The monthly Mastercard statement as a PDF with a text layer (docs/architecture/
-- import-source-analysis.md 6.8). PO decisions (#339):
--
-- - Booking line: purchase date (Beleg), booking date (Buchung), text, amount and the sign printed
--   after it, "-" for a purchase, "+" for a payment received. The booking date is the second date;
--   the purchase date stays in the raw data, as in the CSV template. The row pattern puts a "-"
--   amount in the Soll cell and a "+" amount in the Haben cell (SEPARATE_DEBIT_CREDIT), so on a
--   card account a row is a purchase or a settlement by its sign (US-07-18).
-- - Foreign purchases are not part of this template.
-- - Balances: the previous balance on a dated "Saldo ... Abrechnung" line, the new balance on a
--   "Neuer Saldo" line without a date, which takes the statement's period end ("Ihre Abrechnung
--   vom ... bis <date>"); both signed by the sign after the amount ("-": owed). They reconcile the
--   bookings (US-07-12).
-- - Account: the masked card number on the statement's title line ("Mastercard-Nummer:"),
--   matched like the CSV export's (MASKED_CARD, US-07-10).
--
-- A new family; its id is fixed, so later versions and tests can name it. RLS handling: see V75.
-- =============================================================================================

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
VALUES
(
    '8f7e1b44-deb6-4e30-81d8-9a96d63d417c'::UUID,
    '8f7e1b44-deb6-4e30-81d8-9a96d63d417c'::UUID,
    (
        SELECT catalogue.id
        FROM institution_catalogue AS catalogue
        WHERE catalogue.name = 'Sparkasse' AND catalogue.country = 'DE'
    ),
    NULL,
    'Sparkasse Kreditkarte Abrechnung PDF',
    'CASH_TRANSACTIONS',
    '1',
    DATE '2026-10-16',
    ';',
    'UTF-8',
    ',',
    '.',
    'dd.MM.yy',
    0,
    0,
    0,
    'SEPARATE_DEBIT_CREDIT',
    'FIXED',
    'EUR',
    '{
        "bookingDate": "Buchung",
        "description": "Verwendungszweck",
        "debitAmount": "Soll",
        "creditAmount": "Haben"
    }'::JSONB,
    '{}'::JSONB,
    '{}'::JSONB,
    'PREAMBLE_LINE',
    '["Beleg", "Buchung", "Verwendungszweck", "Soll", "Haben"]'::JSONB,
    NULL,
    TRUE,
    'ShippedTemplateFixtures.sparkasseCardStatement',
    'PDF_TEXT',
    '{
        "columns": ["Beleg", "Buchung", "Verwendungszweck", "Soll", "Haben"],
        "rowPattern": "^([0-9]{2}[.][0-9]{2}[.][0-9]{2})[ ]+([0-9]{2}[.][0-9]{2}[.][0-9]{2})[ ]+(.+?)[ ]+(?:([0-9.]*[0-9],[0-9]{2})[ ]*-|([0-9.]*[0-9],[0-9]{2})[ ]*[+])$",
        "documentMarker": "Mastercard-Nummer:",
        "recordStartPattern": "^[0-9]{2}[.][0-9]{2}[.][0-9]{2}[ ]+[0-9]{2}[.][0-9]{2}[.][0-9]{2}[ ]",
        "headerLabels": []
    }'::JSONB,
    NULL,
    '{
        "kind": "MASKED_CARD",
        "label": "Ihre Abrechnung vom",
        "pattern": "Mastercard-Nummer:[ ]*([0-9][0-9X ]*[0-9])"
    }'::JSONB,
    '{
        "pattern": "^(?:(?P<date>[0-9]{2}[.][0-9]{2}[.][0-9]{2})[ ]+Saldo[ ].*Abrechnung|Neuer Saldo)[ ]+(?P<amount>[0-9.]*[0-9],[0-9]{2})[ ]*(?P<sign>[+-])$",
        "statementDatePattern": "Abrechnung vom [0-9.]+[ ]+bis[ ]+(?P<date>[0-9]{2}[.][0-9]{2}[.][0-9]{4})",
        "statementDateFormat": "dd.MM.yyyy"
    }'::JSONB
);

-- Every workspace can read shipped templates, but cannot insert/update/delete them (V65).
ALTER TABLE import_template FORCE ROW LEVEL SECURITY;
