-- =============================================================================================
-- V34: Workspace-customisable category taxonomy (US-08-04, FR-CAT-001..003/008)
-- =============================================================================================
-- Shipped default categories (workspace_id IS NULL, V19) are shared by every workspace, and V20's
-- RLS policy rejects any workspace write to them. A workspace therefore never edits a default row:
-- relabelling or deactivating a default is recorded per workspace in workspace_category_override,
-- a user customisation a later reference package must never overwrite (FR-REF-009). Workspace-
-- owned categories are ordinary rows the workspace edits directly.
--
-- The depth limit (3 levels), cycle prevention, protected codes (UNCATEGORIZED,
-- TRANSFER_INTERNAL) and "hard delete only if never used" (FR-LIF-001) are service-layer rules in
-- CategoryService - a self-referencing tree depth is not something a CHECK can express safely under
-- concurrent writes. What is enforced here is what must hold for every writer.
-- =============================================================================================

-- --- category: optimistic locking, like every other read-modify-write table (FR-CNC-001) ------
ALTER TABLE category ADD COLUMN version INTEGER NOT NULL DEFAULT 0; -- noqa: RF04
CREATE TRIGGER category_bump_version
BEFORE UPDATE ON category
FOR EACH ROW EXECUTE FUNCTION trg_bump_version();

-- --- code uniqueness also across the shipped defaults -----------------------------------------
-- V13's UNIQUE (workspace_id, code) treats NULLs as distinct, so it never stopped two default rows
-- sharing a code. NULLS NOT DISTINCT (PostgreSQL 15+) closes that gap.
ALTER TABLE category DROP CONSTRAINT category_workspace_id_code_key;
ALTER TABLE category
ADD CONSTRAINT uq_category_workspace_code UNIQUE NULLS NOT DISTINCT (workspace_id, code);

-- --- separate code namespaces: workspace codes can never clash with a default code -------------
-- Workspace codes are server-generated with a WS_ prefix and defaults never carry it, so a
-- reference package adding a default later cannot collide with a code a workspace already uses.
ALTER TABLE category
ADD CONSTRAINT category_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
ADD CONSTRAINT category_code_namespace CHECK ((workspace_id IS NULL) = (code NOT LIKE 'WS\_%'));

-- --- a parent is either a shared default or a category of the same workspace -------------------
-- The FK alone would accept another workspace's category as parent (FK checks bypass RLS). The
-- lookup below runs under the caller's RLS context, so a foreign parent is simply not found.
CREATE OR REPLACE FUNCTION trg_category_parent_scope_guard()
RETURNS TRIGGER AS $$
DECLARE
    parent_workspace UUID;
    parent_found     BOOLEAN;
BEGIN
    IF NEW.parent_category_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT TRUE, workspace_id INTO parent_found, parent_workspace
        FROM category WHERE id = NEW.parent_category_id;
    IF parent_found IS NULL
        OR (parent_workspace IS NOT NULL AND parent_workspace IS DISTINCT FROM NEW.workspace_id) THEN
        RAISE EXCEPTION 'category_parent_scope: category % may only be placed under a default category or a category of its own workspace',
            NEW.id USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER category_parent_scope_guard
BEFORE INSERT OR UPDATE OF parent_category_id, workspace_id ON category
FOR EACH ROW EXECUTE FUNCTION trg_category_parent_scope_guard();

-- --- per-workspace customisation of a shared default category ----------------------------------
-- A NULL column means "inherit the shipped value", so a later package relabelling a default still
-- reaches workspaces that never relabelled it themselves.
CREATE TABLE workspace_category_override (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace (id),
    category_id UUID NOT NULL REFERENCES category (id),
    name_en TEXT,
    name_de TEXT,
    is_active BOOLEAN,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INTEGER NOT NULL DEFAULT 0, -- noqa: RF04
    CONSTRAINT uq_workspace_category_override UNIQUE (workspace_id, category_id),
    CONSTRAINT workspace_category_override_not_empty
    CHECK (name_en IS NOT NULL OR name_de IS NOT NULL OR is_active IS NOT NULL)
);
CREATE INDEX idx_workspace_category_override_category
ON workspace_category_override (category_id);
CREATE TRIGGER workspace_category_override_set_updated_at
BEFORE UPDATE ON workspace_category_override
FOR EACH ROW EXECUTE FUNCTION trg_set_updated_at();
CREATE TRIGGER workspace_category_override_bump_version
BEFORE UPDATE ON workspace_category_override
FOR EACH ROW EXECUTE FUNCTION trg_bump_version();

-- Only a shared default can be overridden; a workspace edits its own categories directly.
CREATE OR REPLACE FUNCTION trg_workspace_category_override_default_only()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM category WHERE id = NEW.category_id AND workspace_id IS NULL) THEN
        RAISE EXCEPTION 'workspace_category_override_default_only: category % is not a shipped default category',
            NEW.category_id USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspace_category_override_default_only
BEFORE INSERT OR UPDATE OF category_id ON workspace_category_override
FOR EACH ROW EXECUTE FUNCTION trg_workspace_category_override_default_only();

ALTER TABLE workspace_category_override ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_category_override FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_category_override
USING (workspace_id = current_workspace_id());
