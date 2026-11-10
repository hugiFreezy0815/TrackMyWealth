-- V120 recorded the account's creation time for an unverified migration baseline.
-- That time is not proof that current portfolio membership or archive state was already true.
-- The Flyway V120 installation time is the earliest authoritative point for that baseline.
-- Keep V120 immutable for installations which have already applied it.
-- Flyway runs this migration as the schema owner. FORCE RLS prevents reading history
-- without a workspace context, so temporarily lift FORCE within this DDL transaction.
ALTER TABLE account_history_state NO FORCE ROW LEVEL SECURITY;

UPDATE account_history_state
SET
    effective_at = (
        SELECT installed_on AT TIME ZONE current_setting('TimeZone')
        FROM flyway_schema_history
        WHERE version = '120'
    )
WHERE verified = FALSE;

ALTER TABLE account_history_state FORCE ROW LEVEL SECURITY;
