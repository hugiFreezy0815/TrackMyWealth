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
-- Backfill (PO 2026-11-23: today's visible gains stay): a position whose statement value is in
-- the snapshot's currency (the account's own, V58) had a gain before, whatever its security's
-- currency; so had one without a statement value whose security is in that currency. Their cost
-- basis is taken to be in that currency, as before. Every other cost basis stays of unknown
-- currency.
--
-- A cost at the purchase-date rate is only for a cost basis in another currency than the
-- account's: the API refuses it beside one in the account currency (422), and a trigger keeps
-- that rule here, since a CHECK cannot read the snapshot's currency.
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
    AND (
        h.reported_currency = s.currency
        OR (h.reported_currency IS NULL AND sec.denomination_currency = s.currency)
    );

CREATE FUNCTION trg_snapshot_holding_account_cost_guard()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.cost_basis_in_account_currency IS NOT NULL
        AND EXISTS (
            SELECT 1 FROM account_snapshot AS s
            WHERE s.id = NEW.snapshot_id AND s.currency = NEW.reported_cost_basis_currency
        )
    THEN
        RAISE EXCEPTION 'snapshot_holding_account_cost: holding % has its cost basis in the account currency %, so it takes no separate account-currency cost (#527).',
            NEW.id, NEW.reported_cost_basis_currency USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER snapshot_holding_account_cost_guard
BEFORE INSERT OR UPDATE OF reported_cost_basis_currency, cost_basis_in_account_currency, snapshot_id
ON snapshot_holding
FOR EACH ROW EXECUTE FUNCTION trg_snapshot_holding_account_cost_guard();

COMMENT ON COLUMN snapshot_holding.reported_cost_basis_currency IS
'Currency of reported_cost_basis; NULL = unknown, so no gain (#527).';
COMMENT ON COLUMN snapshot_holding.cost_basis_in_account_currency IS
'Cost in the account currency at the purchase-date rate(s), for a cost basis in another '
'currency; never derived at today''s rate (#527).';
