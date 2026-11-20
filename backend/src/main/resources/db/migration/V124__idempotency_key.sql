-- US-29-14 (#574, ADR 0006 "Idempotent creates"): one row per Idempotency-Key a member sent on a
-- creating POST. The key is scoped to the user, the workspace and the operation; the request
-- fingerprint tells a retry of the same request from a reuse of the key for another one.
--
-- A row is IN_PROGRESS from the moment a request claims the key until the transaction that
-- creates the resource also stores the response (COMPLETED), so the resource and its stored
-- response commit together or not at all. A request that fails deletes its claim: nothing was
-- created, and a retry runs as a new request. After expires_at (24 hours) the key is handled as
-- new; purge_expired_idempotency_keys() removes such rows.
--
-- The stored response repeats the created resource (financial data): workspace RLS like every
-- other workspace table, and the row goes with its workspace or user.
CREATE TABLE idempotency_key (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    operation_id TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    status TEXT NOT NULL DEFAULT 'IN_PROGRESS',
    response_status SMALLINT,
    response_body TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT idempotency_key_scope_unique
    UNIQUE (workspace_id, user_id, operation_id, idempotency_key),
    CONSTRAINT idempotency_key_status_check
    CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT idempotency_key_completed_has_response CHECK (
        (status = 'COMPLETED') = (response_status IS NOT NULL AND response_body IS NOT NULL)
    ),
    CONSTRAINT idempotency_key_length_check CHECK (length(idempotency_key) <= 255)
);

-- The purge reads by expiry across workspaces.
CREATE INDEX idx_idempotency_key_expires_at ON idempotency_key (expires_at);
CREATE INDEX idx_idempotency_key_user_id ON idempotency_key (user_id);

ALTER TABLE idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_key FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON idempotency_key
USING (workspace_id = current_workspace_id())
WITH CHECK (workspace_id = current_workspace_id());

-- The hourly purge runs without a workspace context, which RLS denies by default. Like V81's
-- lookups, this SECURITY DEFINER function is the one narrow exception: it deletes expired rows
-- only, at most p_limit of them, and returns how many. It reads or returns no row content.
CREATE FUNCTION public.purge_expired_idempotency_keys(p_now TIMESTAMPTZ, p_limit INTEGER)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET row_security = off
AS $$
DECLARE
    deleted INTEGER;
BEGIN
    DELETE FROM public.idempotency_key
    WHERE id IN (
        SELECT k.id FROM public.idempotency_key AS k
        WHERE k.expires_at <= p_now
        LIMIT greatest(p_limit, 0)
    );
    GET DIAGNOSTICS deleted = ROW_COUNT;
    RETURN deleted;
END;
$$;

-- PostgreSQL grants EXECUTE on a new function to PUBLIC. Only the runtime role may call it.
REVOKE ALL ON FUNCTION public.purge_expired_idempotency_keys(TIMESTAMPTZ, INTEGER) FROM PUBLIC;

-- The runtime role of the role-split template, when provisioning created it before Flyway.
-- A role created later is granted EXECUTE by hand (database-schema.md); never grant to PUBLIC.
DO $$
BEGIN
    IF to_regrole('trackmywealth_runtime') IS NOT NULL THEN
        GRANT EXECUTE ON FUNCTION public.purge_expired_idempotency_keys(TIMESTAMPTZ, INTEGER)
        TO trackmywealth_runtime;
    END IF;
END;
$$;

COMMENT ON TABLE idempotency_key IS
'US-29-14 (#574): Idempotency-Key claims and stored responses of creating POSTs, kept 24 hours.';

COMMENT ON FUNCTION public.purge_expired_idempotency_keys(TIMESTAMPTZ, INTEGER) IS
'US-29-14 (#574): deletes expired idempotency_key rows only; owner must bypass RLS.';
