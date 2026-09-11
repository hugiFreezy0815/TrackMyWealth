-- =============================================================================================
-- V24: account.native_currency is immutable after creation, same as account_type
-- =============================================================================================
-- FR-ACC-002 states an account retains its own currency independently of container/user reporting
-- currencies, but is silent on whether that currency can change post-creation. US-05-02 treats it
-- as immutable for the same reason V4's trg_account_type_immutable treats account_type that way:
-- figures already booked against this account (transactions, valuations) were computed in the
-- current native_currency, and an in-place currency change would silently reinterpret them without
-- any actual conversion happening (NFR-REL-003). Where a genuine currency correction is needed,
-- it is the same close-and-recreate flow as an account_type correction (US-05-02/US-05-03), never
-- an in-place update - this is a new, separate trigger rather than folding the check into
-- trg_account_type_immutable so each violation raises its own specific, traceable message.
-- =============================================================================================

CREATE OR REPLACE FUNCTION trg_account_currency_immutable()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.native_currency IS DISTINCT FROM OLD.native_currency THEN
        RAISE EXCEPTION 'account_currency_immutable: account % native_currency cannot change from % to % (FR-ACC-002)',
            OLD.id, OLD.native_currency, NEW.native_currency USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_currency_immutable BEFORE UPDATE ON account
FOR EACH ROW EXECUTE FUNCTION trg_account_currency_immutable();
