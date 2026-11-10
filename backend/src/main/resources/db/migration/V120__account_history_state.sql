-- US-11-06: keep effective portfolio membership and lifecycle intervals for history charts.
-- The current account.archived_at field is deliberately cleared on restore and cannot by itself
-- reconstruct a previously archived interval.
CREATE TABLE account_history_state (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    workspace_id UUID NOT NULL REFERENCES workspace (id),
    account_id UUID NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    portfolio_id UUID NOT NULL REFERENCES portfolio (id),
    status TEXT NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    verified BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_account_history_state_lookup
ON account_history_state (workspace_id, account_id, effective_at, event_sequence);

-- Pre-existing account transitions cannot be recovered from account.archived_at or
-- account.portfolio_id. Backfill the current state as an explicitly unverified baseline.
-- The migration owner is bound by FORCE RLS and has no request workspace context.
-- Temporarily let only the table owner bypass its existing read policy while holding
-- the migration's exclusive DDL lock; restore FORCE before this transaction commits.
ALTER TABLE account NO FORCE ROW LEVEL SECURITY;
INSERT INTO account_history_state
(workspace_id, account_id, portfolio_id, status, effective_at, verified)
SELECT
    workspace_id,
    id,
    portfolio_id,
    status,
    coalesce(created_at, clock_timestamp()) AS effective_at,
    FALSE AS verified
FROM account;
ALTER TABLE account FORCE ROW LEVEL SECURITY;

ALTER TABLE account_history_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE account_history_state FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON account_history_state
USING (workspace_id = current_workspace_id())
WITH CHECK (workspace_id = current_workspace_id());


CREATE FUNCTION record_account_history_state() RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO account_history_state
        (workspace_id, account_id, portfolio_id, status, effective_at, verified)
    VALUES (NEW.workspace_id, NEW.id, NEW.portfolio_id, NEW.status, clock_timestamp(), TRUE);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_history_state_on_insert
AFTER INSERT ON account
FOR EACH ROW EXECUTE FUNCTION record_account_history_state();

CREATE TRIGGER account_history_state_on_change
AFTER UPDATE OF portfolio_id, status ON account
FOR EACH ROW
WHEN (
    OLD.portfolio_id IS DISTINCT FROM NEW.portfolio_id
    OR OLD.status IS DISTINCT FROM NEW.status
)
EXECUTE FUNCTION record_account_history_state();
