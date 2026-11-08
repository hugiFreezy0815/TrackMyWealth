-- US-29-13 (#473): serve the six lists that become paged here in their documented order, each
-- ending with its id tie-breaker, without a separate sort. PagedListPlanTest checks every one as
-- the runtime role.
--
-- account_snapshot, loan_payment, settlement_match and transaction are FORCE ROW LEVEL SECURITY
-- (V20, V30, V83), so every read by the runtime role carries the policy's
-- workspace_id = current_workspace_id(). Their indexes lead with workspace_id for the reason
-- V115 gives: both the policy and the list's own filter become index conditions, and the planner
-- does not trade the ordered index for a workspace-wide one on a guessed selectivity.
-- custom_asset_valuation and amortisation_schedule_entry carry no workspace_id (they are scoped
-- through their account), so theirs lead with account_id.
--
-- A role that bypasses RLS has no workspace condition: it reads these lists through the older
-- account-led indexes (V5, V11, V83) plus a sort of one account's rows, as before #473.
-- Plain CREATE INDEX blocks writes to the table while it is built, at application start (V115).

-- Withdrawn snapshots are never listed, as in the page query.
CREATE INDEX idx_account_snapshot_history_order
ON account_snapshot (workspace_id, account_id, snapshot_date DESC, id DESC)
WHERE withdrawn_at IS NULL;

CREATE INDEX idx_custom_asset_valuation_history_order
ON custom_asset_valuation (account_id, valuation_date DESC, id DESC);

CREATE INDEX idx_loan_payment_history_order
ON loan_payment (workspace_id, loan_account_id, booking_date DESC, id DESC);

CREATE INDEX idx_amortisation_schedule_order
ON amortisation_schedule_entry (account_id, due_date ASC, id ASC);

-- The settlement-match queue: one status, newest first, optionally one card. Without
-- workspace_id first the default queue would walk every workspace's matches of that status in
-- created_at order and discard the other tenants' rows, a read growing with the whole database.
-- The queue index starts with (workspace_id, status), so it replaces V30's index of exactly that.
CREATE INDEX idx_settlement_match_queue_order
ON settlement_match (workspace_id, status, created_at DESC, id DESC);

DROP INDEX idx_settlement_match_workspace_status;

CREATE INDEX idx_settlement_match_card_queue_order
ON settlement_match (workspace_id, card_account_id, status, created_at DESC, id DESC);

-- The restore list's fixed order is an expression over two columns. The native query reads
-- removed rows, which the entity's @SQLRestriction hides, so the index is partial on exactly
-- those: every restorable row is soft-deleted or voided.
CREATE INDEX idx_transaction_restorable_order
ON transaction (
    workspace_id, account_id, (coalesce(deleted_at, voided_at)) DESC, id DESC
)
WHERE deleted_at IS NOT NULL OR voided_at IS NOT NULL;
