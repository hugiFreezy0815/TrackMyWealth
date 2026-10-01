-- =============================================================================================
-- V45: Preserve category override revision after reverting to shipped values (FR-CNC-001/002)
-- =============================================================================================
-- V34 rejected an override row with all nullable override fields empty, and CategoryService deleted
-- such a row when a workspace reverted a default category back to the shipped values. That reset
-- the client-visible optimistic-concurrency token to 0; a later override row could then reuse an
-- old numeric version and let a stale client overwrite a newer customization.
--
-- Keep the row as a revision/tombstone instead. NULL still means "inherit the shipped value" for
-- every field, but the row's version continues increasing across customize -> revert -> customize
-- cycles. CategoryService reports customised=false when all three override values are NULL.
-- =============================================================================================

ALTER TABLE workspace_category_override
DROP CONSTRAINT workspace_category_override_not_empty;
