-- #520: category_id is shared across workspaces (UNCATEGORIZED is one system category), but its
-- frequency depends strongly on account_id. Assuming the two independent, PostgreSQL took the
-- database-wide uncategorized share for every account: 7,184 estimated rows for an inbox of 500 in
-- PagedListPlanTest's fixture. At that density walking the account's date-order index and
-- filtering on category_id looked as cheap as the inbox index, and the first page sometimes did.
--
-- Multivariate most-common-value statistics give the planner the real (account_id, category_id)
-- combinations, sparse uncategorized accounts included (414 estimated for 500 in the fixture);
-- dependencies cover combinations outside the MCV list. Autovacuum's ANALYZE collects them, as
-- does the plan fixture's VACUUM (ANALYZE).
--
-- These statistics alone made the inbox's deep pages worse: PostgreSQL ignores extended
-- statistics when it costs a partial index, so V115's partial inbox index kept the old estimate
-- while a sort over idx_transaction_category got cheaper. V119 makes that index non-partial.
CREATE STATISTICS st_transaction_account_category (MCV, DEPENDENCIES)
ON account_id, category_id
FROM transaction;
