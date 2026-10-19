-- =============================================================================================
-- V98: what a holdings statement reports for each position (US-07-13, #273)
-- =============================================================================================
-- A bank's positions statement prints, per position, its price per unit and its market value, in
-- the currency the position is quoted in (which may differ from the account's). They are kept as
-- printed, beside the quantity and the optional cost basis, and never converted or rounded here:
-- valuation reads market prices of its own (EPIC 15). All three are set together or not at all; a
-- holding entered by hand has none.
-- =============================================================================================

ALTER TABLE snapshot_holding
ADD COLUMN reported_price NUMERIC(20, 10),
ADD COLUMN reported_value NUMERIC(20, 4),
ADD COLUMN reported_currency CHAR(3);

ALTER TABLE snapshot_holding
ADD CONSTRAINT chk_snapshot_holding_reported_price CHECK (
    reported_price IS NULL OR reported_price >= 0
);

ALTER TABLE snapshot_holding
ADD CONSTRAINT chk_snapshot_holding_reported_value CHECK (
    reported_value IS NULL OR reported_value >= 0
);

ALTER TABLE snapshot_holding
ADD CONSTRAINT chk_snapshot_holding_reported_currency CHECK (
    reported_currency IS NULL OR reported_currency ~ '^[A-Z]{3}$'
);

-- A statement may leave a position's price out (a crypto line prints its rate only), never its
-- value or currency.
ALTER TABLE snapshot_holding
ADD CONSTRAINT chk_snapshot_holding_reported_together CHECK (
    (reported_value IS NULL) = (reported_currency IS NULL)
    AND (reported_price IS NULL OR reported_value IS NOT NULL)
);

COMMENT ON COLUMN snapshot_holding.reported_price IS
'Price per unit as the statement printed it, in reported_currency (#273).';
COMMENT ON COLUMN snapshot_holding.reported_value IS
'Market value as the statement printed it, in reported_currency; not the cost basis (#273).';
COMMENT ON COLUMN snapshot_holding.reported_currency IS
'Currency of the reported price and value; may differ from the account currency (#273).';
