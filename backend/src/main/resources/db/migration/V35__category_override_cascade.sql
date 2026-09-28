-- =============================================================================================
-- V35: workspace_category_override follows its workspace and its category (US-08-04)
-- =============================================================================================
-- An override only means something while both the workspace and the shipped default it
-- customises exist. V34 created both foreign keys without an ON DELETE action, so removing either
-- would have to find and delete the overrides first: a workspace erasure, or a reference package
-- retiring a default. Cascading makes the override go with them.
-- =============================================================================================

ALTER TABLE workspace_category_override
DROP CONSTRAINT workspace_category_override_workspace_id_fkey,
DROP CONSTRAINT workspace_category_override_category_id_fkey,
ADD CONSTRAINT fk_workspace_category_override_workspace
FOREIGN KEY (workspace_id) REFERENCES workspace (id) ON DELETE CASCADE,
ADD CONSTRAINT fk_workspace_category_override_category
FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE CASCADE;
