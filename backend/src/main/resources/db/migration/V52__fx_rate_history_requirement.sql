-- =============================================================================================
-- V52: How far back FX history is needed - the earliest booking date in the ledger (#223)
-- =============================================================================================
-- The FX import (US-06-04) loads rate history from the first transaction booking on, and must
-- load further back as soon as an older transaction arrives (an import of old statements). That
-- date spans every workspace, but transaction is behind FORCE ROW LEVEL SECURITY (V20): a job
-- with no workspace context sees no rows at all once the runtime role is no longer the superuser
-- (the role split V20 describes). So the ledger reports it here instead, through a trigger that
-- fires for every insert path, today's and any future import's.
--
-- Global, non-tenant data like fx_rate itself: one row holding one date, no workspace_id, no
-- amount, no account. Only the backend reads it; no endpoint returns it.
--
-- The trigger's UPDATE only matches - and only locks the row - when a booking is older than
-- every earlier one, so ordinary inserts never contend on it. Two concurrent older bookings
-- serialise on the row lock, and READ COMMITTED re-checks the WHERE after the wait, so the
-- stored date is always the minimum.
--
-- The seed reads the existing ledger as the migration role. Every environment today migrates
-- as the bootstrap superuser, which bypasses RLS; under a non-bypassing role it would seed NULL,
-- and fetch-on-missing (FxRateService) would still load older rates the first time a
-- conversion needs them.
-- =============================================================================================

CREATE TABLE fx_rate_history_requirement (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Exactly one row: the UNIQUE + CHECK pair admits only singleton = TRUE once.
    singleton BOOLEAN NOT NULL DEFAULT TRUE UNIQUE CHECK (singleton),
    earliest_booking_date DATE
);

INSERT INTO fx_rate_history_requirement (earliest_booking_date)
SELECT min(booking_date) FROM transaction;

CREATE OR REPLACE FUNCTION trg_fx_rate_history_requirement() RETURNS TRIGGER AS $$
BEGIN
    UPDATE fx_rate_history_requirement
    SET earliest_booking_date = NEW.booking_date
    WHERE earliest_booking_date IS NULL OR earliest_booking_date > NEW.booking_date;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER transaction_fx_rate_history_requirement
AFTER INSERT ON transaction
FOR EACH ROW EXECUTE FUNCTION trg_fx_rate_history_requirement();

COMMENT ON TABLE fx_rate_history_requirement IS
'#223: earliest booking date in the ledger - FX history is loaded back to it.';
