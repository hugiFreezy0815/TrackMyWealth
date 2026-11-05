-- US-29-06 (#395): serve the paged account ledger, import history, reconciliation history
-- and institution catalogue in their documented order without a separate sort.
-- V114 already indexes the workspace-wide transaction list and its category filter.
--
-- The three account transaction indexes lead with workspace_id. transaction is FORCE ROW LEVEL
-- SECURITY (V20), so the runtime role's every read carries the policy's
-- workspace_id = current_workspace_id(). PostgreSQL can only guess that condition's share of the
-- table, and with account-led indexes it chose V114's workspace indexes for about half of the
-- analyzed test fixtures - for the deep ledger page with an Incremental Sort. With workspace_id
-- first, both the policy and account_id are index conditions and the scan is in page order.
-- Like V114's, they are partial on deleted_at IS NULL, the entity's @SQLRestriction that every
-- paged read carries: smaller, and the page's count(*) is an index-only scan.
-- A role that bypasses RLS (a not yet split single-role installation, see DatabaseRoleConfig)
-- has no workspace condition and cannot use them.
--
-- For that role idx_transaction_account_date (V10) becomes idx_transaction_account_date_order:
-- the same (account_id, booking_date) prefix for the account's booking-date range reads (import
-- duplicate rule 2, balances) with or without RLS, plus the ledger's full page order, so a ledger
-- page read through it - by that role, or by the runtime role when the planner prefers it - needs
-- no sort either. It stays non-partial: native reads of an account's rows include deleted ones.
--
-- Cost, measured on 500,000 rows (PostgreSQL 16, median of five runs): transaction has three
-- more indexes than before. An import's row-by-row inserts (JDBC batches of 50) took about 4%
-- longer, a set-based INSERT ... SELECT about 17%. Flyway runs at application start (ADR 0001):
-- building them delays that start once, by about 1.2 s per 500,000 rows. Plain CREATE INDEX
-- blocks writes to the table meanwhile, which only another instance still running would notice.
CREATE INDEX idx_transaction_account_ledger_order
ON transaction (workspace_id, account_id, booking_date DESC, created_at DESC, id DESC)
WHERE deleted_at IS NULL;

CREATE INDEX idx_transaction_account_date_order
ON transaction (account_id, booking_date DESC, created_at DESC, id DESC);

DROP INDEX idx_transaction_account_date;

-- The ledger's sort=amount maps to V108's generated account-currency amount, not to amount.
-- PostgreSQL can scan this ascending index backwards for amount DESC, id DESC.
CREATE INDEX idx_transaction_account_amount_order
ON transaction (workspace_id, account_id, amount_in_account_currency, id)
WHERE deleted_at IS NULL;

-- The account's uncategorized inbox (uncategorized=true). Without it PostgreSQL reads
-- idx_transaction_category (V10) and sorts: UNCATEGORIZED is one shared system category, so that
-- index holds every workspace's uncategorized rows and the read grows with the whole database.
-- The predicate is the repository's (and Transaction's @SQLRestriction); category_id stays a key
-- column, so the same index serves any one category of the account.
CREATE INDEX idx_transaction_account_category_order
ON transaction (
    workspace_id, account_id, category_id, booking_date DESC, created_at DESC, id DESC
)
WHERE voided_at IS NULL AND replaces_transaction_id IS NULL AND deleted_at IS NULL;

CREATE INDEX idx_import_batch_account_order
ON import_batch (account_id, uploaded_at DESC, id DESC);

-- The leading account_id also covers all lookups that used the old single-column index.
DROP INDEX idx_import_batch_account;

-- The account's reconciliation history excludes per-security results. Keep the existing
-- (account_id, status) index: other reconciliation queries still use that access path.
CREATE INDEX idx_reconciliation_cash_history_order
ON reconciliation_result (account_id, created_at DESC, id DESC)
WHERE affected_security_id IS NULL;

-- The catalogue lists active institutions in name/id order. A name ILIKE '%term%' filter
-- is not a B-tree lookup; it remains a filter over the ordered active entries.
CREATE INDEX idx_institution_catalogue_active_name_order
ON institution_catalogue (name, id)
WHERE is_active;
