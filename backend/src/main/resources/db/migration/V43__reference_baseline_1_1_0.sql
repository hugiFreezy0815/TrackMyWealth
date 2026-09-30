-- =============================================================================================
-- V43: Reference-data baseline 1.1.0 (US-01-04, FR-REF-001/010)
-- =============================================================================================
-- V19 recorded the shipped baseline as package 1.0.0-baseline. V37 (US-08-01) then extended the
-- shipped content - seven more default categories and the first MCC / ISO 20022 source-code
-- mappings - but kept the old label, so the version an administrator sees no longer said what was
-- loaded. This records the extended content as its own package, 1.1.0-baseline, and makes it
-- current; 1.0.0-baseline stays as history (decision on issue #148).
--
-- Publication dates are when the content entered the application, not when a database happened
-- to run the migration: V19 used CURRENT_DATE, which on any install is the install date.
-- =============================================================================================

UPDATE reference_package
SET publication_date = DATE '2026-09-03'
WHERE package_version = '1.0.0-baseline';

-- uq_reference_package_current allows one current package: clear it before adding the new one.
UPDATE reference_package
SET is_current = FALSE
WHERE is_current;

INSERT INTO reference_package (
    package_version, publication_date, content_manifest, checksum_sha256, imported_at, is_current
)
VALUES (
    '1.1.0-baseline',
    DATE '2026-09-28',
    '{"institution_catalogue": true, "categories": true, "category_source_mappings": true,'
    ' "fallback_sector_taxonomy": true, "gics_structure_version": true}',
    'baseline-shipped-with-application',
    now(),
    TRUE
);

-- The source-code mappings were introduced by the 1.1.0 content, whatever V37 labelled them.
UPDATE category_source_mapping
SET reference_package_version = '1.1.0-baseline'
WHERE reference_package_version = '1.0.0-baseline';
