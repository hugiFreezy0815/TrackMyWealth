-- =============================================================================================
-- V104: Reference-data baseline 1.8.0 (US-07-22, #325, FR-REF-001/010)
-- =============================================================================================
-- V103 ships version 3 of the Yuh statement template. As V43, V80, V85, V89, V92, V94 and V97
-- did, this records the shipped content as its own package, 1.8.0-baseline, and makes it
-- current; 1.7.0-baseline stays as history.
-- =============================================================================================

-- uq_reference_package_current allows one current package: clear it before adding the new one.
UPDATE reference_package
SET is_current = FALSE
WHERE is_current;

INSERT INTO reference_package (
    package_version, publication_date, content_manifest, checksum_sha256, imported_at, is_current
)
VALUES (
    '1.8.0-baseline',
    DATE '2026-10-19',
    '{"institution_catalogue": true, "categories": true, "category_source_mappings": true,'
    ' "fallback_sector_taxonomy": true, "gics_structure_version": true, "import_templates": true}',
    'baseline-shipped-with-application',
    now(),
    TRUE
);
