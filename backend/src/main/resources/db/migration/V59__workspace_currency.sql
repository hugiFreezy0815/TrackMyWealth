-- =============================================================================================
-- V59: Workspace display currency (US-06-05 / FR-USR-007, FR-CUR-010/011)
-- =============================================================================================
-- Existing workspaces inherit the reporting currency of their oldest login-capable member;
-- workspaces without any linked login fall back to CHF.
-- The column keeps DEFAULT 'CHF' on purpose: the application always sets the currency (setup takes
-- the administrator's), the default only serves direct SQL inserts such as test fixtures.
-- =============================================================================================

ALTER TABLE workspace ADD COLUMN currency CHAR(3) NOT NULL DEFAULT 'CHF';

UPDATE workspace AS w
SET currency = coalesce(
    (
        SELECT u.reporting_currency
        FROM workspace_member AS m
        INNER JOIN app_user AS u ON m.id = u.workspace_member_id
        WHERE m.workspace_id = w.id
        ORDER BY m.created_at, u.created_at, u.id
        LIMIT 1
    ),
    'CHF'
);


COMMENT ON COLUMN workspace.currency IS 'US-06-05: workspace-level ISO 4217 display currency.';

-- #223 (V54): every currency figures are held or shown in is a "currency in use", so the FX import
-- stores its cross rates as master data instead of chaining through EUR on each read. The
-- workspace currency is one; record today's and keep it current like the other five tables do.
INSERT INTO fx_rate_currency_in_use (currency)
SELECT DISTINCT w.currency FROM workspace AS w
ON CONFLICT (currency) DO NOTHING;

CREATE TRIGGER workspace_fx_rate_currency_in_use
AFTER INSERT OR UPDATE OF currency ON workspace
FOR EACH ROW EXECUTE FUNCTION trg_fx_rate_currency_in_use('currency');
