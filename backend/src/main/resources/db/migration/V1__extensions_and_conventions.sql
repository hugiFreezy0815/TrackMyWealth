-- =============================================================================================
-- V1: Extensions and schema-wide conventions
-- =============================================================================================
-- This migration establishes the extensions and helper functions every later migration relies
-- on. Conventions used throughout the schema (see docs/architecture/database-schema.md for the
-- full rationale, cross-referenced to the requirements document):
--
--   * Primary keys are UUID (gen_random_uuid()), not sequential integers, so externally visible
--     identifiers are non-enumerable by construction (FR-TEN-005) and stable across the eventual
--     multi-tenant hosted / self-hosted topologies (PR-002).
--   * Money is NUMERIC(20,4); quantities (shares, units) are NUMERIC(28,10). Floating point is
--     never used for a financial value (DB-01, NFR-CALC-001, NFR-TEC-001).
--   * Every user-owned table carries created_at/updated_at (TIMESTAMPTZ) and a version column for
--     optimistic locking (DB-05, FR-CNC-001).
--   * Timestamps are TIMESTAMPTZ; calendar-bound financial dates (trade date, booking date,
--     value date) are DATE. Mixing the two is called out repeatedly in the requirements as a
--     source of off-by-one errors (DB-03, NFR-TEC-002).
--   * household_id is the tenant column on every household-scoped table and is enforced by
--     row-level security, not by application code alone (FR-TEN-001..003) - see
--     V19__tenancy_row_level_security.sql.
--   * PostgreSQL table inheritance (INHERITS) is never used (DB-13). Where the requirements
--     describe a type hierarchy (Account, in particular), it is modelled as class-table
--     inheritance: one parent table plus explicit per-subtype extension tables sharing the
--     parent's primary key (DM-18, DB-09).
-- =============================================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS citext;     -- case-insensitive email addresses

-- Generic "touch updated_at on every UPDATE" trigger function, attached per-table below.
CREATE OR REPLACE FUNCTION trg_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Generic optimistic-locking trigger: increments `version` on every UPDATE and rejects a write
-- whose incoming version does not match the row currently stored (FR-CNC-001). Application code
-- must send back the version it last read; JPA's @Version does this automatically via an
-- `... WHERE id = ? AND version = ?` update, so this trigger is a defence-in-depth backstop for
-- any write path that bypasses the ORM (bulk jobs, manual SQL).
CREATE OR REPLACE FUNCTION trg_bump_version()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.version IS DISTINCT FROM OLD.version THEN
        RAISE EXCEPTION 'optimistic_lock_conflict: % row % was modified concurrently (expected version %, row is at version %)',
            TG_TABLE_NAME, OLD.id, NEW.version, OLD.version
            USING ERRCODE = '40001';
    END IF;
    NEW.version := OLD.version + 1;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION trg_set_updated_at IS 'Sets updated_at = now() on every row UPDATE.';
COMMENT ON FUNCTION trg_bump_version IS 'Optimistic locking backstop (FR-CNC-001): rejects an UPDATE whose version does not match the stored row, then increments version.';
