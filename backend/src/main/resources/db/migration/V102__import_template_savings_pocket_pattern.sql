-- US-07-22 (#325): versioned template rule for the moves between an account and its savings pocket.
ALTER TABLE import_template
ADD COLUMN savings_pocket_pattern TEXT;

ALTER TABLE import_template
ADD CONSTRAINT chk_import_template_savings_pocket_pattern CHECK (
    savings_pocket_pattern IS NULL OR char_length(btrim(savings_pocket_pattern)) BETWEEN 1 AND 1000
);

COMMENT ON COLUMN import_template.savings_pocket_pattern IS
'RE2 description pattern of a move to or from the savings pocket; parse-relevant and versioned';
