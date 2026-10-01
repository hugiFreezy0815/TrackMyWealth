-- =============================================================================================
-- V47: bounded authorization-denial audit lifecycle (#205)
-- =============================================================================================
-- RATE_LIMITED is a summary row emitted when one principal exceeds the configured exact-row
-- budget in a throttle window. It intentionally carries no requested_entity_id: after the budget
-- is exhausted we preserve the security signal without allowing random UUID probes to grow this
-- table without bound.
--
-- suppressed_count is set only on the RATE_LIMITED row that closes such a window: how many
-- denials in it were answered without a row of their own, so the magnitude of probing survives.
--
-- Retention deletes by occurred_at, so index that path. The principal index from V16 remains useful
-- for incident review by actor.
-- =============================================================================================

ALTER TABLE authorization_denial_log
DROP CONSTRAINT authorization_denial_log_reason_check,
ADD CONSTRAINT authorization_denial_log_reason_check
CHECK (reason IN ('NOT_FOUND', 'NOT_AUTHORIZED', 'RATE_LIMITED'));

ALTER TABLE authorization_denial_log
ADD COLUMN suppressed_count INTEGER,
ADD CONSTRAINT authorization_denial_log_suppressed_count_check
CHECK (
    suppressed_count IS NULL
    OR (reason = 'RATE_LIMITED' AND suppressed_count > 0)
);

CREATE INDEX idx_authorization_denial_log_occurred_at
ON authorization_denial_log (occurred_at);
