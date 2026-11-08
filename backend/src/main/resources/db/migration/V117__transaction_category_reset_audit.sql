-- US-07-38: a member's reset is auditable, not an anonymous automatic rerun.
-- Existing decisions remain untouched; older automatic rows stay CATEGORY_ASSIGNED.
ALTER TABLE transaction_categorization_log
ADD COLUMN is_reset BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE transaction_categorization_log
ADD CONSTRAINT transaction_categorization_log_reset_actor CHECK (
    NOT is_reset OR (NOT is_user_override AND assigned_by_user_id IS NOT NULL)
);

-- A reset of a non-categorized type can legitimately return to a NULL category.
ALTER TABLE transaction_categorization_log
ALTER COLUMN category_id DROP NOT NULL;

ALTER TABLE transaction_categorization_log
ADD CONSTRAINT transaction_categorization_log_category_or_reset CHECK (
    category_id IS NOT NULL OR is_reset
);

-- An uncategorized reset has no automatic assignment method but needs an audit row.
ALTER TABLE transaction_categorization_log
DROP CONSTRAINT transaction_categorization_log_assigned_by_check;

ALTER TABLE transaction_categorization_log
ADD CONSTRAINT transaction_categorization_log_assigned_by_check CHECK (
    assigned_by IN (
        'SOURCE_CODE', 'RULE', 'TRANSACTION_TYPE', 'FALLBACK_MATCH', 'USER', 'RESET'
    )
);

-- Query the complete, paged history of a correction chain without scanning unrelated logs.
CREATE INDEX idx_transaction_categorization_log_history
ON transaction_categorization_log (transaction_id, assigned_at DESC, id DESC);
