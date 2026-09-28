-- =============================================================================================
-- V36: shape of investment rows on the ledger (US-07-01, BUY/SELL/DIVIDEND)
-- =============================================================================================
-- The ledger is append-only (trg_transaction_append_only), and a position is derived as the sum
-- of quantity over BUY and SELL (US-15-01). A malformed row can therefore only ever be voided,
-- never fixed, and a wrong-signed quantity silently corrupts every position computed from it.
-- TransactionService checks these rules for manual entry; the constraints below hold them for
-- every writer, including the imports of EPIC 07 and any future ingestion path.
--
-- Tolerance rules (a trade's amount against quantity x unitPrice) stay in the service: they are
-- about statement rounding, not about what a stored row may look like.
--
-- A reversing row of the void path (FR-LIF-002: same type, amounts negated, replaces_transaction_id
-- set) mirrors the signs of the row it reverses, so the sign rules exempt it; the shape rules
-- (which fields a type carries) apply to it as to any row.
--
-- The cash and card types are listed explicitly rather than as "everything that is not an
-- investment type": CORPORATE_ACTION (EPIC 14), VALUATION_ADJUSTMENT and gross/withheld interest
-- will legitimately carry some of these fields, and the story adding them widens the rule
-- deliberately instead of finding it already forbidden or silently permitted.
-- =============================================================================================

ALTER TABLE transaction
ADD CONSTRAINT transaction_trade_shape CHECK (
    transaction_type NOT IN ('BUY', 'SELL')
    OR (
        security_id IS NOT NULL
        AND quantity IS NOT NULL
        AND unit_price IS NOT NULL
        AND gross_amount IS NULL
        AND tax_withheld_amount IS NULL
        AND net_amount IS NULL
    )
),
ADD CONSTRAINT transaction_trade_quantity_sign CHECK (
    replaces_transaction_id IS NOT NULL
    OR (
        (transaction_type != 'BUY' OR quantity > 0)
        AND (transaction_type != 'SELL' OR quantity < 0)
        AND (transaction_type != 'BUY' OR amount < 0)
    )
),
ADD CONSTRAINT transaction_dividend_shape CHECK (
    transaction_type != 'DIVIDEND'
    OR (
        security_id IS NOT NULL
        AND trade_date IS NULL
        AND settlement_date IS NULL
        AND fee_amount IS NULL
        AND (net_amount IS NULL OR net_amount = amount)
    )
),
ADD CONSTRAINT transaction_dividend_sign CHECK (
    replaces_transaction_id IS NOT NULL
    OR transaction_type != 'DIVIDEND'
    OR (amount > 0 AND (quantity IS NULL OR quantity > 0))
),
ADD CONSTRAINT transaction_cash_and_card_carry_no_investment_fields CHECK (
    transaction_type NOT IN (
        'INCOME', 'EXPENSE', 'DEPOSIT', 'WITHDRAWAL', 'INTEREST', 'FEE', 'TAX', 'REFUND',
        'CREDIT_CARD_PURCHASE', 'SETTLEMENT'
    )
    OR (
        security_id IS NULL
        AND quantity IS NULL
        AND unit_price IS NULL
        AND fee_amount IS NULL
        AND trade_date IS NULL
        AND settlement_date IS NULL
        AND gross_amount IS NULL
        AND tax_withheld_amount IS NULL
        AND net_amount IS NULL
    )
),
ADD CONSTRAINT transaction_price_and_fee_are_magnitudes CHECK (
    (unit_price IS NULL OR unit_price > 0)
    AND (fee_amount IS NULL OR fee_amount >= 0)
),
ADD CONSTRAINT transaction_withholding_reconciles CHECK (
    (gross_amount IS NULL) = (tax_withheld_amount IS NULL)
    AND (
        gross_amount IS NULL
        OR (tax_withheld_amount >= 0 AND net_amount = gross_amount - tax_withheld_amount)
    )
),
ADD CONSTRAINT transaction_trade_dates_ordered CHECK (
    (trade_date IS NULL OR settlement_date IS NULL OR settlement_date >= trade_date)
    AND (trade_date IS NULL OR trade_date <= booking_date)
);
