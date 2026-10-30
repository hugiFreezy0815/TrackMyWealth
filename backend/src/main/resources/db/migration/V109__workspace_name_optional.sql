-- US-03-07: an unnamed workspace uses the translated "All portfolios" label in the UI.
-- Allow a null name from setup or the rename endpoint, and never store a blank one.
ALTER TABLE workspace
ALTER COLUMN name DROP NOT NULL;

-- Names stored before US-03-07 were @NotBlank but never trimmed. Normalize them the way the service
-- now does (WorkspaceNames.normalize), so the check below holds for every row. Only changed rows
-- are touched; they get a new version like any other update. Forced RLS would hide every row from
-- the migration role, so it is lifted for the backfill only (as in V75).
ALTER TABLE workspace NO FORCE ROW LEVEL SECURITY;

UPDATE workspace
SET name = nullif(btrim(name), '')
WHERE name IS DISTINCT FROM nullif(btrim(name), '');

ALTER TABLE workspace FORCE ROW LEVEL SECURITY;

-- Defence in depth: the 60-character limit and the character rules stay in the application, so
-- that a longer name stored before them cannot block this migration.
ALTER TABLE workspace
ADD CONSTRAINT chk_workspace_name_not_blank CHECK (
    name IS NULL OR btrim(name) <> ''
);
