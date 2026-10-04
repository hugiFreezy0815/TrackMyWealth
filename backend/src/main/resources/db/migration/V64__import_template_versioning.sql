-- =============================================================================================
-- V64: import template versioning, activation and optimistic concurrency (US-07-03, #229)
-- =============================================================================================
-- FR-IMP-023: a template is versioned so a historical import stays reproducible under the exact
-- template it used. Every version is its own import_template row; the rows of one template share
-- template_family_id, and exactly one of them is the current version. A change to any
-- parse-relevant field inserts a new row instead of updating the old one, which import_batch
-- keeps pointing at (template_id + template_version_used).
--
-- is_active: a template some import batch used cannot be deleted (FR-LIF-001); the member
-- deactivates it instead, which hides it from detection and from the default list.
--
-- version: the database-owned revision every mutable resource carries (ADR 0004, V48), mapped
-- with @Version + @Generated so JPA and direct SQL writers advance the same client token.
-- =============================================================================================

ALTER TABLE import_template
ADD COLUMN template_family_id UUID;

-- Rows from before versioning each start their own family.
UPDATE import_template SET template_family_id = id;

ALTER TABLE import_template
ALTER COLUMN template_family_id SET NOT NULL;

ALTER TABLE import_template
ADD COLUMN is_current BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE import_template
ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;

-- FR-IMP-022: the header cells of the sample the template was built from, as a JSON array of
-- strings. header_fingerprint is derived from them server-side, and a mapping by column name is
-- checked against them when the template is saved (a repeated name cannot be mapped by name).
-- NULL for a file without a header row.
ALTER TABLE import_template
ADD COLUMN header_columns JSONB;

ALTER TABLE import_template
ADD COLUMN version INTEGER NOT NULL DEFAULT 0; -- noqa: RF04

CREATE TRIGGER import_template_bump_version
BEFORE UPDATE ON import_template
FOR EACH ROW EXECUTE FUNCTION trg_bump_version();

-- Two concurrent edits of one template cannot both leave a current version behind.
CREATE UNIQUE INDEX uq_import_template_family_current
ON import_template (template_family_id)
WHERE is_current;

-- The parser's row-skipping counts. header_row_index -1 means the file has no header row and
-- the column mapping uses 0-based column indexes.
ALTER TABLE import_template
ADD CONSTRAINT chk_import_template_row_counts CHECK (
    header_row_index >= -1
    AND preamble_row_count >= 0
    AND trailing_summary_row_count >= 0
);
