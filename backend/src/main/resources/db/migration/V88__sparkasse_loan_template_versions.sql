-- =============================================================================================
-- V88: Sparkasse templates, version 2: a loan statement's bookings are recorded (US-07-21, #324)
-- =============================================================================================
-- Since US-07-21 the ledger accepts what a loan statement books onto a loan or mortgage on import:
-- the instalment (DEBT_REPAYMENT, positive), interest (EXPENSE) and fees (FEE) charged onto it.
-- On such an account, a row neither the type mapping nor a description pattern types is an
-- EXPENSE when negative and a DEBT_REPAYMENT otherwise (ImportFileParserService), so:
--
-- 1. CSV-CAMT V8 (family d902ba3d-...): the current account's payment of the loan instalment,
--    "EINZUG RATE/ANNUITAET", is one leg of a transfer to the loan (TRANSFER), matched with the
--    loan's instalment by transfer detection; the loan's interest, "DARLEHENSZINSEN", an EXPENSE.
--    The loan's own instalment, "GUTSCHRIFT", goes by the loan's sign rule.
-- 2. Annual loan statement PDF (family fe678117-...): the statement fee, "Kontoauszugspreis", a
--    FEE by description; interest ("Darlehenszins") and instalments ("Darlehensleistung") go by
--    the loan's sign rule.
--
-- Each is a new version of its family (V64): a copy of version 1 with a fixed id of its own,
-- version '2' and these types; version 1 stays, no longer current, for the batches that used it.
-- Fixed ids and RLS handling: see V75.
-- =============================================================================================

ALTER TABLE import_template NO FORCE ROW LEVEL SECURITY;

-- uq_import_template_family_current allows one current version per family.
UPDATE import_template
SET is_current = FALSE
WHERE id IN (
    'd902ba3d-1344-4e55-8cc2-e2b0e0fe8bf5'::UUID,
    'fe678117-6fd0-4863-b271-b2a038ecb5cf'::UUID
);

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
SELECT
    version_two.id,
    t.template_family_id,
    t.institution_catalogue_id,
    t.workspace_id,
    t.name,
    t.template_class,
    '2' AS template_version,
    DATE '2026-10-16' AS effective_from,
    t.delimiter,
    t.encoding,
    t.decimal_separator,
    t.thousands_separator,
    t.date_format,
    t.header_row_index,
    t.preamble_row_count,
    t.trailing_summary_row_count,
    t.amount_representation,
    t.currency_mode,
    t.fixed_currency,
    t.column_mapping,
    version_two.type_mapping,
    version_two.description_type_patterns,
    t.account_identification_strategy,
    t.header_columns,
    t.header_fingerprint,
    t.is_system_provided,
    t.fixture_reference,
    t.file_format,
    t.pdf_layout,
    t.row_filter,
    t.account_identifier_rule,
    t.statement_balance_rule
FROM import_template AS t
INNER JOIN (
    VALUES
    (
        'd902ba3d-1344-4e55-8cc2-e2b0e0fe8bf5'::UUID,
        'df6b6640-6a37-49ce-b5fc-feb61c677971'::UUID,
        '{
            "ENTGELTABSCHLUSS": "FEE",
            "BARGELDAUSZAHLUNG": "WITHDRAWAL",
            "EINZUG RATE/ANNUITAET": "TRANSFER",
            "DARLEHENSZINSEN": "EXPENSE"
        }'::JSONB,
        '{}'::JSONB
    ),
    (
        'fe678117-6fd0-4863-b271-b2a038ecb5cf'::UUID,
        '72805b69-3fa5-4922-b02a-af25e48dbd29'::UUID,
        '{}'::JSONB,
        '{"FEE": "^Kontoauszugspreis"}'::JSONB
    )
) AS version_two (version_one_id, id, type_mapping, description_type_patterns)
    ON t.id = version_two.version_one_id;

-- Every workspace can read shipped templates, but cannot insert/update/delete them (V65).
ALTER TABLE import_template FORCE ROW LEVEL SECURITY;
