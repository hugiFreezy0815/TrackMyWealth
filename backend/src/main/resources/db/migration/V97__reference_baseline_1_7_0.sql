-- #323: shipped PDF identification templates extend reference data baseline 1.6.0.
UPDATE reference_package SET is_current = FALSE WHERE is_current;
INSERT INTO reference_package (
    package_version, publication_date, content_manifest, checksum_sha256, imported_at, is_current
) VALUES (
    '1.7.0-baseline', DATE '2026-10-16',
    '{"institution_catalogue": true, "categories": true, "category_source_mappings": true, "fallback_sector_taxonomy": true, "gics_structure_version": true, "import_templates": true}',
    'baseline-shipped-with-application', now(), TRUE
);
