-- =============================================================================================
-- V37: Automatic categorization baseline (US-08-01, FR-CAT-002/005/009/010)
-- =============================================================================================
-- Three parts:
--   1. Six more shipped default categories, so common card and bank codes have a precise home
--      instead of LEISURE or OTHER: DINING and TRAVEL under LEISURE, UTILITIES under HOUSING, and
--      the top-level HEALTH, SHOPPING and TAXES.
--   2. The first shipped source-code mappings (category_source_mapping, V13): ~40 common MCC
--      (ISO 18245) and ISO 20022 purpose codes. Configuration data, not code (FR-CAT-010) - a
--      reference package can extend it later. MCC 6011 (ATM withdrawal) is deliberately unmapped:
--      moving cash is not spending.
--   3. A one-off backfill of existing cash and card rows, which predate categorization: a mapped
--      MCC where there is one, else UNCATEGORIZED (FR-CAT-013: visible, never silently "Other").
--      No workspace had rules before this migration, and fuzzy matching has nothing to learn
--      from yet, so those layers have nothing to contribute here.
-- =============================================================================================

-- --- 1. new shipped defaults ------------------------------------------------------------------
INSERT INTO category (workspace_id, parent_category_id, code, name_en, name_de, is_system_default)
VALUES
(NULL, NULL, 'HEALTH', 'Health', 'Gesundheit', TRUE),
(NULL, NULL, 'SHOPPING', 'Shopping', 'Einkäufe', TRUE),
(NULL, NULL, 'TAXES', 'Taxes', 'Steuern', TRUE);

INSERT INTO category (workspace_id, parent_category_id, code, name_en, name_de, is_system_default)
SELECT
    NULL,
    parent.id,
    child.code,
    child.name_en,
    child.name_de,
    TRUE
FROM (VALUES
    ('LEISURE', 'DINING', 'Dining Out', 'Auswärts essen'),
    ('LEISURE', 'TRAVEL', 'Travel', 'Reisen'),
    ('HOUSING', 'UTILITIES', 'Utilities & Telecom', 'Energie & Telekommunikation')
) AS child (parent_code, code, name_en, name_de)
INNER JOIN category AS parent
    ON parent.code = child.parent_code AND parent.workspace_id IS NULL;

-- --- 2. shipped source-code mappings ----------------------------------------------------------
INSERT INTO category_source_mapping (source_standard, source_code, category_id,
    reference_package_version)
SELECT
    mapping.source_standard,
    mapping.source_code,
    target.id,
    '1.0.0-baseline'
FROM (VALUES
    ('MCC', '5411', 'GROCERIES'), -- grocery stores, supermarkets
    ('MCC', '5422', 'GROCERIES'), -- meat provisioners
    ('MCC', '5441', 'GROCERIES'), -- candy and confectionery
    ('MCC', '5451', 'GROCERIES'), -- dairy stores
    ('MCC', '5462', 'GROCERIES'), -- bakeries
    ('MCC', '5499', 'GROCERIES'), -- misc. food stores
    ('MCC', '5812', 'DINING'), -- restaurants
    ('MCC', '5813', 'DINING'), -- bars
    ('MCC', '5814', 'DINING'), -- fast food
    ('MCC', '4511', 'TRAVEL'), -- airlines
    ('MCC', '4722', 'TRAVEL'), -- travel agencies
    ('MCC', '7011', 'TRAVEL'), -- hotels
    ('MCC', '4111', 'TRANSPORT'), -- commuter transport
    ('MCC', '4112', 'TRANSPORT'), -- passenger railways
    ('MCC', '4121', 'TRANSPORT'), -- taxis, ride-hailing
    ('MCC', '4131', 'TRANSPORT'), -- bus lines
    ('MCC', '5541', 'TRANSPORT'), -- service stations
    ('MCC', '5542', 'TRANSPORT'), -- automated fuel dispensers
    ('MCC', '7523', 'TRANSPORT'), -- parking
    ('MCC', '4814', 'UTILITIES'), -- telecommunication
    ('MCC', '4900', 'UTILITIES'), -- electricity, gas, water
    ('MCC', '5912', 'HEALTH'), -- pharmacies
    ('MCC', '8011', 'HEALTH'), -- doctors
    ('MCC', '8021', 'HEALTH'), -- dentists
    ('MCC', '8062', 'HEALTH'), -- hospitals
    ('MCC', '5311', 'SHOPPING'), -- department stores
    ('MCC', '5651', 'SHOPPING'), -- clothing
    ('MCC', '5732', 'SHOPPING'), -- electronics
    ('MCC', '5942', 'SHOPPING'), -- book stores
    ('MCC', '5815', 'LEISURE'), -- digital media
    ('MCC', '5817', 'LEISURE'), -- digital applications
    ('MCC', '5818', 'LEISURE'), -- digital goods, subscriptions
    ('MCC', '7832', 'LEISURE'), -- cinemas
    ('MCC', '7941', 'LEISURE'), -- sports clubs
    ('MCC', '7997', 'LEISURE'), -- fitness and membership clubs
    ('MCC', '6300', 'INSURANCE'), -- insurance premiums
    ('MCC', '9311', 'TAXES'), -- tax payments
    ('ISO20022_PURPOSE', 'SALA', 'INCOME'), -- salary
    ('ISO20022_PURPOSE', 'PENS', 'INCOME'), -- pension payment
    ('ISO20022_PURPOSE', 'RENT', 'HOUSING'), -- rent
    ('ISO20022_PURPOSE', 'ELEC', 'UTILITIES'), -- electricity bill
    ('ISO20022_PURPOSE', 'INSU', 'INSURANCE'), -- insurance premium
    ('ISO20022_PURPOSE', 'TAXS', 'TAXES') -- tax payment
) AS mapping (source_standard, source_code, category_code)
INNER JOIN category AS target
    ON target.code = mapping.category_code AND target.workspace_id IS NULL;

