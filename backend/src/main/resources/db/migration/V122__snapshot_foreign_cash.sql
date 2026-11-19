-- The own-currency balance remains on account_snapshot for reconciliation.
ALTER TABLE account_snapshot
ADD CONSTRAINT uq_account_snapshot_workspace_id UNIQUE (workspace_id, id);

CREATE TABLE snapshot_foreign_cash (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL,
    snapshot_id UUID NOT NULL,
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    amount NUMERIC(20, 4) NOT NULL,
    import_batch_id UUID REFERENCES import_batch (id),
    UNIQUE (snapshot_id, currency),
    FOREIGN KEY (workspace_id, snapshot_id)
    REFERENCES account_snapshot (workspace_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_snapshot_foreign_cash_workspace ON snapshot_foreign_cash (workspace_id);
CREATE INDEX idx_snapshot_foreign_cash_batch ON snapshot_foreign_cash (import_batch_id)
WHERE import_batch_id IS NOT NULL;

ALTER TABLE snapshot_foreign_cash ENABLE ROW LEVEL SECURITY;
ALTER TABLE snapshot_foreign_cash FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON snapshot_foreign_cash
USING (workspace_id = current_workspace_id());
