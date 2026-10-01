-- =============================================================================================
-- V48: optimistic concurrency for remaining mutable API resources (FR-CNC-001/002, #207)
-- =============================================================================================
-- #206/ADR 0004 made strong ETag/If-Match preconditions mandatory for read-modify-write APIs.
-- Several older mutable tables predate that rollout and have no persistence revision yet.
--
-- Use the same database-owned revision convention as account/category/app_user/workspace_member:
-- INTEGER NOT NULL DEFAULT 0 plus trg_bump_version(). Hibernate maps these columns with @Version
-- and @Generated(INSERT, UPDATE), so direct SQL writers and JPA advance the same client token.
--
-- transaction already has version + transaction_bump_version since V10 and is intentionally not
-- altered here; #207 only exposes/maps that existing revision.
-- =============================================================================================

ALTER TABLE account_credit_card
ADD COLUMN version INTEGER NOT NULL DEFAULT 0; -- noqa: RF04

CREATE TRIGGER account_credit_card_bump_version
BEFORE UPDATE ON account_credit_card
FOR EACH ROW EXECUTE FUNCTION trg_bump_version();

ALTER TABLE account_snapshot
ADD COLUMN version INTEGER NOT NULL DEFAULT 0; -- noqa: RF04

CREATE TRIGGER account_snapshot_bump_version
BEFORE UPDATE ON account_snapshot
FOR EACH ROW EXECUTE FUNCTION trg_bump_version();

ALTER TABLE categorization_rule
ADD COLUMN version INTEGER NOT NULL DEFAULT 0; -- noqa: RF04

CREATE TRIGGER categorization_rule_bump_version
BEFORE UPDATE ON categorization_rule
FOR EACH ROW EXECUTE FUNCTION trg_bump_version();

ALTER TABLE settlement_match
ADD COLUMN version INTEGER NOT NULL DEFAULT 0; -- noqa: RF04

CREATE TRIGGER settlement_match_bump_version
BEFORE UPDATE ON settlement_match
FOR EACH ROW EXECUTE FUNCTION trg_bump_version();

ALTER TABLE sharing_grant
ADD COLUMN version INTEGER NOT NULL DEFAULT 0; -- noqa: RF04

CREATE TRIGGER sharing_grant_bump_version
BEFORE UPDATE ON sharing_grant
FOR EACH ROW EXECUTE FUNCTION trg_bump_version();
