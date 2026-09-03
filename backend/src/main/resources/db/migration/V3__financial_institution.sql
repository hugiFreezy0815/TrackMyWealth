-- =============================================================================================
-- V3: Financial institutions (containers) and the shared institution catalogue
-- =============================================================================================
-- Section 9 / RULE-001..RULE-003, RULE-020..022, FR-INS-*: a Financial Institution is a generic
-- grouping container. It is never itself an account, has no balance of its own, and its `type`
-- is descriptive metadata only - it must never gate which account types may be created beneath
-- it (FR-INS-008, RULE-020).
-- =============================================================================================

-- FR-INS-007: a curated, shared, seed-data catalogue of CH/DE institutions - not tenant-scoped.
-- Maintained as configuration (see V18 reference-data-distribution framework), searchable at
-- container-creation time, updatable without an application release.
CREATE TABLE institution_catalogue (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                    TEXT NOT NULL,
    country                 CHAR(2) NOT NULL CHECK (country IN ('CH', 'DE')),
    institution_type        TEXT NOT NULL CHECK (institution_type IN (
                                'BANK', 'BROKER', 'PENSION_PROVIDER', 'PENSION_FUND',
                                'ASSET_MANAGER', 'CARD_ISSUER', 'CRYPTO_EXCHANGE', 'INSURER',
                                'PLATFORM', 'OTHER')),
    identifier              TEXT,           -- BIC / LEI where applicable
    logo_url                TEXT,
    available_import_methods JSONB NOT NULL DEFAULT '[]', -- e.g. ["CSV_TEMPLATE","CAMT053","AGGREGATOR"]
    is_active               BOOLEAN NOT NULL DEFAULT TRUE,
    reference_package_version TEXT,        -- which reference-data package introduced/last touched this row (FR-REF-004)
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_institution_catalogue_country ON institution_catalogue(country);
CREATE TRIGGER institution_catalogue_set_updated_at BEFORE UPDATE ON institution_catalogue FOR EACH ROW EXECUTE FUNCTION trg_set_updated_at();

-- Household-scoped instance of an institution (a container). FR-INS-003: users may add
-- institutions not in the catalogue at all (catalogue_institution_id stays NULL).
CREATE TABLE financial_institution (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id                UUID NOT NULL REFERENCES household(id),
    catalogue_institution_id    UUID REFERENCES institution_catalogue(id),
    name                        TEXT NOT NULL,
    country                     CHAR(2),
    institution_type            TEXT NOT NULL DEFAULT 'OTHER' CHECK (institution_type IN (
                                    'BANK', 'BROKER', 'PENSION_PROVIDER', 'PENSION_FUND',
                                    'ASSET_MANAGER', 'CARD_ISSUER', 'CRYPTO_EXCHANGE', 'INSURER',
                                    'PLATFORM', 'PERSONAL_ASSETS', 'OTHER')),
    identifier                  TEXT,
    logo_url                    TEXT,
    -- FR-INS-004 / FR-CUR-002: chosen when the container is created; does not constrain the
    -- currency of accounts or securities inside it (FR-INS-005).
    container_currency          CHAR(3) NOT NULL,
    -- FR-INS-011 / C9: exactly one container per household is flagged as the default "Personal
    -- Assets" container for holdings with no provider (real estate, vehicles, precious metals,
    -- collectibles). Enforced by the partial unique index below.
    is_personal_assets_default  BOOLEAN NOT NULL DEFAULT FALSE,
    status                      TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_financial_institution_household ON financial_institution(household_id);
-- C7: a container may be empty and remain valid - no constraint requires accounts to exist.
-- C9 / FR-INS-011: at most one default Personal Assets container per household.
CREATE UNIQUE INDEX uq_financial_institution_one_personal_assets_per_household
    ON financial_institution(household_id) WHERE is_personal_assets_default;
CREATE TRIGGER financial_institution_set_updated_at BEFORE UPDATE ON financial_institution FOR EACH ROW EXECUTE FUNCTION trg_set_updated_at();
CREATE TRIGGER financial_institution_bump_version BEFORE UPDATE ON financial_institution FOR EACH ROW EXECUTE FUNCTION trg_bump_version();

COMMENT ON TABLE financial_institution IS
    'RULE-001/002/003: a container, not an account. Holds 0..n accounts of heterogeneous types (C1); type never constrains which account types may exist inside it (RULE-020); roll-up may legitimately be negative (C6) and is always derived, never stored (C8).';
