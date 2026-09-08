-- =============================================================================================
-- V13: Categorization - three-layer classification model
-- =============================================================================================
-- Section 13 / FR-DAT-006/007, DM-22/23: source codes (L1, immutable, as received) -> canonical
-- taxonomy (L2, the only layer product logic reads) -> optional external-standard mappings (L3).
-- Classifying a transaction never mutates it (FR-CAT-014/RULE-010) - it only ever writes
-- category_id on the transaction row (an annotation) while the original imported description,
-- MCC and ISO 20022 codes stay in transaction.raw_source_data untouched.
-- =============================================================================================

-- L2: canonical, hierarchical (<=3 levels), user-extensible reporting taxonomy.
CREATE TABLE category (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- NULL workspace_id = shipped system default category, visible to every workspace
    -- (FR-CAT-002); non-NULL = a workspace's own custom category (FR-CAT-003).
    workspace_id          UUID REFERENCES workspace(id),
    parent_category_id       UUID REFERENCES category(id),
    -- FR-CAT-008: stable internal code, independent of the localized labels below, so relabelling
    -- EN/DE never touches historical classification.
    code                        TEXT NOT NULL,
    name_en                        TEXT NOT NULL,
    name_de                           TEXT NOT NULL,
    is_system_default                     BOOLEAN NOT NULL DEFAULT FALSE,
    -- FR-CAT-003: the shipped default set is editable but not deletable while mappings depend on
    -- it (is_active = false instead of a hard delete, FR-LIF-001 "deactivate, historical
    -- assignments preserved").
    is_active                                BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, code)
);
CREATE INDEX idx_category_workspace ON category(workspace_id);
CREATE INDEX idx_category_parent ON category(parent_category_id);
CREATE TRIGGER category_set_updated_at BEFORE UPDATE ON category FOR EACH ROW EXECUTE FUNCTION trg_set_updated_at();

-- Now that `category` exists, wire up the deferred FKs from V10.
ALTER TABLE transaction ADD CONSTRAINT fk_transaction_category FOREIGN KEY (category_id) REFERENCES category(id);
ALTER TABLE transaction_category_split ADD CONSTRAINT fk_transaction_category_split_category FOREIGN KEY (category_id) REFERENCES category(id);

-- L1 -> L2 shipped mapping, configuration data (FR-CAT-010), not code. MCC (ISO 18245) for card
-- transactions, ISO 20022 BankTransactionCode/Purpose for bank transactions (FR-CAT-009).
CREATE TABLE category_source_mapping (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_standard       TEXT NOT NULL CHECK (source_standard IN ('MCC', 'ISO20022_BTC', 'ISO20022_PURPOSE')),
    source_code               TEXT NOT NULL,
    category_id                  UUID NOT NULL REFERENCES category(id),
    reference_package_version       TEXT,
    UNIQUE (source_standard, source_code)
);

-- FR-CAT-007/012/015: workspace-defined, retroactively-applicable categorisation rules.
CREATE TABLE categorization_rule (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id          UUID NOT NULL REFERENCES workspace(id),
    match_type                TEXT NOT NULL CHECK (match_type IN ('MERCHANT', 'COUNTERPARTY_IBAN', 'AMOUNT_PATTERN', 'SOURCE_CODE')),
    match_value                   TEXT NOT NULL,
    category_id                       UUID NOT NULL REFERENCES category(id),
    priority                             INTEGER NOT NULL DEFAULT 100,
    is_active                               BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                                    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_categorization_rule_workspace ON categorization_rule(workspace_id, priority);
CREATE TRIGGER categorization_rule_set_updated_at BEFORE UPDATE ON categorization_rule FOR EACH ROW EXECUTE FUNCTION trg_set_updated_at();

-- FR-CAT-002/006: provenance of how a transaction ended up with its current category, and
-- whether a user override exists that must never be silently replaced by a later automatic run
-- (FR-CAT-003/RULE-031).
CREATE TABLE transaction_categorization_log (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id        UUID NOT NULL REFERENCES transaction(id),
    category_id               UUID NOT NULL REFERENCES category(id),
    assigned_by                  TEXT NOT NULL CHECK (assigned_by IN ('SOURCE_CODE', 'RULE', 'FALLBACK_MATCH', 'USER')),
    rule_id                          UUID REFERENCES categorization_rule(id),
    confidence                          NUMERIC(4,3),
    is_user_override                       BOOLEAN NOT NULL DEFAULT FALSE,
    assigned_at                               TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_transaction_categorization_log_transaction ON transaction_categorization_log(transaction_id);
