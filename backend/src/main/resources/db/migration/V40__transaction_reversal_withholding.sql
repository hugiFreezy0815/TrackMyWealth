-- =============================================================================================
-- V40: a void's reversing row may carry a negated withholding tax (US-07-02, FR-LIF-002)
-- =============================================================================================
-- V36's transaction_withholding_reconciles required tax_withheld_amount >= 0 on every row. A
-- reversing row negates gross, tax and net alike (net = gross - tax still holds), so voiding a
-- dividend with withholding tax failed the check and the void rolled back. The magnitude rule now
-- exempts a reversing row (replaces_transaction_id set), as V36's sign rules already do; the
-- identity still applies to every row.
-- =============================================================================================

ALTER TABLE transaction
DROP CONSTRAINT transaction_withholding_reconciles,
ADD CONSTRAINT transaction_withholding_reconciles CHECK (
    (gross_amount IS NULL) = (tax_withheld_amount IS NULL)
    AND (
        gross_amount IS NULL
        OR (
            (replaces_transaction_id IS NOT NULL OR tax_withheld_amount >= 0)
            AND net_amount = gross_amount - tax_withheld_amount
        )
    )
);
