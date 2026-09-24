-- =============================================================================================
-- V32: constrain security.instrument_type (US-12-01)
-- =============================================================================================
-- V7 left instrument_type free text ("e.g. EQUITY, ETF, FUND, BOND, CRYPTO, DERIVATIVE"). The
-- security master is shared by every workspace, so a value written by one importer or endpoint
-- that no other code recognises would surface in everyone's data. POST /api/v1/securities already
-- validates against this list; the CHECK makes the database the backstop for every other writer.
-- NULL stays allowed: a record may exist before its type is known (FR-SMD-011 completeness).
-- Adding a type later is a one-line migration, deliberately.
-- =============================================================================================

ALTER TABLE security
ADD CONSTRAINT security_instrument_type_check CHECK (
    instrument_type IS NULL
    OR instrument_type IN ('EQUITY', 'ETF', 'FUND', 'BOND', 'CRYPTO', 'DERIVATIVE', 'OTHER')
);
