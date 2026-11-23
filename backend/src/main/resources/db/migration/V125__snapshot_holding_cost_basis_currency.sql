-- =============================================================================================
-- V125: the currency of a position's cost basis, and its cost in the account currency (#527)
-- =============================================================================================
-- A statement prints a position's cost basis in the security's currency or in the account's;
-- some banks print both. Without its currency no gain can be certain (FR-PERF-018), so a foreign
-- position showed none.
--
-- reported_cost_basis_currency: the currency reported_cost_basis is in. NULL means unknown:
-- such a position has no gain, as before.
-- cost_basis_in_account_currency: the cost converted at the purchase-date rate(s), as the
-- statement prints it or the member enters it, for a cost basis in another currency. The
-- account-currency gain and the depot's total gain need it; it is never derived at today's rate.
--
-- Backfill (PO 2026-11-23): a position whose security and reported value are in the snapshot's
-- currency (the account's own, V58) had a certain gain before; its cost basis is in that
-- currency. Every other cost basis stays of unknown currency.
-- =============================================================================================

ALTER TABLE snapshot_holding
ADD COLUMN reported_cost_basis_currency CHAR(3),
ADD COLUMN cost_basis_in_account_currency NUMERIC(20, 4);

ALTER TABLE snapshot_holding
ADD CONSTRAINT chk_snapshot_holding_cost_basis_currency CHECK (
    reported_cost_basis_currency IS NULL
    OR (reported_cost_basis_currency ~ '^[A-Z]{3}$' AND reported_cost_basis IS NOT NULL)
);

ALTER TABLE snapshot_holding
ADD CONSTRAINT chk_snapshot_holding_cost_basis_in_account_currency CHECK (
    cost_basis_in_account_currency IS NULL
    OR (cost_basis_in_account_currency >= 0 AND reported_cost_basis_currency IS NOT NULL)
);

UPDATE snapshot_holding h
SET reported_cost_basis_currency = s.currency
FROM account_snapshot AS s, security AS sec
WHERE
    s.id = h.snapshot_id
    AND sec.id = h.security_id
    AND h.reported_cost_basis IS NOT NULL
    AND sec.denomination_currency = s.currency
    AND (h.reported_currency IS NULL OR h.reported_currency = s.currency);

COMMENT ON COLUMN snapshot_holding.reported_cost_basis_currency IS
'Currency of reported_cost_basis; NULL = unknown, so no gain (#527).';
COMMENT ON COLUMN snapshot_holding.cost_basis_in_account_currency IS
'Cost in the account currency at the purchase-date rate(s), for a cost basis in another '
'currency; never derived at today''s rate (#527).';
