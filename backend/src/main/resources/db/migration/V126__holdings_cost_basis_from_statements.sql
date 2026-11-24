-- =============================================================================================
-- V126: read the cost basis from imported holdings statements (US-15-08, #589)
-- =============================================================================================
-- The holdings import (#273, V99/V100) never filled reported_cost_basis, so no imported position
-- had a gain. Two of the shipped templates print a purchase price per position:
--
-- 1. Swissquote "Portfolio-Wertentwicklung" PDF (PF-7, YUH-2): the <average price> between ISIN
--    and market price, in the section's currency ("Bewertung in <currency>"). Version 2 captures
--    it as the named group averagePrice. It is matched as any token, not only digits: a line
--    whose average price is not a number (e.g. a position transferred in without one) is still
--    read as a position, with a warning and no cost basis, rather than not matched at all.
--    The Yuh crypto line prints no purchase price and keeps its pattern: no cost basis.
-- 2. PostFinance E-Trading positions XLSX (PF-5): the column "Einstandskurs", in "Währung".
--    Version 2 maps it as averagePrice. The PF-4 cash XLS prints none and stays as it is.
--
-- cost basis = quantity x average price, in the position's currency (FR-PERF-018: never guessed;
-- V98: values as printed). The batch keeps it per position (import_holding_row) so the preview
-- shows what the commit stores in snapshot_holding.reported_cost_basis(_currency) (V125).
--
-- A statement imported before (version 1) left its positions without a cost basis. Importing it
-- again fills that gap: the commit completes the snapshot (US-07-31) with the part COST_BASIS,
-- setting the cost basis of each held position that has none - never overwriting one. The
-- holding records the batch that filled it (cost_basis_import_batch_id), so rolling that batch
-- back clears only what it filled.
--
-- Each new version is a copy with a fixed id of its own; version 1 stays, no longer current, for
-- the batches that used it (as V103). Fixed ids and RLS handling: see V75.
-- =============================================================================================

ALTER TABLE import_holding_row
ADD COLUMN reported_cost_basis NUMERIC(20, 4);

ALTER TABLE import_holding_row
ADD CONSTRAINT chk_import_holding_row_cost_basis CHECK (
    reported_cost_basis IS NULL
    OR (kind = 'POSITION' AND reported_cost_basis >= 0)
);

COMMENT ON COLUMN import_holding_row.reported_cost_basis IS
'A position''s total cost in its currency, as the statement prints it; NULL when it prints '
'none or it is unreadable (US-15-08).';

ALTER TABLE snapshot_holding
ADD COLUMN cost_basis_import_batch_id UUID REFERENCES import_batch (id);

-- Only a cost basis that exists was filled by a batch.
ALTER TABLE snapshot_holding
ADD CONSTRAINT chk_snapshot_holding_cost_basis_import_batch CHECK (
    cost_basis_import_batch_id IS NULL OR reported_cost_basis IS NOT NULL
);

CREATE INDEX idx_snapshot_holding_cost_basis_import_batch
ON snapshot_holding (cost_basis_import_batch_id)
WHERE cost_basis_import_batch_id IS NOT NULL;

COMMENT ON COLUMN snapshot_holding.cost_basis_import_batch_id IS
'The batch whose statement filled this held position''s empty cost basis afterwards; NULL when '
'the cost basis came with the holding or by hand (US-15-08).';

ALTER TABLE import_template NO FORCE ROW LEVEL SECURITY;

-- uq_import_template_family_current allows one current version per family.
UPDATE import_template
SET is_current = FALSE
WHERE id IN (
    '003878c0-995e-4801-96d3-3e431c5095a2'::UUID,
    '41aff7f6-3fc8-4206-ae61-dc4bf3fdad53'::UUID
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
    statement_balance_rule,
    savings_pocket_pattern,
    holdings_rule
)
SELECT
    CASE t.id
        WHEN '003878c0-995e-4801-96d3-3e431c5095a2'::UUID
            THEN '24803064-3f64-4856-b79a-a2b33e4c37c2'::UUID
        ELSE 'cd83acd5-0e83-4433-bda7-4813133a6582'::UUID
    END AS id,
    t.template_family_id,
    t.institution_catalogue_id,
    t.workspace_id,
    t.name,
    t.template_class,
    '2' AS template_version,
    t.effective_from,
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
    t.type_mapping,
    t.description_type_patterns,
    t.account_identification_strategy,
    t.header_columns,
    t.header_fingerprint,
    t.is_system_provided,
    t.fixture_reference,
    t.file_format,
    t.pdf_layout,
    t.row_filter,
    t.account_identifier_rule,
    t.statement_balance_rule,
    t.savings_pocket_pattern,
    CASE t.id
        WHEN '003878c0-995e-4801-96d3-3e431c5095a2'::UUID
            THEN
                jsonb_set(
                    t.holdings_rule,
                    '{positionPatterns,0}',
                    to_jsonb(
                        '^(?P<quantity>[0-9][0-9''.]*) (?P<name>.+) '
                        || '(?P<isin>[A-Z]{2}[A-Z0-9]{9}[0-9]) (?P<averagePrice>\S+) '
                        || '(?P<price>[0-9''.]+) [0-9]{2}[.][0-9]{2}[.][0-9]{4} '
                        || '(?P<value>[0-9''.]+)(?: [0-9''.]+)? -?[0-9.]+$'
                    )
                )
        ELSE jsonb_set(t.holdings_rule, '{columns,averagePrice}', '"Einstandskurs"'::JSONB)
    END AS holdings_rule
FROM import_template AS t
WHERE t.id IN (
    '003878c0-995e-4801-96d3-3e431c5095a2'::UUID,
    '41aff7f6-3fc8-4206-ae61-dc4bf3fdad53'::UUID
);

ALTER TABLE import_template FORCE ROW LEVEL SECURITY;
