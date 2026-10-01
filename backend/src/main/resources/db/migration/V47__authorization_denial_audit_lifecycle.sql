-- =============================================================================================
-- V47: bounded authorization-denial audit lifecycle (#205)
-- =============================================================================================
-- RATE_LIMITED is a summary row emitted when one principal exceeds the configured exact-row
-- budget in a throttle window. It intentionally carries no requested_entity_id: after the budget
-- is exhausted we preserve the security signal without allowing random UUID probes to grow this
-- table without bound.
--
-- Retention deletes by occurred_at, so index that path. The principal index from V16 remains useful
-- for incident review by actor.
-- =============================================================================================

ALTER TABLE authorization_denial_log
DROP CONSTRAINT authorization_denial_log_reason_check,
ADD CONSTRAINT authorization_denial_log_reason_check
CHECK (reason IN ('NOT_FOUND', 'NOT_AUTHORIZED', 'RATE_LIMITED'));

CREATE INDEX idx_authorization_denial_log_occurred_at
ON authorization_denial_log (occurred_at);