-- --- 3. backfill existing cash and card rows --------------------------------------------------
-- transaction is FORCE ROW LEVEL SECURITY (V20). With row_security off, a role that bypasses RLS
-- (today's migration role) sees every workspace, and any other role gets an error instead of
-- silently updating nothing.
SET LOCAL row_security = off;

-- The types CategorizationService categorizes; SETTLEMENT and investment types get no category.
CREATE TEMPORARY TABLE v37_eligible ON COMMIT DROP AS
SELECT
    t.id,
    t.workspace_id,
    lpad(t.raw_source_data ->> 'mcc', 4, '0') AS mcc
FROM transaction AS t
WHERE
    t.category_id IS NULL
    AND t.transaction_type IN (
        'INCOME', 'EXPENSE', 'DEPOSIT', 'WITHDRAWAL', 'INTEREST', 'FEE', 'TAX', 'REFUND',
        'CREDIT_CARD_PURCHASE'
    );

-- A mapped category counts only if it and its parent are active for the row's workspace, with
-- the workspace's own overrides (V34) applied - the same rule CategoryService uses. The new
-- defaults are at most two levels deep, so the parent is the only ancestor.
CREATE TEMPORARY TABLE v37_mapped ON COMMIT DROP AS
SELECT
    e.id AS transaction_id,
    m.category_id
FROM v37_eligible AS e
INNER JOIN category_source_mapping AS m
    ON m.source_standard = 'MCC' AND m.source_code = e.mcc
INNER JOIN category AS c ON c.id = m.category_id
LEFT JOIN category AS p ON p.id = c.parent_category_id
LEFT JOIN workspace_category_override AS co
    ON co.workspace_id = e.workspace_id AND co.category_id = c.id
LEFT JOIN workspace_category_override AS po
    ON po.workspace_id = e.workspace_id AND po.category_id = p.id
WHERE
    coalesce(co.is_active, c.is_active)
    AND (p.id IS NULL OR coalesce(po.is_active, p.is_active));

INSERT INTO transaction_categorization_log (transaction_id, category_id, assigned_by)
SELECT
    transaction_id,
    category_id,
    'SOURCE_CODE'
FROM v37_mapped;

UPDATE transaction AS t
SET category_id = m.category_id
FROM v37_mapped AS m
WHERE t.id = m.transaction_id;

-- Everything else lands in the always-active, protected UNCATEGORIZED default, with no log row:
-- the log records how a category was assigned, and nothing assigned this one.
UPDATE transaction AS t
SET category_id = (SELECT id FROM category WHERE workspace_id IS NULL AND code = 'UNCATEGORIZED')
FROM v37_eligible AS e
WHERE t.id = e.id AND t.category_id IS NULL;
