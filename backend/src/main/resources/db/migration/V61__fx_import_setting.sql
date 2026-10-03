-- =============================================================================================
-- V61: FX import interval set by an administrator (US-06-07, #227, FR-CUR-007/009)
-- =============================================================================================
-- One global row (no workspace_id: the FX import serves the whole installation, FR-TEN-007 keeps
-- it outside every workspace). import_interval_hours NULL means "not set": FX_IMPORT_CRON
-- (app.fx.import.import-cron) applies as before. Once an administrator sets it, it wins over the
-- environment at every start, so a restart never undoes it. The row exists from the start, so
-- the setting always has a version for If-Match (ADR 0004), also before the first change.
-- =============================================================================================

CREATE TABLE fx_import_setting (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Allows exactly one row: the column is always TRUE and unique.
    singleton BOOLEAN NOT NULL DEFAULT TRUE UNIQUE CHECK (singleton),
    import_interval_hours SMALLINT CHECK (import_interval_hours IN (1, 2, 6, 12, 24)),
    updated_by UUID REFERENCES app_user (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INTEGER NOT NULL DEFAULT 0 -- noqa: RF04
);

CREATE TRIGGER fx_import_setting_set_updated_at BEFORE UPDATE ON fx_import_setting
FOR EACH ROW EXECUTE FUNCTION trg_set_updated_at();
CREATE TRIGGER fx_import_setting_bump_version BEFORE UPDATE ON fx_import_setting
FOR EACH ROW EXECUTE FUNCTION trg_bump_version();

INSERT INTO fx_import_setting (import_interval_hours) VALUES (NULL);

COMMENT ON TABLE fx_import_setting IS
'US-06-07: global FX import settings an administrator changes at runtime (one row).';
COMMENT ON COLUMN fx_import_setting.import_interval_hours IS
'Hours between scheduled FX imports, clock-aligned in the import zone; NULL: FX_IMPORT_CRON.';
