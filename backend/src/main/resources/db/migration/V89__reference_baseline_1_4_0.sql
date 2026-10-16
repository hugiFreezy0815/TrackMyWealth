-- =============================================================================================
-- V89: Reference-data baseline 1.4.0 (US-07-21, #324, FR-REF-001/010)
-- =============================================================================================
-- V88 publishes version 2 of the shipped Sparkasse CSV-CAMT and loan statement templates. As V43,
-- V80 and V85 did, this records the shipped content as its own package, 1.4.0-baseline, and makes
-- it current, so the version an administrator sees says what is loaded; 1.3.0-baseline stays as
-- history.
-- =============================================================================================

-- uq_reference_package_current allows one current package: clear it before adding the new one.
UPDATE reference_package
SET is_current = FALSE
WHERE is_current;

INSERT INTO reference_package (
    package_version, publication_date, content_manifest, checksum_sha256, imported_at, is_current
)
VALUES (
    '1.4.0-baseline',
    DATE '2026-10-16',
    '{"institution_catalogue": true, "categories": true, "category_source_mappings": true,'
    ' "fallback_sector_taxonomy": true, "gics_structure_version": true, "import_templates": true}',
    'baseline-shipped-with-application',
    now(),
    TRUE
);
