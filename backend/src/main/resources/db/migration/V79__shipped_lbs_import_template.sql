-- US-07-15 (#275): shipped template for the LBS Süd annual building-savings statement PDF (SPK-7 in
-- docs/architecture/import-source-analysis.md, section 6.8), and LBS Süd in the institution
-- catalogue, which had no entry for it (FR-INS-007).
--
-- Each booking line starts with its booking and value date. The amounts are unsigned: the
-- Belastungen or Gutschriften column they sit under decides the direction. The contract details
-- printed beside the bookings (tariff, contract sum, IBAN) sit under the "Tarif" label of the same
-- header line, so they never reach a booking's cells. Deposits and interest are typed; anything
-- else goes by its sign. "Saldovortrag" and "Ihr Bausparguthaben am" are balances, never bookings;
-- the latter, dated, is the statement balance (US-07-12). The statement prints no running balance,
-- so bookings are not checked against one.
-- Fixed id and RLS handling: see V75.
INSERT INTO institution_catalogue (name, country, institution_type, available_import_methods)
VALUES ('LBS Süd', 'DE', 'BANK', '[]'::JSONB);

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
    statement_balance_rule
)
VALUES (
    '0ac5fd45-d7f5-4c7b-895d-cc8233d07e7b'::UUID,
    '0ac5fd45-d7f5-4c7b-895d-cc8233d07e7b'::UUID,
    (
        SELECT catalogue.id
        FROM institution_catalogue AS catalogue
        WHERE catalogue.name = 'LBS Süd' AND catalogue.country = 'DE'
    ),
    NULL,
    'LBS Bausparvertrag Jahreskontoauszug PDF',
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
    'SEPARATE_DEBIT_CREDIT',
    'FIXED',
    'EUR',
    '{
        "bookingDate": "Buch.-",
        "valueDate": "Wert",
        "debitAmount": "Belastungen",
        "creditAmount": "Gutschriften",
        "description": "Buchungsvorgang",
        "transactionType": "Buchungsvorgang"
    }'::JSONB,
    '{"Einzahlung": "DEPOSIT"}'::JSONB,
    '{"INTEREST": "^Zinsen"}'::JSONB,
    'USER_SELECTED',
    '["Zeile", "Buch.-", "Wert", "Buchungsvorgang", "Belastungen", "Gutschriften", "Tarif"]'::JSONB,
    '14bf995c1f10600d6267072dd4e7921474b6330145394dd9673acfde47487368',
    TRUE,
    'ShippedTemplateFixtures.lbsStatement',
    'PDF_TEXT',
    '{
        "columns": ["Zeile"],
        "rowPattern": "(.*)",
        "documentMarker": "Ihr Bausparguthaben am",
        "recordStartPattern": "^[0-9]{2}[.][0-9]{2}[.][0-9]{4}[ ]+[0-9]{2}[.][0-9]{2}[.][0-9]{4}",
        "headerLabels": ["Buch.-", "Wert", "Buchungsvorgang", "Belastungen", "Gutschriften", "Tarif"],
        "balanceLinePattern": "^(?:Saldovortrag|Ihr Bausparguthaben am [0-9.]+)[ ]+([0-9.]*[0-9],[0-9]{2})"
    }'::JSONB,
    '{
        "pattern": "^Ihr Bausparguthaben am (?P<date>[0-9]{2}[.][0-9]{2}[.][0-9]{4})[ ]+(?P<amount>-?[0-9.]*[0-9],[0-9]{2})$"
    }'::JSONB
);

-- Every workspace can read shipped templates, but cannot insert/update/delete them (V65).
ALTER TABLE import_template FORCE ROW LEVEL SECURITY;
