-- =============================================================================================
-- V99: An account's savings pocket (US-07-22, #325)
-- =============================================================================================
-- Yuh sets money aside in a savings pocket within the account ("Spareinlage"). The pocket is kept
-- as a savings account of its own, so that a move into it is saving, not spending; the account
-- names it here. The first import commit with a pocket move creates the pocket and links it
-- (SavingsPocketService). A pocket belongs to one account, never to itself, and lies in the same
-- workspace: the FK alone would accept another workspace's account (FK checks bypass RLS), so a
-- trigger looks the pocket up under the caller's RLS context, as V34 does for categories.
-- =============================================================================================

ALTER TABLE account
ADD COLUMN savings_pocket_account_id UUID REFERENCES account (id);

ALTER TABLE account
ADD CONSTRAINT chk_account_savings_pocket_not_self CHECK (
    savings_pocket_account_id IS DISTINCT FROM id
);

CREATE UNIQUE INDEX uq_account_savings_pocket
ON account (savings_pocket_account_id)
WHERE savings_pocket_account_id IS NOT NULL;

CREATE FUNCTION trg_account_savings_pocket_scope_guard()
RETURNS TRIGGER AS $$
DECLARE
    pocket_found BOOLEAN;
BEGIN
    IF NEW.savings_pocket_account_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.savings_pocket_account_id IS NOT DISTINCT FROM OLD.savings_pocket_account_id THEN
            RETURN NEW;
        END IF;
    END IF;
    SELECT TRUE INTO pocket_found
        FROM account
        WHERE id = NEW.savings_pocket_account_id AND workspace_id = NEW.workspace_id;
    IF pocket_found IS NULL THEN
        RAISE EXCEPTION 'account_savings_pocket_scope: the savings pocket of account % must be an account of its own workspace',
            NEW.id USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_savings_pocket_scope_guard
BEFORE INSERT OR UPDATE OF savings_pocket_account_id ON account
FOR EACH ROW EXECUTE FUNCTION trg_account_savings_pocket_scope_guard();
