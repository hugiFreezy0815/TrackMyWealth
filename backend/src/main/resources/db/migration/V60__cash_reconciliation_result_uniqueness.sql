-- US-25-02: the cash reconciliation engine owns at most one account-level result per snapshot.
-- Holdings reconciliation (EPIC 15) will use rows with affected_security_id populated and is
-- deliberately outside this partial unique index.
CREATE UNIQUE INDEX uq_reconciliation_result_snapshot_cash
ON reconciliation_result (snapshot_id)
WHERE affected_security_id IS NULL;
