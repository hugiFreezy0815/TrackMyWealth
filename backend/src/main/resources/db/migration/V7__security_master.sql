-- =============================================================================================
-- V7: Security Master
-- =============================================================================================
-- Section 19 / DM-25..28, RULE-012/013/017: one global, shared security master record per
-- instrument, referenced by every position across every workspace - never duplicated per
-- portfolio (DM-25). Created only on first reference (FR-SMD-001, lazy instantiation) and
-- retained forever once referenced by any historical activity (FR-SMD-002/005).
--
-- This table carries NO workspace_id / tenant column by design: it is shared reference data,
-- not tenant data (NFR-LIC-007), and is therefore excluded from the row-level security policies
-- in V19. Per-user overrides of a shared field are modelled as a separate table keyed by
-- (workspace_id, security_id) so an override never leaks into another workspace's view (DM-25).
-- =============================================================================================

CREATE TABLE issuer (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name             TEXT NOT NULL,
    lei               TEXT,
    country            CHAR(2),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE security (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- FR-SMD-008/FR-IMD-01: ISIN is the authoritative primary identifier where one exists; a
    -- synthetic key covers instruments without one. Additional identifiers (Valor, WKN, CUSIP,
    -- SEDOL, FIGI, ticker) live in security_identifier below - see FR-IMD-02.
    isin                        CHAR(12) UNIQUE,
    synthetic_key                 TEXT UNIQUE,
    legal_name                     TEXT NOT NULL,
    display_name                    TEXT NOT NULL,
    description_en                   TEXT,
    description_de                    TEXT,
    issuer_id                          UUID REFERENCES issuer(id),

    instrument_type                     TEXT,     -- e.g. EQUITY, ETF, FUND, BOND, CRYPTO, DERIVATIVE
    cfi_code                             CHAR(6),  -- ISO 10962, primary asset-class backbone (FR-CLS-003)

    -- FR-IMD-06/07: four distinct country fields - never conflate these.
    security_country                       CHAR(2),
    issuer_country                          CHAR(2),
    risk_country                             CHAR(2),
    withholding_source_country                 CHAR(2), -- FR-TAXR-002: explains reduced dividend receipts only, no reclaim logic

    denomination_currency                       CHAR(3) NOT NULL,
    is_hedged_share_class                        BOOLEAN NOT NULL DEFAULT FALSE,

    -- GICS: 8-digit sub-industry code, Sector/Industry Group/Industry derived by truncation, not
    -- stored as separate columns (FR-GICS-006). Equity-nature instruments only (FR-GICS-003).
    gics_sub_industry_code                        CHAR(8),
    gics_structure_version                         TEXT,   -- FR-GICS-007: structure is effective-dated

    -- SNB classification - orthogonal to GICS, never derived from it (FR-SNB-003). Exact code
    -- list is TBD per OPEN-007/D12; the columns are intentionally free-text pending that decision.
    snb_institutional_sector_code                    TEXT,
    snb_securities_category_code                      TEXT,

    -- Fund-specific attributes (FR-IMD-12), NULL for non-funds.
    fund_ter_percent                                    NUMERIC(6,4),
    fund_is_distributing                                 BOOLEAN,
    fund_replication_method                               TEXT,
    fund_share_class                                       TEXT,
    fund_benchmark                                          TEXT,

    -- Bond-specific attributes (FR-IMD-12), NULL for non-bonds.
    bond_coupon_percent                                      NUMERIC(6,4),
    bond_maturity_date                                        DATE,
    bond_payment_frequency                                     TEXT,
    bond_rating                                                 TEXT,
    bond_seniority                                               TEXT,

    -- FR-SMD-003/FR-STA-004: lifecycle state.
    state                                                        TEXT NOT NULL DEFAULT 'ACTIVE'
                                                                    CHECK (state IN ('ACTIVE', 'INACTIVE', 'DELISTED', 'SUPERSEDED')),
    successor_security_id                                         UUID REFERENCES security(id), -- FR-SMD-010/FR-IMD-16

    created_at                                                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                                                      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CHECK (isin IS NOT NULL OR synthetic_key IS NOT NULL)
);
CREATE INDEX idx_security_state ON security(state);
CREATE INDEX idx_security_gics ON security(gics_sub_industry_code);
CREATE TRIGGER security_set_updated_at BEFORE UPDATE ON security FOR EACH ROW EXECUTE FUNCTION trg_set_updated_at();

COMMENT ON TABLE security IS
    'DM-25: global and shared across all workspaces. FR-SMD-005: deletable only when no transaction or position in any workspace portfolio references it - never merely because nobody currently holds it.';

-- FR-IMD-02/FR-SMD-008: additional identifiers, one row per (security, identifier_type). ISIN
-- stays on the security row itself since it is the primary key candidate; everything else here.
CREATE TABLE security_identifier (
    security_id      UUID NOT NULL REFERENCES security(id),
    identifier_type    TEXT NOT NULL CHECK (identifier_type IN ('VALOR', 'WKN', 'CUSIP', 'SEDOL', 'FIGI', 'TICKER')),
    identifier_value     TEXT NOT NULL,
    PRIMARY KEY (security_id, identifier_type)
);
CREATE INDEX idx_security_identifier_value ON security_identifier(identifier_type, identifier_value);

-- FR-CLS-004/DM-24/RULE-027: a fund is a weighted set of asset classes, never a single class.
-- This is a schema-level requirement even before look-through data is licensed (FR-CLS-005).
CREATE TABLE security_asset_class_weight (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    security_id         UUID NOT NULL REFERENCES security(id),
    asset_class            TEXT NOT NULL CHECK (asset_class IN (
                              'EQUITY', 'FIXED_INCOME', 'CASH_EQUIVALENT', 'REAL_ESTATE',
                              'COMMODITY', 'PRECIOUS_METAL', 'CRYPTOCURRENCY', 'ALTERNATIVES',
                              'MULTI_ASSET', 'DERIVATIVES', 'OTHER')),
    weight                   NUMERIC(6,5) NOT NULL CHECK (weight > 0 AND weight <= 1),
    is_estimated               BOOLEAN NOT NULL DEFAULT FALSE, -- FR-CLS-005: declared-allocation fallback
    effective_date               DATE NOT NULL DEFAULT CURRENT_DATE, -- FR-SMD-009: effective-dated reference data
    source                         TEXT,
    UNIQUE (security_id, asset_class, effective_date)
);
CREATE INDEX idx_security_asset_class_weight_security ON security_asset_class_weight(security_id);

-- FR-SMD-012/013 / RULE-031: per-field provenance and user override. Modelled generically rather
-- than as a column-per-field flag set, since the set of overridable fields grows over time.
CREATE TABLE security_field_provenance (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    security_id         UUID NOT NULL REFERENCES security(id),
    field_name            TEXT NOT NULL,
    source                  TEXT NOT NULL,       -- e.g. PROVIDER:refinitiv, USER_OVERRIDE
    retrieved_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    confidence                  TEXT,
    is_user_override             BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (security_id, field_name)
);

-- DM-25: workspace-scoped override of a shared security-master field. Never merged back into
-- the shared `security` row; read as an overlay at query time so it cannot leak between tenants.
CREATE TABLE workspace_security_override (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id         UUID NOT NULL REFERENCES workspace(id),
    security_id            UUID NOT NULL REFERENCES security(id),
    field_name                TEXT NOT NULL,
    override_value               TEXT NOT NULL,
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, security_id, field_name)
);
CREATE INDEX idx_workspace_security_override_workspace ON workspace_security_override(workspace_id);
