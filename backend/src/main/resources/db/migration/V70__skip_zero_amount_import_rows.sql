-- US-07-19: zero-amount rows are visible SKIPPED rows, not ledger errors.
-- Both the database and the API forbid including them in a commit.
ALTER TABLE import_row_raw
DROP CONSTRAINT import_row_raw_parse_status_check;

ALTER TABLE import_row_raw
ADD CONSTRAINT import_row_raw_parse_status_check
CHECK (parse_status IN ('PARSED', 'DUPLICATE', 'ERROR', 'SKIPPED'));

ALTER TABLE import_row_raw
DROP CONSTRAINT chk_import_row_raw_error_excluded;

ALTER TABLE import_row_raw
ADD CONSTRAINT chk_import_row_raw_not_includable_excluded
CHECK (NOT (included AND parse_status IN ('ERROR', 'SKIPPED')));
