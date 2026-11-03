-- US-07-35 (#382): the workspace-wide transaction list, GET /api/v1/transactions.
--
-- 1. Search. q finds a row by its description or its counterparty name (V113), whatever the case
--    and accents ("zurich" finds "Zürich"). Both sides are folded by the same IMMUTABLE functions,
--    so a trigram index on the folded text serves q's LIKE '%...%'. unaccent() itself is only
--    STABLE (its dictionary could change); the wrappers pin the dictionary by its schema-qualified
--    name, the documented way to index unaccented text. unaccent is a trusted extension
--    (PostgreSQL 13+), so the migration role needs no superuser rights for it, like pg_trgm (V10).
-- 2. Order. The list's default order is booking date, then entry time, then id, all descending
--    (web.Paged appends the tie-breaker in the first field's direction); one index per access
--    path lets the first page stop after 50 rows instead of sorting the whole workspace.

CREATE EXTENSION IF NOT EXISTS unaccent;

-- Lower case without accents: the form q and the indexed text are compared in.
CREATE FUNCTION search_fold(value TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT lower(public.unaccent('public.unaccent'::REGDICTIONARY, value))
$$;

-- What q is matched against: description and counterparty name, folded. A NULL part is left out.
CREATE FUNCTION transaction_search_text(description TEXT, counterparty TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    -- Schema-qualified: an index expression must not depend on search_path (pg_dump restores
    -- with an empty one).
    SELECT public.search_fold(concat_ws(' ', description, counterparty))
$$;

CREATE INDEX idx_transaction_search_trgm
ON transaction USING gin (
    transaction_search_text(merchant_description, counterparty_name) gin_trgm_ops
);

-- The default order of the whole workspace's ledger (soft-deleted rows are never listed).
CREATE INDEX idx_transaction_workspace_ledger_order
ON transaction (workspace_id, booking_date DESC, created_at DESC, id DESC)
WHERE deleted_at IS NULL;

-- The same order inside one category: the uncategorized inbox (FR-CAT-013) and categoryId=...
CREATE INDEX idx_transaction_workspace_category_order
ON transaction (workspace_id, category_id, booking_date DESC, created_at DESC, id DESC)
WHERE deleted_at IS NULL;
