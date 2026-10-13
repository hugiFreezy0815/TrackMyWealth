-- US-07-18: versioned template rules for identifying card refunds without a type column.
ALTER TABLE import_template
ADD COLUMN description_type_patterns JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN import_template.description_type_patterns IS
    'RE2 description pattern per target transaction type; parse-relevant and versioned';
