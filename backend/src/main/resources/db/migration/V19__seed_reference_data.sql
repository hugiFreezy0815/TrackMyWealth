-- =============================================================================================
-- V19: Baseline reference-data seed
-- =============================================================================================
-- FR-REF-001: every release ships a complete baseline reference-data set. A fresh installation
-- must never start with an empty institution catalogue, no import templates or no categories.
-- This migration is that baseline; later updates arrive as imported reference_package files
-- (V18), not as further Flyway migrations, per FR-REF-002/003 (manual import is the update
-- channel; no automatic network retrieval, in either topology).
--
-- Deliberately sequenced before V20 (row-level security): this migration inserts system-default
-- rows (workspace_id IS NULL) into `category`. V20 enables FORCE ROW LEVEL SECURITY with a policy
-- whose WITH CHECK requires workspace_id = current_workspace_id() for writes, which a NULL-
-- workspace_id seed row can never satisfy. Running before V20 avoids depending on the migration
-- role having BYPASSRLS/superuser to seed shared defaults.
-- =============================================================================================

INSERT INTO reference_package (id, package_version, publication_date, content_manifest, checksum_sha256, imported_at, is_current)
VALUES (
    gen_random_uuid(), '1.0.0-baseline', CURRENT_DATE,
    '{"institution_catalogue": true, "categories": true, "fallback_sector_taxonomy": true, "gics_structure_version": true}',
    'baseline-shipped-with-application', now(), TRUE
);

-- --- FR-INS-007: seed institution catalogue -------------------------------------------------
-- Representative starting set spanning both markets and several institution types, illustrating
-- that type never constrains account type (FR-INS-008). The full CH/DE list is a deferred
-- delivery input per section 28.1.1 / INPUT-001..005 - see docs/user-stories/EPIC-07-*.md.
INSERT INTO institution_catalogue (name, country, institution_type, available_import_methods) VALUES
    ('PostFinance', 'CH', 'BANK',              '["CSV_TEMPLATE", "CAMT053"]'),
    ('Yuh',         'CH', 'BANK',              '["CSV_TEMPLATE"]'),
    ('VIAC',        'CH', 'PENSION_PROVIDER',  '["CSV_TEMPLATE"]'),
    ('DKB',         'DE', 'BANK',              '["CSV_TEMPLATE", "CAMT053"]'),
    ('Sparkasse',   'DE', 'BANK',              '["CAMT053"]');

-- --- FR-CAT-002: default reporting-category taxonomy (system default, workspace_id NULL) ----
INSERT INTO category (id, workspace_id, code, name_en, name_de, is_system_default) VALUES
    (gen_random_uuid(), NULL, 'INCOME',           'Income',           'Einkommen',          TRUE),
    (gen_random_uuid(), NULL, 'HOUSING',          'Housing',          'Wohnen',             TRUE),
    (gen_random_uuid(), NULL, 'GROCERIES',        'Groceries',        'Lebensmittel',       TRUE),
    (gen_random_uuid(), NULL, 'TRANSPORT',        'Transport',        'Transport',          TRUE),
    (gen_random_uuid(), NULL, 'INSURANCE',        'Insurance',        'Versicherung',       TRUE),
    (gen_random_uuid(), NULL, 'LEISURE',          'Leisure',          'Freizeit',           TRUE),
    (gen_random_uuid(), NULL, 'SAVINGS_INVEST',   'Savings & Investing','Sparen & Investieren', TRUE),
    (gen_random_uuid(), NULL, 'TRANSFER_INTERNAL','Internal Transfer','Interne Überweisung',TRUE),
    (gen_random_uuid(), NULL, 'UNCATEGORIZED',    'Uncategorized',    'Nicht kategorisiert',TRUE),
    (gen_random_uuid(), NULL, 'OTHER',            'Other',            'Sonstiges',          TRUE);

-- --- FR-GICS-011/FR-CAT-50: fallback sector taxonomy, always available regardless of GICS
-- licensing status (RISK-003) ------------------------------------------------------------------
INSERT INTO fallback_sector_taxonomy (code, name_en, name_de) VALUES
    ('ENERGY',            'Energy',                 'Energie'),
    ('MATERIALS',         'Materials',              'Grundstoffe'),
    ('INDUSTRIALS',       'Industrials',            'Industrie'),
    ('CONSUMER_DISCR',    'Consumer Discretionary', 'Zyklische Konsumgüter'),
    ('CONSUMER_STAPLES',  'Consumer Staples',       'Nichtzyklische Konsumgüter'),
    ('HEALTH_CARE',       'Health Care',            'Gesundheitswesen'),
    ('FINANCIALS',        'Financials',             'Finanzwesen'),
    ('INFO_TECH',         'Information Technology', 'Informationstechnologie'),
    ('COMMUNICATION',     'Communication Services', 'Kommunikationsdienste'),
    ('UTILITIES',         'Utilities',              'Versorger'),
    ('REAL_ESTATE',       'Real Estate',            'Immobilien'),
    ('NOT_CLASSIFIED',    'Not classified',         'Nicht klassifiziert');

INSERT INTO gics_structure_version (structure_version, effective_from, reference_package_version)
VALUES ('2023-03', '2023-03-17', '1.0.0-baseline');

-- =============================================================================================
-- FR-INS-011/C9: every workspace must have exactly one default "Personal Assets" container,
-- created automatically so the account tree is always uniform and no account is ever orphaned.
-- =============================================================================================
CREATE OR REPLACE FUNCTION trg_workspace_create_personal_assets_container()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO financial_institution (workspace_id, name, institution_type, container_currency, is_personal_assets_default)
    VALUES (NEW.id, 'Personal Assets', 'PERSONAL_ASSETS', 'CHF', TRUE);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspace_create_personal_assets_container
    AFTER INSERT ON workspace
    FOR EACH ROW EXECUTE FUNCTION trg_workspace_create_personal_assets_container();

COMMENT ON TRIGGER workspace_create_personal_assets_container ON workspace IS
    'FR-INS-011: default currency CHF is a placeholder - the onboarding flow (EPIC 01/03 user stories) should update it to the workspace''s actual reporting currency immediately after creation.';

-- Workspace bootstrap sequence required by the RLS policies in V19 (documented here, not
-- enforced by SQL, since it is a client-side call ordering concern):
--   1. Application generates a new UUID client-side for the workspace.
--   2. Application opens a transaction and executes
--      SELECT set_config('app.current_workspace_id', '<the new uuid>', true);
--   3. Application executes INSERT INTO workspace (id, name, ...) VALUES ('<the new uuid>', ...);
--      -> satisfies workspace's tenant_isolation_insert policy (WITH CHECK (true), so this step
--         would succeed regardless, but steps 2 and 4 need the session variable set either way);
--      -> fires workspace_create_personal_assets_container, whose INSERT INTO
--         financial_institution now satisfies that table's tenant_isolation policy because
--         workspace_id = current_workspace_id().
--   4. Application inserts the first workspace_member (the workspace's own admin) and any
--      further seed rows in the same transaction, still under the same session variable.
-- See docs/user-stories/EPIC-01-application-foundation.md, US-01-03 (initial administrator /
-- first workspace bootstrap) for the corresponding acceptance criteria.

