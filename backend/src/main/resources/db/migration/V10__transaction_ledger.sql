-- =============================================================================================
-- V10: Transaction ledger
-- =============================================================================================
-- Section 12 / DM-01, RULE-024, FR-TRX-*: the ledger is append-only. Corrections are recorded as
-- new compensating/superseding entries, never as in-place mutation of a committed financial
-- value (FR-LIF-002/002a/004). This is what makes every derived figure (positions, tax lots,
-- daily valuations) reproducible from source, and every import safely re-runnable.
-- =============================================================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm; -- fuzzy merchant-name search/matching (FR-TRX-005, FR-CAT-011 fallback categorisation)

CREATE TABLE transaction (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id                UUID NOT NULL REFERENCES household(id),
    account_id                     UUID NOT NULL REFERENCES account(id),
    transaction_type                  TEXT NOT NULL CHECK (transaction_type IN (
                                        'INCOME', 'EXPENSE', 'TRANSFER', 'DEPOSIT', 'WITHDRAWAL',
                                        'BUY', 'SELL', 'DIVIDEND', 'INTEREST', 'FEE', 'TAX',
                                        'REFUND', 'DEBT_REPAYMENT', 'PENSION_CONTRIBUTION',
                                        'CREDIT_CARD_PURCHASE', 'SETTLEMENT',
                                        'VALUATION_ADJUSTMENT', 'CORPORATE_ACTION')),
    security_id                        UUID REFERENCES security(id), -- set for BUY/SELL/DIVIDEND/... investment activity

    -- FR-TRX-008: trade date and settlement date are both retained where the source provides
    -- them. Performance is measured on trade date; cash balances move on settlement date.
    booking_date                          DATE NOT NULL,
    value_date                               DATE,
    trade_date                                DATE,
    settlement_date                            DATE,

    -- DB-01: money is NUMERIC(20,4); quantities are NUMERIC(28,10). No floating point, ever.
    amount                                       NUMERIC(20,4) NOT NULL,
    currency                                        CHAR(3) NOT NULL,
    fx_rate_to_account_currency                       NUMERIC(20,10), -- DM-06: every cross-currency value carries the FX rate used
    fx_rate_date                                         DATE,
    quantity                                                NUMERIC(28,10),
    unit_price                                                 NUMERIC(20,10),
    fee_amount                                                    NUMERIC(20,4),
    -- FR-TAXR-001: tax deducted at source is a distinct amount; gross vs net stay distinguishable.
    tax_withheld_amount                                             NUMERIC(20,4),
    gross_amount                                                       NUMERIC(20,4),
    net_amount                                                            NUMERIC(20,4),

    merchant_description                                                     TEXT,
    counterparty_account_id                                                     UUID REFERENCES account(id), -- FR-CF-005: two-sided transfer matching
    is_internal_transfer                                                           BOOLEAN NOT NULL DEFAULT FALSE, -- DM-05/FR-CF-001
    category_id                                                                       UUID, -- FK added in V13 after category table exists
    notes                                                                                TEXT,

    -- Provenance (FR-TRX-001/009/010, FR-DAT-*)
    source                                                                                TEXT NOT NULL DEFAULT 'MANUAL'
                                                                                            CHECK (source IN ('MANUAL', 'CSV', 'DOCUMENT', 'API', 'AGGREGATOR')),
    external_id                                                                              TEXT,
    import_batch_id                                                                             UUID, -- FK added in V15 after import_batch table exists
    raw_source_data                                                                                JSONB,

    -- FR-LIF-002/002a: void semantics. A voided row remains in the ledger; a reversing entry
    -- (transaction_type mirrors the original, amount negated) is inserted as a normal new row
    -- referencing this one via replaces_transaction_id on the *reversing* row, not here.
    voided_at                                                                                         TIMESTAMPTZ,
    voided_by                                                                                            UUID REFERENCES app_user(id),
    void_reason                                                                                             TEXT,
    replaces_transaction_id                                                                                    UUID REFERENCES transaction(id),

    created_at                                                                                                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                                                                                                       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                                                                                                          UUID REFERENCES app_user(id),
    updated_by                                                                                                             UUID REFERENCES app_user(id),
    version                                                                                                                    INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_transaction_account_date ON transaction(account_id, booking_date DESC);
CREATE INDEX idx_transaction_household ON transaction(household_id);
CREATE INDEX idx_transaction_security ON transaction(security_id) WHERE security_id IS NOT NULL;
CREATE INDEX idx_transaction_category ON transaction(category_id) WHERE category_id IS NOT NULL;
CREATE INDEX idx_transaction_import_batch ON transaction(import_batch_id) WHERE import_batch_id IS NOT NULL;
CREATE INDEX idx_transaction_counterparty ON transaction(counterparty_account_id) WHERE counterparty_account_id IS NOT NULL;
-- FR-TRX-005: search/filter by date, account, amount, currency, category, type, merchant, owner
CREATE INDEX idx_transaction_type_date ON transaction(transaction_type, booking_date DESC);
CREATE INDEX idx_transaction_merchant_trgm ON transaction USING GIN (merchant_description gin_trgm_ops);

-- DB-04/FR-TRX-009: idempotent imports - a stable external identifier from the source, scoped
-- per account and source, makes repeat imports safely re-runnable.
CREATE UNIQUE INDEX uq_transaction_external_id
    ON transaction(account_id, source, external_id) WHERE external_id IS NOT NULL;

CREATE TRIGGER transaction_set_updated_at BEFORE UPDATE ON transaction FOR EACH ROW EXECUTE FUNCTION trg_set_updated_at();
CREATE TRIGGER transaction_bump_version BEFORE UPDATE ON transaction FOR EACH ROW EXECUTE FUNCTION trg_bump_version();

-- RULE-024/FR-TRX-007/FR-LIF-004: enforce append-only-ness at the data layer, not only in the
-- service layer. Only the columns listed as "always mutable" may change; everything else that
-- affects a financial figure is frozen once written, and any correction must go through the
-- void-and-replace path (FR-LIF-002/004) or the import-batch rollback path (FR-LIF-010/011).
CREATE OR REPLACE FUNCTION trg_transaction_append_only()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.amount IS DISTINCT FROM OLD.amount
        OR NEW.currency IS DISTINCT FROM OLD.currency
        OR NEW.quantity IS DISTINCT FROM OLD.quantity
        OR NEW.unit_price IS DISTINCT FROM OLD.unit_price
        OR NEW.booking_date IS DISTINCT FROM OLD.booking_date
        OR NEW.value_date IS DISTINCT FROM OLD.value_date
        OR NEW.trade_date IS DISTINCT FROM OLD.trade_date
        OR NEW.settlement_date IS DISTINCT FROM OLD.settlement_date
        OR NEW.account_id IS DISTINCT FROM OLD.account_id
        OR NEW.security_id IS DISTINCT FROM OLD.security_id
        OR NEW.transaction_type IS DISTINCT FROM OLD.transaction_type
        OR NEW.gross_amount IS DISTINCT FROM OLD.gross_amount
        OR NEW.net_amount IS DISTINCT FROM OLD.net_amount
        OR NEW.tax_withheld_amount IS DISTINCT FROM OLD.tax_withheld_amount
    THEN
        RAISE EXCEPTION 'transaction_ledger_append_only: financial fields of transaction % cannot be updated in place (RULE-024/FR-TRX-007). Void and insert a replacement instead (FR-LIF-002/004).',
            OLD.id USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER transaction_append_only BEFORE UPDATE ON transaction FOR EACH ROW EXECUTE FUNCTION trg_transaction_append_only();

-- FR-TRX-006/FR-CAT split-transaction support: one purchase, several category allocations.
CREATE TABLE transaction_category_split (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id       UUID NOT NULL REFERENCES transaction(id),
    category_id             UUID, -- FK added in V13
    amount                     NUMERIC(20,4) NOT NULL
);
CREATE INDEX idx_transaction_category_split_transaction ON transaction_category_split(transaction_id);
