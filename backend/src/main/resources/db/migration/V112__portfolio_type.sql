-- =============================================================================================
-- V112: a descriptive portfolio type, suggested from the portfolio's accounts (US-04-06, #390)
-- =============================================================================================
-- FR-INS-006/008: portfolio_type describes what a portfolio holds, so the app can show it in the
-- right way. It never constrains which account types may be added (RULE-020); institution_type
-- stays the kind of provider behind the portfolio.
--
-- While portfolio_type_overridden is false the application recomputes the type whenever an
-- account is added, moved, archived or restored (AccountService#refreshPortfolioTypes):
--   * the Personal Assets default -> PERSONAL_ASSETS;
--   * no active account -> BANK (the column default);
--   * every active account in one family -> that family: cash, savings and cards -> BANK,
--     securities and managed mandates -> PORTFOLIO, Pillar 3a -> PILLAR_3A, other pensions and
--     vested benefits -> PENSION_FUND, crypto -> CRYPTO;
--   * anything else, including any property, mortgage, loan or other custom asset -> MIXED.
-- Once the user sets a type, it is kept as set.
-- =============================================================================================

ALTER TABLE portfolio
ADD COLUMN portfolio_type TEXT NOT NULL DEFAULT 'BANK' CHECK (
    portfolio_type IN (
        'BANK', 'PORTFOLIO', 'PILLAR_3A', 'PENSION_FUND', 'CRYPTO', 'PERSONAL_ASSETS', 'MIXED'
    )
),
ADD COLUMN portfolio_type_overridden BOOLEAN NOT NULL DEFAULT FALSE;

-- New workspaces: their default portfolio is created with its own type.
CREATE OR REPLACE FUNCTION trg_workspace_create_personal_assets_container()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO portfolio (
        workspace_id,
        name,
        institution_type,
        portfolio_currency,
        is_personal_assets_default,
        portfolio_type
    )
    VALUES (NEW.id, 'Personal Assets', 'PERSONAL_ASSETS', 'CHF', TRUE, 'PERSONAL_ASSETS');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Existing portfolios get the type the application would have suggested. Forced RLS would hide
-- every row from the migration role, so it is lifted for the backfill only (as in V75 and V109).
-- Changed rows get a new version like any other update.
ALTER TABLE portfolio NO FORCE ROW LEVEL SECURITY;
ALTER TABLE account NO FORCE ROW LEVEL SECURITY;

WITH account_family AS (
    SELECT
        acc.portfolio_id,
        CASE
            WHEN acc.account_type IN ('CASH', 'SAVINGS', 'CREDIT_CARD') THEN 'BANK'
            WHEN acc.account_type IN ('SECURITIES', 'MANAGED_MANDATE') THEN 'PORTFOLIO'
            WHEN acc.account_type = 'CRYPTO' THEN 'CRYPTO'
            WHEN
                acc.account_type = 'PENSION' AND pension.pension_scheme = 'CH_PILLAR_3A'
                THEN 'PILLAR_3A'
            WHEN acc.account_type IN ('PENSION', 'VESTED_BENEFITS') THEN 'PENSION_FUND'
            -- Property, mortgage, loan and other custom assets belong to no family.
            ELSE 'MIXED'
        END AS type_family
    FROM account AS acc
    LEFT JOIN account_pension AS pension ON acc.id = pension.account_id
    WHERE acc.status = 'ACTIVE'
),

suggested AS (
    SELECT
        portfolio_id,
        CASE
            WHEN count(DISTINCT type_family) = 1 THEN min(type_family) ELSE 'MIXED'
        END AS portfolio_type
    FROM account_family
    GROUP BY portfolio_id
)

UPDATE portfolio
SET portfolio_type = suggested.portfolio_type
FROM suggested
WHERE
    portfolio.id = suggested.portfolio_id
    AND NOT portfolio.is_personal_assets_default
    AND portfolio.portfolio_type <> suggested.portfolio_type;

UPDATE portfolio
SET portfolio_type = 'PERSONAL_ASSETS'
WHERE is_personal_assets_default AND portfolio_type <> 'PERSONAL_ASSETS';

ALTER TABLE account FORCE ROW LEVEL SECURITY;
ALTER TABLE portfolio FORCE ROW LEVEL SECURITY;
