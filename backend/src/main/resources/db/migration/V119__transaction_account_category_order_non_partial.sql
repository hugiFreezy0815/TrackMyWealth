-- #520: V115's uncategorized-inbox index, rebuilt without its partial predicate so V118's
-- statistics reach its cost estimate.
--
-- When PostgreSQL costs a partial index it adds the index predicate to the index conditions as
-- bare clauses, and its extended-statistics code gives up on a list holding any bare clause. The
-- partial index was therefore costed for 7,184 rows while the table estimate was 414: a deep inbox
-- page (offset 450 of 500) read idx_transaction_category, every workspace's uncategorized rows
-- (20,500 removed by filter), and sorted. Measured on PagedListPlanTest's fixture (PostgreSQL
-- 16.15, as the runtime role): partial index 7,738, bitmap scan plus sort 3,299, this index 1,117.
--
-- The predicate's columns move into INCLUDE, so the page's count(*) stays an index-only scan:
-- deleted, voided and replaced rows are now in the index and are filtered there, without a heap
-- visit. Live rows hold NULL in all three columns, which costs only the null bitmap. Same name and
-- key as before; plain CREATE INDEX blocks writes to the table while it is built, at application
-- start (V115).
DROP INDEX idx_transaction_account_category_order;

CREATE INDEX idx_transaction_account_category_order
ON transaction (
    workspace_id, account_id, category_id, booking_date DESC, created_at DESC, id DESC
)
INCLUDE (deleted_at, voided_at, replaces_transaction_id);
