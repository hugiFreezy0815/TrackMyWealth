-- =============================================================================================
-- V80: Reference-data baseline 1.2.0 (US-07-15, #275, FR-REF-001/010)
-- =============================================================================================
-- V75-V79 extend the shipped reference content: the first shipped import templates (DKB,
-- PostFinance, Sparkasse, Yuh, LBS) and LBS Süd in the institution catalogue. As V43 did for
-- 1.1.0, this records that content as its own package, 1.2.0-baseline, and makes it current, so
-- the version an administrator sees says what is loaded; 1.1.0-baseline stays as history.
-- =============================================================================================

-- uq_reference_package_current allows one current package: clear it before adding the new one.
UPDATE reference_package
SET is_current = FALSE
WHERE is_current;

INSERT INTO reference_package (
    package_version, publication_date, content_manifest, checksum_sha256, imported_at, is_current
)
VALUES (
    '1.2.0-baseline',
    DATE '2026-10-15',
    '{"institution_catalogue": true, "categories": true, "category_source_mappings": true,'
    ' "fallback_sector_taxonomy": true, "gics_structure_version": true, "import_templates": true}',
    'baseline-shipped-with-application',
    now(),
    TRUE
);

-- FR-REF-004: the package that introduced the catalogue entry V79 added.
UPDATE institution_catalogue
SET reference_package_version = '1.2.0-baseline'
WHERE name = 'LBS Süd' AND country = 'DE';
