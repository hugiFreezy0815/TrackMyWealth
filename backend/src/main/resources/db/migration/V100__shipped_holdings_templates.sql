-- =============================================================================================
-- V100: shipped holdings statement templates (US-07-13, #273)
-- =============================================================================================
-- docs/architecture/import-source-analysis.md, sections 3.6, 3.7 and 6.6.1:
--
-- 1. Swissquote "Portfolio-Wertentwicklung" PDF (PF-7, YUH-2): PostFinance E-Trading and Yuh
--    share it, so one template serves both and names no institution (two would both match every
--    statement, and neither would be picked on its own). The statement's date is the first line
--    "Portfolio-Wertentwicklung per dd.MM.yyyy". A table header "... Bewertung in <currency>"
--    starts each section: its first currency is the one the section's prices and values are in.
--    A position line is "<quantity> <name> <ISIN> <average price> <market price> <price date>
--    <value> [<value in CHF>] <percent>"; a crypto position (Yuh) "<symbol> Position in <symbol>
--    <quantity> <rate> <value in CHF> <percent>", resolved by the workspace's ticker. Cash is one
--    "<currency> <reference> Kontokorrent ..." line per currency.
-- 2. PostFinance E-Trading positions XLSX (PF-5): tickers only, no ISIN; group headings and
--    subtotals have no quantity and are passed over.
-- 3. PostFinance E-Trading cash XLS (PF-4): one row per currency with its account balance; the
--    "Gesamt CHF" total names no currency code and is passed over.
--
-- A spreadsheet states no date: the member enters it at upload. Its header fingerprint is that of
-- the export's header row. Fixed ids and RLS handling: see V75.
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
    holdings_rule
)
VALUES (
    '003878c0-995e-4801-96d3-3e431c5095a2'::UUID,
    '003878c0-995e-4801-96d3-3e431c5095a2'::UUID,
    NULL,
    NULL,
    'Swissquote Portfolio-Wertentwicklung PDF (PostFinance E-Trading, Yuh)',
    'SNAPSHOT',
    '1',
    DATE '2026-10-19',
    ',',
    'UTF-8',
    '.',
    '''',
    'dd.MM.yyyy',
    0,
    0,
    0,
    'SINGLE_SIGNED_COLUMN',
    'FROM_ACCOUNT',
    NULL,
    '{}'::JSONB,
    '{}'::JSONB,
    'USER_SELECTED',
    NULL,
    NULL,
    TRUE,
    'ShippedTemplateFixtures.swissquoteHoldings',
    'PDF_TEXT',
    '{
        "documentMarker": "Portfolio-Wertentwicklung per",
        "datePattern": "^Portfolio-Wertentwicklung per (?P<date>[0-9]{2}[.][0-9]{2}[.][0-9]{4})$",
        "sectionPattern": "Bewertung in (?P<currency>[A-Z]{3})",
        "positionPatterns": [
            "^(?P<quantity>[0-9][0-9''.]*) (?P<name>.+) (?P<isin>[A-Z]{2}[A-Z0-9]{9}[0-9]) [0-9''.]+ (?P<price>[0-9''.]+) [0-9]{2}[.][0-9]{2}[.][0-9]{4} (?P<value>[0-9''.]+)(?: [0-9''.]+)? -?[0-9.]+$",
            "^(?P<symbol>[A-Z0-9]{2,10}) Position in [A-Z0-9]{2,10} (?P<quantity>[0-9''.]+) (?P<price>[0-9''.]+) (?P<value>[0-9''.]+) -?[0-9.]+$"
        ],
        "cashPattern": "^(?P<currency>[A-Z]{3}) [0-9]+ Kontokorrent (?:[(][A-Z]{3}[)]|[A-Z]{3}) (?P<amount>-?[0-9''.]+) [0-9.]+ -?[0-9''.]+ -?[0-9.]+$"
    }'::JSONB
),
(
    '41aff7f6-3fc8-4206-ae61-dc4bf3fdad53'::UUID,
    '41aff7f6-3fc8-4206-ae61-dc4bf3fdad53'::UUID,
    (
        SELECT catalogue.id
        FROM institution_catalogue AS catalogue
        WHERE catalogue.name = 'PostFinance' AND catalogue.country = 'CH'
    ),
    NULL,
    'PostFinance E-Trading Positionen XLSX',
    'SNAPSHOT',
    '1',
    DATE '2026-10-19',
    ',',
    'UTF-8',
    '.',
    '''',
    'dd.MM.yyyy',
    0,
    0,
    0,
    'SINGLE_SIGNED_COLUMN',
    'FROM_ACCOUNT',
    NULL,
    '{}'::JSONB,
    '{}'::JSONB,
    'USER_SELECTED',
    '[
        "", "Symbol", "Anzahl", "Einstandskurs", "Totalwert", "Tagesveränderung",
        "Diff. Vortag %", "Preis", "Währung", "G&V Nominalwert CHF", "G&V % CHF",
        "Totalwert CHF", "Positionen %"
    ]'::JSONB,
    'de0a349956e86a13162992fc2c76e4b6bf278aaad9ba06107f77b08de7ed7e0b',
    TRUE,
    'ShippedTemplateFixtures.postfinancePositionsWorkbook',
    'SPREADSHEET',
    '{
        "columns": {
            "symbol": "Symbol",
            "quantity": "Anzahl",
            "price": "Preis",
            "value": "Totalwert",
            "currency": "Währung"
        }
    }'::JSONB
),
(
    '1dcaf9a1-3aab-4b56-8e44-1a252821ca23'::UUID,
    '1dcaf9a1-3aab-4b56-8e44-1a252821ca23'::UUID,
    (
        SELECT catalogue.id
        FROM institution_catalogue AS catalogue
        WHERE catalogue.name = 'PostFinance' AND catalogue.country = 'CH'
    ),
    NULL,
    'PostFinance E-Trading Liquidität XLS',
    'SNAPSHOT',
    '1',
    DATE '2026-10-19',
    ',',
    'UTF-8',
    '.',
    '''',
    'dd.MM.yyyy',
    0,
    0,
    0,
    'SINGLE_SIGNED_COLUMN',
    'FROM_ACCOUNT',
    NULL,
    '{}'::JSONB,
    '{}'::JSONB,
    'USER_SELECTED',
    '[
        "Währung", "Kurs", "Kontosaldo", "Positionswert", "Totalwert", "Bewertung CHF",
        "Portfolio %"
    ]'::JSONB,
    '236c15b92d1cee4d2d2d93bfd8c96998f015f579094a1a2b1af65ccf73d683b5',
    TRUE,
    'ShippedTemplateFixtures.postfinanceCashWorkbook',
    'SPREADSHEET',
    '{
        "columns": {
            "currency": "Währung",
            "cashAmount": "Kontosaldo"
        }
    }'::JSONB
);

-- Every workspace can read shipped templates, but cannot insert/update/delete them (V65).
ALTER TABLE import_template FORCE ROW LEVEL SECURITY;
