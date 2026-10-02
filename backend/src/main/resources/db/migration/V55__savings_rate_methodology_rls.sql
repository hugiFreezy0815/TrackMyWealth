-- =============================================================================================
-- V55: Row-level security for savings_rate_methodology (#223 review)
-- =============================================================================================
-- savings_rate_methodology (V14) holds one workspace's savings-rate settings - tenant data - but
-- V20's list of workspace-scoped tables missed it, so it never got RLS. No code reads or writes
-- it yet, so nothing has leaked; the first feature that does would have. Found by
-- SchemaConventionsTest's new check that every table with a workspace_id is under forced RLS.
--
-- The same policy V20 gives every workspace-scoped table.
-- =============================================================================================

ALTER TABLE savings_rate_methodology ENABLE ROW LEVEL SECURITY;
ALTER TABLE savings_rate_methodology FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON savings_rate_methodology
USING (workspace_id = current_workspace_id());
