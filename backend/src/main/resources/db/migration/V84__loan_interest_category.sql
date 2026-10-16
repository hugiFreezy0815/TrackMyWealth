-- =============================================================================================
-- V84: shipped category for loan and mortgage interest (US-10-02, #328)
-- =============================================================================================
-- The interest part of a loan payment is a real expense (FR-CF-006). CategorizationService gives
-- its EXPENSE row this category from the row's own shape - an EXPENSE that names a loan in
-- counterparty_account_id - in the same layer that puts a FEE in FEES (TRANSACTION_TYPE), so a
-- member's rule still wins and an override is never replaced.
-- =============================================================================================

INSERT INTO category (workspace_id, parent_category_id, code, name_en, name_de, is_system_default)
VALUES (
    NULL, NULL, 'LOAN_INTEREST', 'Loan & Mortgage Interest', 'Kredit- & Hypothekarzinsen', TRUE
);
