-- =============================================================================================
-- V82: members maintain a loan's amortisation schedule (US-10-02, #328)
-- =============================================================================================
-- amortisation_schedule_entry (V5) had no writer until now. A member enters, corrects and deletes
-- entries through the API, so each row carries the database-owned revision every mutable
-- resource has (ADR 0004, V48): If-Match on PUT and DELETE, mapped with @Version + @Generated.
--
-- The amounts are what a lender's schedule states for one due date, never signed ledger amounts:
-- none is negative, and an entry that splits nothing (zero interest and zero principal) says
-- nothing a payment could be split by.
--
-- The table stays protected transitively through account (V20): every access path loads the
-- account first, under RLS.
-- =============================================================================================

ALTER TABLE amortisation_schedule_entry
ADD COLUMN version INTEGER NOT NULL DEFAULT 0; -- noqa: RF04

CREATE TRIGGER amortisation_schedule_entry_bump_version
BEFORE UPDATE ON amortisation_schedule_entry
FOR EACH ROW EXECUTE FUNCTION trg_bump_version();

ALTER TABLE amortisation_schedule_entry
ADD CONSTRAINT chk_amortisation_schedule_entry_amounts CHECK (
    interest_component >= 0
    AND principal_component >= 0
    AND remaining_balance >= 0
    AND interest_component + principal_component > 0
);
