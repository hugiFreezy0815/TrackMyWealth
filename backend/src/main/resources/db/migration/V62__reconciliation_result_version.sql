-- US-25-03: a member accepts, dismisses or reopens a reconciliation result, and the engine
-- (US-25-02) rewrites the same row whenever the ledger or the snapshot changes. Those decisions are
-- read-modify-write, so the row gets the database-owned revision every mutable resource carries
-- (ADR 0004, V48): a decision taken on a figure the engine has since changed is a 412, never a
-- silent overwrite.
ALTER TABLE reconciliation_result
ADD COLUMN version INTEGER NOT NULL DEFAULT 0; -- noqa: RF04

CREATE TRIGGER reconciliation_result_bump_version
BEFORE UPDATE ON reconciliation_result
FOR EACH ROW EXECUTE FUNCTION trg_bump_version();
