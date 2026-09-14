-- =============================================================================================
-- V25: account archive/restore (US-05-03)
-- =============================================================================================
-- FR-LIF-005: an archived account is excluded from current totals/selection lists but fully
-- included in every historical figure covering a period in which it was active. FR-LIF-006:
-- archived accounts are restorable through the interface for 30 days; thereafter they remain in
-- the data but are no longer user-restorable. FR-STA-001 fixes the whole state machine: ACTIVE ->
-- ARCHIVED -> ACTIVE (within the restore window) - transitions not listed are prohibited.
--
-- archived_at is a new, dedicated column rather than reusing closed_at: closed_at (added in V24's
-- companion US-05-02 work) is a plain user-editable business date exposed on the general
-- PUT /accounts/{id} update endpoint, with its own independent meaning ("when the user considers
-- this account closed"). Anchoring the 30-day restore window to a field the general update
-- endpoint can also set - possibly to an arbitrary past or future date - would make the window's
-- start ambiguous and couple two unrelated concerns. archived_at is written only by the archive
-- action below and cleared only by restore, so it unambiguously means "when this account most
-- recently became ARCHIVED".
--
-- The CHECK ties status and archived_at together at the DB level (this codebase's standing
-- pattern of enforcing invariants in the schema, not just in application code - see V4's
-- trg_account_type_immutable, V24's trg_account_currency_immutable): archived_at is NOT NULL if
-- and only if status = 'ARCHIVED', so the application can never leave the two inconsistent even
-- if a future code path forgets to set/clear one of them together with the other.
-- =============================================================================================

ALTER TABLE account ADD COLUMN archived_at TIMESTAMPTZ;

ALTER TABLE account ADD CONSTRAINT account_archived_at_matches_status
CHECK ((status = 'ARCHIVED') = (archived_at IS NOT NULL));
