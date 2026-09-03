-- =============================================================================================
-- V5: Account subtype extension tables (class-table inheritance children)
-- =============================================================================================
-- DB-09/DB-10: one extension table per subtype that actually has distinct, constrained
-- attributes. Each extension table's primary key is also a foreign key to account(id), which
-- structurally enforces "at most one extension row per account" (G2, disjoint specialisation).
-- The trg_extension_type_guard trigger additionally enforces that a row can only be inserted
-- into e.g. account_credit_card when the parent account's account_type is actually 'CREDIT_CARD'.
-- Subtypes with no distinct attributes (CASH, SAVINGS, CRYPTO) get no extension table.
-- =============================================================================================

CREATE OR REPLACE FUNCTION trg_extension_type_guard()
RETURNS TRIGGER AS $$
DECLARE
    expected_type TEXT := TG_ARGV[0];
    actual_type TEXT;
BEGIN
    SELECT account_type INTO actual_type FROM account WHERE id = NEW.account_id;
    IF actual_type IS DISTINCT FROM expected_type THEN
        RAISE EXCEPTION 'account_extension_type_mismatch: account % has account_type % but a % row was attempted in %',
            NEW.account_id, actual_type, expected_type, TG_TABLE_NAME USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- --- Securities / Depot (holds_positions = true) -----------------------------------------
-- FR-ACC-011: also used for Managed Mandate accounts (discretionary flag on account); the two
-- differ only by is_discretionary, so per DM-20 they share this shape rather than forking types.
CREATE TABLE account_securities (
    account_id              UUID PRIMARY KEY REFERENCES account(id),
    default_cost_basis_method TEXT NOT NULL DEFAULT 'FIFO' CHECK (default_cost_basis_method IN ('FIFO', 'LIFO', 'AVERAGE_COST')), -- FR-DEP-004
    fees_included_in_cost_basis BOOLEAN NOT NULL DEFAULT TRUE -- FR-DEP-005
);
CREATE TRIGGER account_securities_type_guard BEFORE INSERT ON account_securities
    FOR EACH ROW EXECUTE FUNCTION trg_extension_type_guard('SECURITIES');

-- --- Credit card ---------------------------------------------------------------------------
CREATE TABLE account_credit_card (
    account_id              UUID PRIMARY KEY REFERENCES account(id),
    statement_day            SMALLINT CHECK (statement_day BETWEEN 1 AND 31),   -- FR-CC-008
    credit_limit              NUMERIC(20,4),
    due_date_offset_days      SMALLINT,
    billing_currency           CHAR(3) NOT NULL,
    settlement_source_account_id UUID REFERENCES account(id) -- expected current account that settles this card (FR-CC-007)
);
CREATE TRIGGER account_credit_card_type_guard BEFORE INSERT ON account_credit_card
    FOR EACH ROW EXECUTE FUNCTION trg_extension_type_guard('CREDIT_CARD');

-- --- Mortgage / Loan (amortisation) --------------------------------------------------------
CREATE TABLE account_mortgage (
    account_id              UUID PRIMARY KEY REFERENCES account(id),
    original_principal        NUMERIC(20,4) NOT NULL,
    interest_rate_percent      NUMERIC(7,4) NOT NULL,
    fixation_end_date           DATE,                       -- FR-NW-006
    amortisation_type           TEXT CHECK (amortisation_type IN ('DIRECT', 'INDIRECT', 'INTEREST_ONLY')),
    original_term_months        INTEGER,
    linked_asset_account_id     UUID REFERENCES account(id) -- real estate held net of financing, FR-POR-04/FR-NW-007
);
CREATE TRIGGER account_mortgage_type_guard BEFORE INSERT ON account_mortgage
    FOR EACH ROW EXECUTE FUNCTION trg_extension_type_guard('MORTGAGE');

CREATE TABLE account_loan (
    account_id              UUID PRIMARY KEY REFERENCES account(id),
    original_principal        NUMERIC(20,4) NOT NULL,
    interest_rate_percent      NUMERIC(7,4),
    original_term_months        INTEGER
);
CREATE TRIGGER account_loan_type_guard BEFORE INSERT ON account_loan
    FOR EACH ROW EXECUTE FUNCTION trg_extension_type_guard('LOAN');

