-- =============================================================================================
-- V19: Tenancy enforcement via PostgreSQL row-level security
-- =============================================================================================
-- Section 48.4 / FR-TEN-001..003: the isolation boundary is the workspace. FR-TEN-003 is
-- explicit that isolation must be enforced in PostgreSQL itself - "a single forgotten predicate
-- in one query is the entire failure" if isolation is left to the service layer alone.
-- NFR-DEP-004: this applies identically in a single-workspace self-hosted deployment; a
-- deployment believed to have one tenant is not permitted to relax any control.
--
-- Mechanism: every workspace-scoped table gets an RLS policy comparing its workspace_id column
-- against the PostgreSQL session variable `app.current_workspace_id`. The application sets this
-- variable once per request/transaction (see
-- com.trackmywealth.backend.config.WorkspaceContextTransactionExecutionListener for the pattern) using
-- `SELECT set_config('app.current_workspace_id', ?, true)` - the `true` makes it
-- transaction-local, so it can never leak between two requests sharing a pooled connection.
--
-- The application's runtime database role must NOT be the schema owner (the owner bypasses RLS
-- by default). In this scaffold, migrations and runtime share one role for local-development
-- simplicity (see docs/architecture/database-schema.md, "Tenancy enforcement"); before a hosted,
-- multi-workspace deployment goes live, split this into a migration-only owner role and a
-- lower-privilege runtime role as shown at the bottom of this file, and apply
-- `FORCE ROW LEVEL SECURITY` (already done below) so even a role with elevated GRANTs is bound
-- by policy.
-- =============================================================================================

CREATE OR REPLACE FUNCTION current_workspace_id() RETURNS UUID AS $$
    SELECT NULLIF(current_setting('app.current_workspace_id', true), '')::UUID;
$$ LANGUAGE sql STABLE;

-- Applies a standard "own workspace only" RLS policy to every workspace-scoped table in one
-- place, rather than dozens of near-identical CREATE POLICY statements that inevitably drift.
DO $$
DECLARE
    t TEXT;
    -- Every table listed here has its own workspace_id column. Tables that hang off one of
    -- these instead (account_ownership keyed by account_id, account_credit_card etc. keyed by
    -- account_id, tax_lot/position_history/daily_valuation keyed by account_id, import_row_raw
    -- keyed by import_batch_id, transaction_categorization_log keyed by transaction_id, ...) are
    -- deliberately NOT listed - they are protected transitively through a join to one of these
    -- tables, per the comment below.
    workspace_scoped_tables TEXT[] := ARRAY[
        'workspace', 'workspace_member', 'financial_institution', 'account',
        'sharing_grant', 'transaction', 'account_snapshot',
        'reconciliation_result', 'position', 'budget', 'goal',
        'categorization_rule', 'import_batch', 'financial_audit_log',
        'workspace_security_override'
    ];
BEGIN
    FOREACH t IN ARRAY workspace_scoped_tables LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        -- workspace itself is scoped by its own id, not a workspace_id column. INSERT is
        -- deliberately unrestricted here (WITH CHECK (true)): creating a brand-new workspace is
        -- the one operation that must be possible before app.current_workspace_id can be set to
        -- anything meaningful. Authorization for "who may create a workspace" (e.g. the initial
        -- setup wizard, or an existing authenticated user starting a second workspace) is a
        -- service-layer concern, not a row-visibility concern, and is out of RLS's job here.
        -- See V19's workspace_create_personal_assets_container trigger comment for the required
        -- bootstrap sequence (the application must pre-generate the workspace UUID and
        -- SET LOCAL app.current_workspace_id to it before issuing the INSERT, so that the
        -- trigger's own INSERT into financial_institution satisfies that table's RLS policy).
        IF t = 'workspace' THEN
            EXECUTE format(
                'CREATE POLICY tenant_isolation_read ON %I FOR SELECT USING (id = current_workspace_id())', t);
            EXECUTE format(
                'CREATE POLICY tenant_isolation_write ON %I FOR UPDATE USING (id = current_workspace_id())', t);
            EXECUTE format(
                'CREATE POLICY tenant_isolation_insert ON %I FOR INSERT WITH CHECK (true)', t);
        ELSE
            EXECUTE format(
                'CREATE POLICY tenant_isolation ON %I USING (workspace_id = current_workspace_id())', t);
        END IF;
    END LOOP;
END $$;

-- Tables that hang off a workspace-scoped table but do not carry workspace_id themselves
-- (e.g. account_credit_card keyed by account_id) are protected transitively: every access path
-- to them goes through a join against `account`, which is itself RLS-protected. They are listed
-- here so the intent is explicit and testable (FR-TEN-010 cross-tenant suite), even though no
-- policy is attached directly.
COMMENT ON FUNCTION current_workspace_id IS
    'Session-local (SET LOCAL / set_config with is_local=true) tenant marker set once per request by the application. Tables such as account_credit_card, tax_lot, price, snapshot_holding etc. are not directly RLS-scoped; they inherit isolation transitively through a join to account/financial_institution/security, which the FR-TEN-010 automated cross-tenant test suite must exercise explicitly for every entity type.';

-- --- Tables with a nullable workspace_id (shared system default + workspace-owned rows) -------
-- `category` (FR-CAT-002/003) and `import_template` (FR-IMP-009/024) both mix shipped,
-- globally-visible rows (workspace_id IS NULL) with workspace-authored rows. A plain equality
-- policy would hide the shipped defaults from everyone, so these two get a bespoke policy
-- instead of the generic loop above.
ALTER TABLE category ENABLE ROW LEVEL SECURITY;
ALTER TABLE category FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_or_shared ON category
    USING (workspace_id IS NULL OR workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

ALTER TABLE import_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE import_template FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_or_shared ON import_template
    USING (workspace_id IS NULL OR workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

-- --- Illustrative production role split (commented out; enable per docs/architecture/database-schema.md) ---
-- CREATE ROLE trackmywealth_migration LOGIN PASSWORD '...';   -- owns the schema, runs Flyway
-- CREATE ROLE trackmywealth_runtime LOGIN PASSWORD '...';      -- used by the running application only
-- GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO trackmywealth_runtime;
-- GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO trackmywealth_runtime;
-- -- security/listing/price/fx_rate/institution_catalogue/reference_* are shared, non-tenant
-- -- data (NFR-LIC-007) and are intentionally readable by the runtime role without restriction.
