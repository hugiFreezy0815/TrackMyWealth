-- =============================================================================================
-- V33: account_snapshot / snapshot_holding guards for manual snapshot entry (US-25-01)
-- =============================================================================================
-- FR-REC-006: a manually typed statement balance (and, for a depot, per-security quantities) is
-- what gives a fully manual user correctness before any connector exists. V11 already created both
-- tables; this adds what manual entry needs on top of them.
--
-- 1. Currency guard. A snapshot's currency must be its account's native_currency, the same rule
--    V26 enforces for custom_asset_valuation: the reconciliation engine (US-25-02) compares the
--    snapshot balance against the ledger-derived balance, which is always in the account's own
--    currency, so a snapshot in any other currency could never be compared without an FX
--    conversion nobody asked for. The service derives the currency from the account; this trigger
--    is defense-in-depth.
--
-- 2. One line per security per snapshot. A statement lists each position once; two lines for the
--    same security would leave "how many units were reported" ambiguous for reconciliation.
--
-- 3. updated_at / updated_by. A MANUAL snapshot may be replaced ("update today's snapshot", the
--    story's edge case) - it is the user's own transcription of a statement, so correcting a typo
--    is a correction of the observation, not a rewrite of history. The row still records that and
--    by whom it was changed. Provider-sourced snapshots are never replaced through the API.
-- =============================================================================================

CREATE OR REPLACE FUNCTION trg_account_snapshot_currency_guard()
RETURNS TRIGGER AS $$
DECLARE
    account_currency TEXT;
BEGIN
    SELECT native_currency INTO account_currency FROM account WHERE id = NEW.account_id;
    IF NEW.currency IS DISTINCT FROM account_currency THEN
        RAISE EXCEPTION 'account_snapshot_currency_mismatch: account % native_currency is % but a snapshot in % was attempted (FR-ACC-002)',
            NEW.account_id, account_currency, NEW.currency USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_snapshot_currency_guard
BEFORE INSERT OR UPDATE OF currency, account_id ON account_snapshot
FOR EACH ROW EXECUTE FUNCTION trg_account_snapshot_currency_guard();

ALTER TABLE snapshot_holding
ADD CONSTRAINT uq_snapshot_holding_security UNIQUE (snapshot_id, security_id);
-- The unique index leads with snapshot_id, so it serves every lookup V11's index did.
DROP INDEX idx_snapshot_holding_snapshot;

ALTER TABLE account_snapshot
ADD COLUMN updated_at TIMESTAMPTZ,
ADD COLUMN updated_by UUID REFERENCES app_user (id);
