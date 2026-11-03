-- US-07-35 (#382): the counterparty a statement names (the payee of a payment, the payer of an
-- incoming amount) becomes a ledger column of its own, so the app can show it next to the
-- description and the workspace-wide transaction list (#382, its next part) can search it.
--
-- Until now the import kept the counterparty only in raw_source_data ('counterpartyName', written
-- by ImportLedgerRowService) and used it as the description when the row had none; it still does
-- both (that fallback feeds the duplicate check, so it stays). The column is
-- descriptive text like merchant_description: trg_transaction_append_only does not freeze it, so
-- a text-only correction edits it in place (US-07-06).
--
-- V113, not V111: PR #438 (#390) holds V111/V112 (out-of-order migrations are enabled).

ALTER TABLE transaction ADD COLUMN counterparty_name TEXT;

COMMENT ON COLUMN transaction.counterparty_name IS
'The payee or payer the source names (CSV template column, CAMT RltdPties Cdtr/Dbtr Nm), '
'or what the member entered; NULL when unknown. Descriptive, not frozen by the append-only rule.';

-- Backfill every imported row from the source data it already carries, trimmed and cut to the
-- 255 UTF-16 code units a request may send (@Size counts String.length(); a character outside the
-- BMP, such as an emoji, counts twice and is never split). The import caps it the same way
-- (ImportLedgerRowService.ledgerCounterpartyName), so such a row stays correctable; the full text
-- stays in raw_source_data. The UPDATE bumps the rows' version
-- (trg_bump_version) on purpose: their API representation gains the field, so an ETag read before
-- this migration must no longer match (ADR 0004).
--
-- transaction is FORCE ROW LEVEL SECURITY (V20). As in V37: with row_security off, the migration
-- role (BYPASSRLS, ADR 0005) sees every workspace, and any other role gets an error instead of
-- silently updating nothing.
SET LOCAL row_security = off;

-- The longest prefix of whole characters within max_units UTF-16 code units (ascii() is the code
-- point; one outside the BMP takes two units). Temporary: only this backfill needs it.
CREATE FUNCTION pg_temp.utf16_prefix(value TEXT, max_units INTEGER)
RETURNS TEXT
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    units INTEGER := 0;
BEGIN
    -- Short enough even if every character took two units: almost every name.
    IF char_length(value) * 2 <= max_units THEN
        RETURN value;
    END IF;
    FOR i IN 1 .. coalesce(char_length(value), 0) LOOP
        units := units + CASE WHEN ascii(substr(value, i, 1)) > 65535 THEN 2 ELSE 1 END;
        IF units > max_units THEN
            RETURN left(value, i - 1);
        END IF;
    END LOOP;
    RETURN value;
END
$$;

UPDATE transaction
SET counterparty_name = pg_temp.utf16_prefix(btrim(raw_source_data ->> 'counterpartyName'), 255)
WHERE
    raw_source_data ->> 'counterpartyName' IS NOT NULL
    AND btrim(raw_source_data ->> 'counterpartyName') <> '';

DROP FUNCTION pg_temp.utf16_prefix(TEXT, INTEGER);