-- Amortisation schedule rows for mortgage/loan accounts (FR-NW-006). Kept separate from the
-- account so the schedule can be regenerated without touching the parent account row.
CREATE TABLE amortisation_schedule_entry (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id               UUID NOT NULL REFERENCES account(id),
    due_date                  DATE NOT NULL,
    interest_component         NUMERIC(20,4) NOT NULL,
    principal_component        NUMERIC(20,4) NOT NULL,
    remaining_balance           NUMERIC(20,4) NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_id, due_date)
);
CREATE INDEX idx_amortisation_schedule_account ON amortisation_schedule_entry(account_id);

-- --- Pension (Pillar 3a / Pillar 2 / bAV / Riester / Ruerup) --------------------------------
-- DM-07/DM-16/DM-20/FR-ACC-011: one PensionAccount subtype for all jurisdictions and variants.
-- Whether it holds positions is a capability flag on the parent `account` row (a VIAC 3a does; a
-- PostFinance 3a may not); whether it is occupational/balance-only is `is_occupational` here.
CREATE TABLE account_pension (
    account_id                   UUID PRIMARY KEY REFERENCES account(id),
    pension_scheme                 TEXT NOT NULL CHECK (pension_scheme IN (
                                      'CH_PILLAR_3A', 'CH_PILLAR_2_VESTED_BENEFITS', 'DE_RIESTER',
                                      'DE_RUERUP', 'DE_BAV', 'OTHER')),
    is_occupational                 BOOLEAN NOT NULL DEFAULT FALSE, -- FR-ACC-030: balance-and-entitlement model
    contribution_limit_rule_code    TEXT,   -- FK by code, not id, into effective-dated reference config (V18), NFR-MNT-01
    withdrawal_rule_code            TEXT,
    purchase_capacity_amount         NUMERIC(20,4), -- Einkauf, FR-ACC-031
    last_certificate_date             DATE            -- FR-ACC-030: annual certificate the balance was last updated from
);
CREATE TRIGGER account_pension_type_guard BEFORE INSERT ON account_pension
    FOR EACH ROW EXECUTE FUNCTION trg_extension_type_guard('PENSION');

CREATE TABLE account_vested_benefits (
    account_id                   UUID PRIMARY KEY REFERENCES account(id),
    vested_benefit_amount           NUMERIC(20,4),
    interest_credit_rate_percent    NUMERIC(7,4),
    last_certificate_date             DATE
);
CREATE TRIGGER account_vested_benefits_type_guard BEFORE INSERT ON account_vested_benefits
    FOR EACH ROW EXECUTE FUNCTION trg_extension_type_guard('VESTED_BENEFITS');

-- --- Custom asset (real estate, vehicles, precious metals, collectibles) -------------------
CREATE TABLE account_custom_asset (
    account_id                   UUID PRIMARY KEY REFERENCES account(id),
    custom_asset_type               TEXT NOT NULL CHECK (custom_asset_type IN (
                                       'REAL_ESTATE', 'VEHICLE', 'PRECIOUS_METAL', 'COLLECTIBLE', 'OTHER')),
    valuation_frequency               TEXT DEFAULT 'MANUAL'
);
CREATE TRIGGER account_custom_asset_type_guard BEFORE INSERT ON account_custom_asset
    FOR EACH ROW EXECUTE FUNCTION trg_extension_type_guard('CUSTOM_ASSET');

-- FR-NW-003: dated manual valuations for custom assets.
CREATE TABLE custom_asset_valuation (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id                UUID NOT NULL REFERENCES account(id),
    valuation_date             DATE NOT NULL,
    value                       NUMERIC(20,4) NOT NULL,
    currency                    CHAR(3) NOT NULL,
    source                       TEXT NOT NULL DEFAULT 'MANUAL',
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_id, valuation_date)
);
CREATE INDEX idx_custom_asset_valuation_account ON custom_asset_valuation(account_id);
