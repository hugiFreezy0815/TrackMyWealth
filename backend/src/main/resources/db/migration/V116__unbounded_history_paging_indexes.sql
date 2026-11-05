-- US-29-13 (#473): account-local paging indexes (with stable id tie-breakers).
-- All indices support the database's RLS workspace confinement; Flyway is forward-only.
-- Partial snapshot index excludes withdrawn observations just as the page query does.
CREATE INDEX idx_account_snapshot_paged
ON account_snapshot (account_id, snapshot_date DESC, id DESC)
WHERE withdrawn_at IS NULL;

CREATE INDEX idx_custom_asset_valuation_paged
ON custom_asset_valuation (account_id, valuation_date DESC, id DESC);

CREATE INDEX idx_loan_payment_paged
ON loan_payment (loan_account_id, booking_date DESC, id DESC);

CREATE INDEX idx_amortisation_schedule_paged
ON amortisation_schedule_entry (account_id, due_date ASC, id ASC);

-- The queue filters on status and sometimes kind/card; the account_id predicate remains
-- in the query for authorization and is a useful planner filter.
CREATE INDEX idx_settlement_match_paged
ON settlement_match (status, created_at DESC, id DESC);

CREATE INDEX idx_settlement_match_card_paged
ON settlement_match (card_account_id, status, created_at DESC, id DESC);

-- Restore list's fixed order is an expression, not a single entity sort property.
-- Keep inactive rows visible to the native query even though Hibernate excludes them.
CREATE INDEX idx_transaction_restorable_paged
ON transaction (account_id, (coalesce(deleted_at, voided_at)) DESC, id DESC)
WHERE deleted_at IS NOT NULL OR voided_at IS NOT NULL;
