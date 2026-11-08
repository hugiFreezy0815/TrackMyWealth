-- #520: category_id is shared across workspaces, but its frequency depends strongly on
-- account_id. Assuming independence makes the uncategorized inbox look much denser than
-- it is for an individual account and can make PostgreSQL scan the date-order index,
-- filtering thousands of rows before finding the first 50.
--
-- Multivariate most-common-value statistics let the planner estimate actual
-- (account_id, category_id) combinations, including sparse uncategorized accounts.
-- Dependencies also capture the relationship for combinations not in the MCV list.
-- PostgreSQL collects these statistics during normal autovacuum ANALYZE; the plan
-- regression fixture's VACUUM (ANALYZE) collects them explicitly.
CREATE STATISTICS st_transaction_account_category (mcv, dependencies)
ON account_id, category_id
FROM transaction;
