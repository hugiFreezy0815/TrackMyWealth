-- =============================================================================================
-- V112: the portfolio type a member sets (US-04-06, #390)
-- =============================================================================================
-- FR-INS-006/008: a portfolio type describes what a portfolio holds, so the app can show it in
-- the right way. It never constrains which account types may be added (RULE-020);
-- institution_type stays the kind of provider behind the portfolio.
--
-- Only the member's choice is stored. While portfolio_type_override is NULL, the application
-- suggests the type from the portfolio's active accounts each time it reads the portfolio
-- (PortfolioType#suggestedFor): BANK, PORTFOLIO, PILLAR_3A, PENSION_FUND, CRYPTO or MIXED, and
-- PERSONAL_ASSETS for the workspace's default portfolio. Deriving it on read means an account
-- write never touches the portfolio row: it neither changes the portfolio's version (ETag) nor
-- locks it, and no stored value can fall out of step with the accounts.
--
-- PERSONAL_ASSETS cannot be set: it belongs to the default portfolio alone, which always has it
-- and accepts no override.
-- =============================================================================================

ALTER TABLE portfolio
ADD COLUMN portfolio_type_override TEXT CHECK (
    portfolio_type_override IN (
        'BANK', 'PORTFOLIO', 'PILLAR_3A', 'PENSION_FUND', 'CRYPTO', 'MIXED'
    )
),
ADD CONSTRAINT chk_portfolio_personal_assets_type_not_overridden CHECK (
    NOT is_personal_assets_default OR portfolio_type_override IS NULL
);

COMMENT ON COLUMN portfolio.portfolio_type_override IS
'US-04-06, FR-INS-006: the portfolio type the member set, kept whatever accounts are added later. '
'NULL: the type is suggested from the active accounts on every read, never stored.';
