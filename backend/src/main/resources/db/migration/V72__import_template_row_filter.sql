-- US-07-09: an optional row filter (a source column and its accepted values) is part of a
-- versioned import template. NULL means the existing behaviour: every data row is parsed.
ALTER TABLE import_template
ADD COLUMN row_filter JSONB;

ALTER TABLE import_template
ADD CONSTRAINT chk_import_template_row_filter CHECK (
    row_filter IS NULL OR jsonb_typeof(row_filter) = 'object'
);

COMMENT ON COLUMN import_template.row_filter IS
'Optional source column and accepted values; nonmatching rows are shown as SKIPPED.';
