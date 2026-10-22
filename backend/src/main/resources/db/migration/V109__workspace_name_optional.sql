-- US-03-07: an unnamed workspace uses the translated "All portfolios" label in the UI.
-- Keep existing names unchanged and allow a null name from setup or the rename endpoint.
ALTER TABLE workspace
ALTER COLUMN name DROP NOT NULL;
