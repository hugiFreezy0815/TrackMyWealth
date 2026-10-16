-- =============================================================================================
-- V87: a loan payment's record is append-only (US-10-02, #328)
-- =============================================================================================
-- loan_payment (V83) records how a payment was split and names the ledger rows it wrote. Those rows
-- are append-only (V39, V68): removing a payment soft-deletes or voids them, and this record stays
-- as the history of the split and as the owner of its externalId, as a removed transaction keeps
-- its own. The application never changes or deletes it; these triggers keep that rule in the
-- database, now that a separate runtime role holds plain DML on every table (ADR 0005).
--
-- The one change allowed is V83's own ON DELETE SET NULL: deleting a schedule entry clears
-- amortisation_schedule_entry_id and leaves the split as recorded. TRUNCATE is not guarded, so a
-- workspace's TRUNCATE ... CASCADE still empties it, as tests do. As V68's guard, this catches
-- application bugs; it is no boundary against SQL run as the application's own role.
-- =============================================================================================

CREATE FUNCTION trg_loan_payment_append_only()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'UPDATE'
        AND NEW.amortisation_schedule_entry_id IS NULL
        AND to_jsonb(NEW) - 'amortisation_schedule_entry_id'
        = to_jsonb(OLD) - 'amortisation_schedule_entry_id'
    THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'loan_payment_append_only: loan payment % cannot be changed or deleted (US-10-02). Remove its ledger rows instead.',
        OLD.id USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER loan_payment_append_only
BEFORE UPDATE OR DELETE ON loan_payment
FOR EACH ROW EXECUTE FUNCTION trg_loan_payment_append_only();
