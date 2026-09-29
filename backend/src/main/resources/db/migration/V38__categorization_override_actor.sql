-- =============================================================================================
-- V38: who made a category override (US-08-02)
-- =============================================================================================
-- A member's override (assigned_by = 'USER', is_user_override) is protected from every automatic
-- run, so in a shared household the other members need to see whose choice it is. Automatic
-- assignments have no actor and keep NULL; an override must name one.
--
-- Safe on existing data: no override could be written before this story, so every row has
-- is_user_override = FALSE (V13's default).
-- =============================================================================================

ALTER TABLE transaction_categorization_log
ADD COLUMN assigned_by_user_id UUID REFERENCES app_user (id),
ADD CONSTRAINT transaction_categorization_log_override_actor CHECK (
    NOT is_user_override OR assigned_by_user_id IS NOT NULL
);
