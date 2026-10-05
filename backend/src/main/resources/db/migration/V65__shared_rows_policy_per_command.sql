-- =============================================================================================
-- V65: shipped rows of category and import_template are read-only for every workspace (#279)
-- =============================================================================================
-- V20 gave both tables one policy for every command:
--   USING (workspace_id IS NULL OR workspace_id = current_workspace_id())
--   WITH CHECK (workspace_id = current_workspace_id())
-- DELETE is checked against USING only, and a shipped row (workspace_id IS NULL) passes it, so
-- the database itself let a workspace delete a shipped category or template; only the services
-- refused. Deleting a shipped category also cascades (V35) to every other workspace's override of
-- it. An UPDATE that sets workspace_id to the caller's workspace also passed: USING sees a shipped
-- row, WITH CHECK sees the caller's own. For category V34's code-namespace check happens to block
-- that; for import_template nothing did.
--
-- Split per command: every workspace still reads the shipped rows, but inserts, updates and
-- deletes reach its own rows only. Shipped rows stay writable only by a role that bypasses RLS
-- (migrations, the reference-data package), as before.
--
-- SELECT ... FOR UPDATE must also pass the UPDATE policy, so a shipped row can no longer be locked
-- by a workspace: ImportTemplateService locks own rows only and tells a shipped row (read-only)
-- from a hidden one by a plain read when the lock misses.
-- CategoryService locks the workspace row, not the category, and is unaffected.
-- =============================================================================================

DROP POLICY tenant_isolation_or_shared ON category;

CREATE POLICY category_select ON category FOR SELECT
USING (workspace_id IS NULL OR workspace_id = current_workspace_id());

CREATE POLICY category_insert ON category FOR INSERT
WITH CHECK (workspace_id = current_workspace_id());

CREATE POLICY category_update ON category FOR UPDATE
USING (workspace_id = current_workspace_id())
WITH CHECK (workspace_id = current_workspace_id());

CREATE POLICY category_delete ON category FOR DELETE
USING (workspace_id = current_workspace_id());

DROP POLICY tenant_isolation_or_shared ON import_template;

CREATE POLICY import_template_select ON import_template FOR SELECT
USING (workspace_id IS NULL OR workspace_id = current_workspace_id());

CREATE POLICY import_template_insert ON import_template FOR INSERT
WITH CHECK (workspace_id = current_workspace_id());

CREATE POLICY import_template_update ON import_template FOR UPDATE
USING (workspace_id = current_workspace_id())
WITH CHECK (workspace_id = current_workspace_id());

CREATE POLICY import_template_delete ON import_template FOR DELETE
USING (workspace_id = current_workspace_id());
