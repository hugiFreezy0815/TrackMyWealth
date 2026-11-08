-- US-07-38: a member's reset is an auditable action, not another unlabelled automatic run.
-- Existing assignments are unchanged: older automatic decisions remain CATEGORY_ASSIGNED.
ALTER TABLE transaction_categorization_log
    ADD COLUMN is_reset BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE transaction_categorization_log
    ADD CONSTRAINT transaction_categorization_log_reset_actor CHECK (
        NOT is_reset OR (NOT is_user_override AND assigned_by_user_id IS NOT NULL)
    );

-- An uncategorized reset has no automatic assignment method, but still needs an audit row.
ALTER TABLE transaction_categorization_log
    DROP CONSTRAINT transaction_categorization_log_assigned_by_check;
ALTER TABLE transaction_categorization_log
    ADD CONSTRAINT transaction_categorization_log_assigned_by_check CHECK (
        assigned_by IN (
            'SOURCE_CODE', 'RULE', 'TRANSACTION_TYPE', 'FALLBACK_MATCH', 'USER', 'RESET'
        )
    );

-- Supports the complete, paged history of a correction chain without scanning all logs.
CREATE INDEX idx_transaction_categorization_log_history
    ON transaction_categorization_log (transaction_id, assigned_at DESC, id DESC);
