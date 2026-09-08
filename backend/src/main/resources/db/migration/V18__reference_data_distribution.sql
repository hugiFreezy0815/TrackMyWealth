-- =============================================================================================
-- V18: Reference-data distribution and effective-dated configuration
-- =============================================================================================
-- Section 55 / FR-REF-*, NFR-MNT-01: reference data (institution catalogue, import templates,
-- category mappings, pension contribution limits, GICS structure version, fallback taxonomy) is
-- shipped with each release as a baseline and updated between releases only by importing a
-- signed/checksummed package through the administration interface - never by an automatic
-- network call (FR-REF-003, NFR-PRIV-002).
-- =============================================================================================

CREATE TABLE reference_package (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    package_version        TEXT NOT NULL UNIQUE,
    publication_date          DATE NOT NULL,
    compatible_app_version_min  TEXT,
    compatible_app_version_max     TEXT,
    content_manifest                   JSONB NOT NULL, -- what entity types/counts this package touches
    checksum_sha256                       TEXT NOT NULL, -- FR-REF-005: integrity-checked on import
    imported_at                              TIMESTAMPTZ,
    imported_by                                 UUID REFERENCES app_user(id),
    is_current                                     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                                        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_reference_package_current ON reference_package(is_current) WHERE is_current;

COMMENT ON TABLE reference_package IS
    'FR-REF-007: importing a package must not discard a prior version where historical reproducibility depends on it (GICS structure versions, effective-dated pension limits) - previous reference_package rows are retained, not overwritten, and FR-REF-008 rollback simply flips is_current back.';

-- FR-PEN-006: contribution limits, deadlines and account variants for CH/DE as effective-dated
-- configuration, never hard-coded in application logic.
CREATE TABLE pension_scheme_rule (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pension_scheme        TEXT NOT NULL, -- matches account_pension.pension_scheme
    rule_code                 TEXT NOT NULL, -- matches account_pension.contribution_limit_rule_code / withdrawal_rule_code
    effective_from                DATE NOT NULL,
    effective_to                     DATE,
    annual_contribution_limit           NUMERIC(20,4),
    currency                               CHAR(3),
    contribution_deadline_month_day           TEXT, -- e.g. "12-31"
    withdrawal_condition_description             TEXT,
    reference_package_version                       TEXT REFERENCES reference_package(package_version),
    UNIQUE (pension_scheme, rule_code, effective_from)
);
CREATE INDEX idx_pension_scheme_rule_scheme ON pension_scheme_rule(pension_scheme, rule_code);

-- FR-GICS-007/FR-GICS-011: the GICS structure is effective-dated and reviewed annually; the
-- fallback taxonomy must be available without schema change if the licence lapses.
CREATE TABLE gics_structure_version (
    structure_version    TEXT PRIMARY KEY,
    effective_from           DATE NOT NULL,
    effective_to                DATE,
    reference_package_version      TEXT REFERENCES reference_package(package_version)
);

CREATE TABLE fallback_sector_taxonomy (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                  TEXT NOT NULL UNIQUE,
    name_en                   TEXT NOT NULL,
    name_de                      TEXT NOT NULL,
    parent_code                     TEXT REFERENCES fallback_sector_taxonomy(code)
);

COMMENT ON TABLE fallback_sector_taxonomy IS
    'FR-GICS-011/FR-CAT-50: sector grouping degrades to this internal taxonomy without schema change or data loss if the GICS licence is unavailable, delayed or withdrawn (RISK-003).';

-- Trading calendars referenced by listing.trading_calendar_code (V8).
CREATE TABLE trading_calendar (
    code                TEXT PRIMARY KEY,
    name                    TEXT NOT NULL,
    timezone                    TEXT NOT NULL,
    reference_package_version      TEXT REFERENCES reference_package(package_version)
);

CREATE TABLE trading_calendar_holiday (
    trading_calendar_code   TEXT NOT NULL REFERENCES trading_calendar(code),
    holiday_date                DATE NOT NULL,
    description                    TEXT,
    PRIMARY KEY (trading_calendar_code, holiday_date)
);

-- Wires the deferred FK from V3 institution_catalogue.reference_package_version and V13
-- category_source_mapping.reference_package_version, V15 import_template implicitly tracked via
-- template_version (free text, intentionally not FK'd to allow workspace-authored templates).
ALTER TABLE institution_catalogue ADD CONSTRAINT fk_institution_catalogue_reference_package
    FOREIGN KEY (reference_package_version) REFERENCES reference_package(package_version);
ALTER TABLE category_source_mapping ADD CONSTRAINT fk_category_source_mapping_reference_package
    FOREIGN KEY (reference_package_version) REFERENCES reference_package(package_version);
