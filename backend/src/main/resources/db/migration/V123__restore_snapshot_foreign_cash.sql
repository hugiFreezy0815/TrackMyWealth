-- Flyway's schema owner needs to see existing rows in every workspace during this transaction.
ALTER TABLE account_snapshot NO FORCE ROW LEVEL SECURITY;
ALTER TABLE import_batch NO FORCE ROW LEVEL SECURITY;
ALTER TABLE import_holding_row NO FORCE ROW LEVEL SECURITY;
ALTER TABLE snapshot_foreign_cash NO FORCE ROW LEVEL SECURITY;

-- The recorded own-currency balance identifies the cash source. Leave the snapshot incomplete
-- if a different contributing holdings batch also reported foreign cash.
INSERT INTO snapshot_foreign_cash (workspace_id, snapshot_id, currency, amount, import_batch_id)
SELECT
    s.workspace_id,
    s.id AS snapshot_id,
    r.currency,
    sum(r.amount) AS amount,
    b.id AS import_batch_id
FROM account_snapshot AS s
INNER JOIN import_batch AS b ON s.balance_import_batch_id = b.id AND s.workspace_id = b.workspace_id
INNER JOIN import_holding_row AS r ON b.id = r.import_batch_id AND s.workspace_id = r.workspace_id
WHERE
    s.withdrawn_at IS NULL
    AND b.status = 'COMMITTED'
    AND r.kind = 'CASH'
    AND r.currency <> s.currency
    AND NOT EXISTS (
        SELECT 1
        FROM snapshot_holding AS h
        INNER JOIN import_holding_row AS other
            ON h.import_batch_id = other.import_batch_id AND s.workspace_id = other.workspace_id
        WHERE
            h.snapshot_id = s.id AND h.import_batch_id <> b.id
            AND other.kind = 'CASH' AND other.currency <> s.currency
    )
GROUP BY s.workspace_id, s.id, r.currency, b.id
HAVING abs(sum(r.amount)) < 10000000000000000
ON CONFLICT (snapshot_id, currency) DO NOTHING;

ALTER TABLE snapshot_foreign_cash FORCE ROW LEVEL SECURITY;
ALTER TABLE import_holding_row FORCE ROW LEVEL SECURITY;
ALTER TABLE import_batch FORCE ROW LEVEL SECURITY;
ALTER TABLE account_snapshot FORCE ROW LEVEL SECURITY;
