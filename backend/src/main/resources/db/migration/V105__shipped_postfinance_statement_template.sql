-- =============================================================================================
-- V105: shipped template for the PostFinance account statement PDF (US-07-23, #326)
-- =============================================================================================
-- PF-9 and PF-10 in docs/architecture/import-source-analysis.md: the "Privatkonto" and the savings
-- account statement share one layout, for CHF and EUR accounts alike. The table is "Datum | Text |
-- Gutschrift | Lastschrift | Valuta | Saldo"; amounts are unsigned, the column decides the
-- direction, and a space groups the thousands ("1 234.56"). Its column names carry no currency:
-- the statement's currency ends either the IBAN line or, in an older layout, the account number
-- line ("IBAN CH.. .... .... .... .... . EUR", "Konto 12-34567-8 EUR"), read as the section's
-- currency of every booking. On later pages both lines repeat without it and start no section.
--
-- PostFinance prints a day's date only at its first booking, and the running balance only at
-- some: a booking line is a dated line or one whose amount is followed by its value date. Every
-- booking's ledger date is its value date ("Valuta"): the date the PostFinance CSV export (V76)
-- calls "Datum" - checked on the owner's statement and CSV of the same account, 36 of 36 shared
-- bookings - so a booking imported from both is found a duplicate (PO rule, #363). A booking
-- without a balance is checked by the next one that states it. A long booking text runs on under
-- the amount columns; words at word spacing stay text (pdf_layout.flowColumn, #326), so only a
-- word standing apart is an amount. "Kontostand" lines are the opening
-- and closing balance; the "Total" line ends the last booking's text. The statement names its
-- account by IBAN, and its closing balance is the statement balance (US-07-12).
-- Fixed id and RLS handling: see V75.
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
    account_identification_strategy,
    header_columns,
    header_fingerprint,
    is_system_provided,
    fixture_reference,
    file_format,
    pdf_layout,
    account_identifier_rule,
    statement_balance_rule
)
VALUES (
    '35cd132b-78f1-4480-93a7-e59e0fd18f9f'::UUID,
    '35cd132b-78f1-4480-93a7-e59e0fd18f9f'::UUID,
    (
        SELECT catalogue.id
        FROM institution_catalogue AS catalogue
        WHERE catalogue.name = 'PostFinance' AND catalogue.country = 'CH'
    ),
    NULL,
    'PostFinance Kontoauszug PDF',
    'CASH_TRANSACTIONS',
    '1',
    DATE '2026-10-19',
    ';',
    'UTF-8',
    '.',
    ' ',
    'dd.MM.yy',
    0,
    0,
    0,
    'SEPARATE_DEBIT_CREDIT',
    'PER_ROW',
    NULL,
    '{
        "bookingDate": "Valuta",
        "debitAmount": "Lastschrift",
        "creditAmount": "Gutschrift",
        "currency": "Waehrung",
        "description": "Text"
    }'::JSONB,
    '{}'::JSONB,
    'PREAMBLE_LINE',
    '["Zeile", "Datum", "Text", "Gutschrift", "Lastschrift", "Valuta", "Saldo", "Waehrung"]'::JSONB,
    'a5575fbeddeca4d36850b0ff922c70839cad0236ff1ea12910a3742f2b1d2f56',
    TRUE,
    'ShippedTemplateFixtures.postFinanceStatement',
    'PDF_TEXT',
    '{
        "columns": ["Zeile"],
        "rowPattern": "(.*)",
        "documentMarker": "PostFinance",
        "recordStartPattern": "^[0-9]{2}[.][0-9]{2}[.][0-9]{2} |[0-9][.][0-9]{2} [0-9]{2}[.][0-9]{2}[.][0-9]{2}(?: |$)",
        "headerLabels": ["Datum", "Text", "Gutschrift", "Lastschrift", "Valuta", "Saldo"],
        "continuationColumn": "Text",
        "continuationEndPattern": "^Total ",
        "sectionPattern": "^(?:IBAN [A-Z]{2}[0-9]{2}[0-9 ]*[0-9]|[^ ]+ [0-9]{2}-[0-9]{4,6}-[0-9]) ([A-Z]{3})$",
        "sectionColumn": "Waehrung",
        "balanceLinePattern": "^[0-9]{2}[.][0-9]{2}[.][0-9]{2} Kontostand (-?[0-9]{1,3}(?: [0-9]{3})*[.][0-9]{2})$",
        "balanceColumn": "Saldo",
        "flowColumn": "Text"
    }'::JSONB,
    '{
        "kind": "IBAN",
        "label": "IBAN",
        "pattern": "(CH[0-9]{2}(?:[ ]?[0-9]{4}){4}[ ]?[0-9])"
    }'::JSONB,
    '{
        "pattern": "^(?P<date>[0-9]{2}[.][0-9]{2}[.][0-9]{2}) Kontostand (?P<amount>-?[0-9]{1,3}(?: [0-9]{3})*[.][0-9]{2})$"
    }'::JSONB
);

-- Every workspace can read shipped templates, but cannot insert/update/delete them (V65).
ALTER TABLE import_template FORCE ROW LEVEL SECURITY;
