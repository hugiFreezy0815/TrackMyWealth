-- US-07-35 (#382): the workspace-wide transaction list, GET /api/v1/transactions.
--
-- 1. Search. q finds a row by its description or its counterparty name (V113), whatever the case
--    and accents ("zurich" finds "Zürich"). Both sides are folded by the same IMMUTABLE function.
--    unaccent() itself is only STABLE (its dictionary could change); the wrapper pins the
--    dictionary by its schema-qualified name, the documented way to store or index unaccented
--    text. unaccent is a trusted extension (PostgreSQL 13+), so the migration role needs no
--    superuser rights for it.
--
--    No index serves q: transaction is FORCE ROW LEVEL SECURITY (V20) and the runtime role does
--    not bypass it, so the planner may use a user condition as an index condition only if its
--    operator is LEAKPROOF - LIKE (~~) is not, and only a superuser can declare it so. A trigram
--    index on the folded text is used by a superuser's EXPLAIN and never by the application
--    (measured: 0.1 ms vs. 890 ms for a rare q in a 200k-row workspace). What does help is folding
--    once per write instead of once per row and search: the folded text is a stored generated
--    column, the workspace's rows come from the order indexes below, and q is a plain LIKE on
--    search_text (about 130 ms for the same 200k rows; a realistic workspace is a tenth of that).
-- 2. Order. The list's default order is booking date, then entry time, then id, all descending
--    (web.Paged appends the tie-breaker in the first field's direction); one index per access
--    path lets the first page stop after 50 rows instead of sorting the whole workspace.

CREATE EXTENSION IF NOT EXISTS unaccent;

-- Lower case without accents: the form q and the stored text are compared in.
CREATE FUNCTION search_fold(value TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT lower(public.unaccent('public.unaccent'::REGDICTIONARY, value))
$$;

-- What q is matched against: description and counterparty name, folded. A NULL part is left out.
-- The parts are joined by U+001F (unit separator), a character q may not contain, so a q cannot
-- match across the end of one field and the start of the next.
CREATE FUNCTION transaction_search_text(description TEXT, counterparty TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    -- Schema-qualified: a generated column must not depend on search_path (pg_dump restores
    -- with an empty one).
    SELECT public.search_fold(concat_ws(chr(31), description, counterparty))
$$;

-- The LIKE pattern "contains q": q folded first, then LIKE's wildcards and its escape character
-- (backslash) escaped - in this order, so nothing the fold produces can become a wildcard.
CREATE FUNCTION search_like_pattern(q TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT '%' || replace(replace(replace(public.search_fold(q), '\', '\\'), '%', '\%'), '_', '\_')
        || '%'
$$;

-- Rewrites the table once. trg_transaction_append_only lists the frozen columns explicitly, so a
-- text-only correction still updates the description or counterparty (and with them this column)
-- in place. If the unaccent dictionary is ever changed, recompute with
-- ALTER TABLE transaction ALTER COLUMN search_text SET EXPRESSION AS (...) (PostgreSQL 17+) or by
-- dropping and re-adding the column.
ALTER TABLE transaction ADD COLUMN search_text TEXT
GENERATED ALWAYS AS (transaction_search_text(merchant_description, counterparty_name)) STORED;

COMMENT ON COLUMN transaction.search_text IS
'Folded (lower case, unaccented) merchant_description and counterparty_name, joined by U+001F: '
'what the workspace list''s q is matched against (US-07-35). Generated; never written.';

-- The default order of the whole workspace's ledger (soft-deleted rows are never listed).
CREATE INDEX idx_transaction_workspace_ledger_order
ON transaction (workspace_id, booking_date DESC, created_at DESC, id DESC)
WHERE deleted_at IS NULL;

-- The same order inside one category: the uncategorized inbox (FR-CAT-013) and categoryId=...
CREATE INDEX idx_transaction_workspace_category_order
ON transaction (workspace_id, category_id, booking_date DESC, created_at DESC, id DESC)
WHERE deleted_at IS NULL;
