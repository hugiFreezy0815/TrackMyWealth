-- =============================================================================================
-- V111: the container below the workspace is the Portfolio (US-04-06, #390)
-- =============================================================================================
-- PO decision 2026-10-20 (spec v5, FR-INS-001..014): the level below the workspace is a Portfolio
-- the user names freely, with an optional real institution behind it. The table, its currency
-- column and the account's link to it take that name before the frontend binds to the API.
-- "Institution" keeps its meaning only where it is the real bank or broker: institution_catalogue,
-- portfolio.catalogue_institution_id and portfolio.institution_type stay as they are.
--
-- A rename keeps the rows, row-level security (policies are named tenant_isolation, not after the
-- table), grants, foreign keys and trigger bindings. It does not touch names derived from the old
-- table name, nor code that spells it out:
--   * constraint, index and trigger names are renamed below;
--   * trg_workspace_create_personal_assets_container (V19) inserts into the table by name;
--   * the currency-in-use trigger (V54) passes its column name as a string argument.
-- =============================================================================================

ALTER TABLE financial_institution RENAME TO portfolio;
ALTER TABLE portfolio RENAME COLUMN container_currency TO portfolio_currency;
ALTER TABLE account RENAME COLUMN financial_institution_id TO portfolio_id;

-- Every constraint, index and trigger on the two tables whose name still carries the old one:
-- the primary key, check and foreign-key constraints Postgres named itself (V3, V4) as well as
-- the ones V3/V4 named explicitly.
DO $$
DECLARE
    object_name TEXT;
BEGIN
    FOR object_name IN
        SELECT con.conname FROM pg_constraint AS con
        WHERE con.conrelid = 'portfolio'::REGCLASS AND con.conname LIKE '%financial_institution%'
    LOOP
        EXECUTE format(
            'ALTER TABLE portfolio RENAME CONSTRAINT %I TO %I',
            object_name, replace(object_name, 'financial_institution', 'portfolio'));
    END LOOP;

    FOR object_name IN
        SELECT con.conname FROM pg_constraint AS con
        WHERE con.conrelid = 'account'::REGCLASS AND con.conname LIKE '%financial_institution%'
    LOOP
        EXECUTE format(
            'ALTER TABLE account RENAME CONSTRAINT %I TO %I',
            object_name, replace(object_name, 'financial_institution', 'portfolio'));
    END LOOP;

    -- Indexes backing a constraint were renamed with it above.
    FOR object_name IN
        SELECT idx.indexname FROM pg_indexes AS idx
        WHERE idx.tablename = 'portfolio' AND idx.indexname LIKE '%financial_institution%'
    LOOP
        EXECUTE format(
            'ALTER INDEX %I RENAME TO %I',
            object_name, replace(object_name, 'financial_institution', 'portfolio'));
    END LOOP;

    FOR object_name IN
        SELECT trg.tgname FROM pg_trigger AS trg
        WHERE
            trg.tgrelid = 'portfolio'::REGCLASS
            AND NOT trg.tgisinternal
            AND trg.tgname LIKE '%financial_institution%'
    LOOP
        EXECUTE format(
            'ALTER TRIGGER %I ON portfolio RENAME TO %I',
            object_name, replace(object_name, 'financial_institution', 'portfolio'));
    END LOOP;
END;
$$;

ALTER INDEX idx_account_institution RENAME TO idx_account_portfolio;

-- V19: the default Personal Assets portfolio every new workspace gets (FR-INS-011/C9).
CREATE OR REPLACE FUNCTION trg_workspace_create_personal_assets_container()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO portfolio (
        workspace_id, name, institution_type, portfolio_currency, is_personal_assets_default
    )
    VALUES (NEW.id, 'Personal Assets', 'PERSONAL_ASSETS', 'CHF', TRUE);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- V54: the trigger reads the currency column named by its argument, which still says
-- container_currency after the rename.
DROP TRIGGER portfolio_fx_rate_currency_in_use ON portfolio;
CREATE TRIGGER portfolio_fx_rate_currency_in_use
AFTER INSERT OR UPDATE OF portfolio_currency ON portfolio
FOR EACH ROW EXECUTE FUNCTION trg_fx_rate_currency_in_use('portfolio_currency');

COMMENT ON TABLE portfolio IS
'RULE-001/002/003, FR-INS-001: a user-named Portfolio, not an account. Holds 0..n accounts of '
'heterogeneous types (C1); neither institution_type nor portfolio_type ever constrains which '
'account types may exist inside it (RULE-020, FR-INS-008); roll-up may legitimately be negative '
'(C6) and is always derived, never stored (C8).';
