-- =============================================================================================
-- V23: account_securities' extension-type guard must also accept MANAGED_MANDATE
-- =============================================================================================
-- V5's own comment on account_securities says it is "also used for Managed Mandate accounts...
-- per DM-20 they share this shape" (FR-ACC-011), but the trigger wired there only ever accepted
-- account_type = 'SECURITIES' - a MANAGED_MANDATE account could never actually get an
-- account_securities row; the database would reject it via trg_extension_type_guard regardless
-- of application logic (US-05-01). This generalizes the guard function to accept any number of
-- expected types (array membership rather than single-value equality) and re-wires
-- account_securities' trigger to accept both. Every other extension table's trigger still passes
-- exactly one type, so this is fully backward compatible - array membership of one element
-- behaves identically to the original equality check, including its null-safety.
-- =============================================================================================

CREATE OR REPLACE FUNCTION trg_extension_type_guard()
RETURNS TRIGGER AS $$
DECLARE
    actual_type TEXT;
BEGIN
    SELECT account_type INTO actual_type FROM account WHERE id = NEW.account_id;
    IF actual_type IS NULL OR NOT (actual_type = ANY (TG_ARGV)) THEN
        RAISE EXCEPTION 'account_extension_type_mismatch: account % has account_type % but a row was attempted in % (expected one of %)',
            NEW.account_id, actual_type, TG_TABLE_NAME, TG_ARGV USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER account_securities_type_guard BEFORE INSERT ON account_securities
FOR EACH ROW EXECUTE FUNCTION trg_extension_type_guard('SECURITIES', 'MANAGED_MANDATE');
