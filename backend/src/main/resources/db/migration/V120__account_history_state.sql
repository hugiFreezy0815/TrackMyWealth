-- US-11-06: keep effective portfolio membership and lifecycle intervals for history charts.
-- The current account.archived_at field is deliberately cleared on restore and cannot by itself
-- reconstruct a previously archived interval.
CREATE TABLE account_history_state (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    account_id UUID NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    portfolio_id UUID NOT NULL REFERENCES portfolio(id),
    status TEXT NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    verified BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_account_history_state_lookup
    ON account_history_state (workspace_id, account_id, effective_at, id);

-- Pre-existing account transitions cannot be recovered from account.archived_at or
-- account.portfolio_id. Backfill the current state as an explicitly unverified baseline.
INSERT INTO account_history_state
    (workspace_id, account_id, portfolio_id, status, effective_at, verified)
SELECT workspace_id, id, portfolio_id, status, COALESCE(created_at, clock_timestamp()), FALSE
FROM account;

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
    WHEN (OLD.portfolio_id IS DISTINCT FROM NEW.portfolio_id
          OR OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION record_account_history_state();
