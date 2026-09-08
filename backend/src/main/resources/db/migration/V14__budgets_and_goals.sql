-- =============================================================================================
-- V14: Budgets, goals and financial-independence planning
-- =============================================================================================
-- Section 16 / FR-BUD-*, section 25a / FR-PEN-*, FR-NW-*: budgets and goals close the loop
-- between the cashflow model (V10/V13) and the investment/pension model (V4-V12).
-- =============================================================================================

CREATE TABLE budget (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id          UUID NOT NULL REFERENCES workspace(id),
    name                      TEXT NOT NULL,
    period_type                  TEXT NOT NULL DEFAULT 'MONTHLY' CHECK (period_type = 'MONTHLY'),
    -- FR-BUD-007: proposed from the workspace's own transaction history at creation time; this
    -- flag distinguishes an accepted proposal from a budget built from scratch, for UX purposes.
    derived_from_history               BOOLEAN NOT NULL DEFAULT FALSE,
    is_active                             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                                        INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_budget_workspace ON budget(workspace_id);
CREATE TRIGGER budget_set_updated_at BEFORE UPDATE ON budget FOR EACH ROW EXECUTE FUNCTION trg_set_updated_at();
CREATE TRIGGER budget_bump_version BEFORE UPDATE ON budget FOR EACH ROW EXECUTE FUNCTION trg_bump_version();

CREATE TABLE budget_line (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    budget_id             UUID NOT NULL REFERENCES budget(id),
    category_id               UUID NOT NULL REFERENCES category(id),
    budgeted_amount               NUMERIC(20,4) NOT NULL,
    currency                         CHAR(3) NOT NULL,
    -- FR-BUD-008: irregular/annual costs amortised across months rather than misread as an
    -- overspend in the month they land.
    is_irregular                        BOOLEAN NOT NULL DEFAULT FALSE,
    amortised_over_months                  SMALLINT,
    UNIQUE (budget_id, category_id)
);
CREATE INDEX idx_budget_line_budget ON budget_line(budget_id);

-- FR-BUD-009: the savings-rate methodology must be an explicit, documented, user-visible
-- setting - stored per workspace rather than assumed globally, since published definitions
-- genuinely differ.
CREATE TABLE savings_rate_methodology (
    workspace_id                     UUID PRIMARY KEY REFERENCES workspace(id),
    include_employer_pension_contrib    BOOLEAN NOT NULL DEFAULT FALSE,
    include_mortgage_principal             BOOLEAN NOT NULL DEFAULT TRUE,
    include_unrealised_gains                  BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at                                    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE goal (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id          UUID NOT NULL REFERENCES workspace(id),
    goal_type                 TEXT NOT NULL CHECK (goal_type IN (
                                'PILLAR_3A_MAX_OUT', 'SAVINGS_TARGET', 'FIRE_TARGET',
                                'HOUSE_DEPOSIT', 'CUSTOM')),
    name                          TEXT NOT NULL,
    target_amount                    NUMERIC(20,4),
    currency                            CHAR(3),
    target_date                            DATE,
    status                                    TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ACHIEVED', 'ABANDONED', 'ARCHIVED')),
    created_at                                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                                           INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_goal_workspace ON goal(workspace_id);
CREATE TRIGGER goal_set_updated_at BEFORE UPDATE ON goal FOR EACH ROW EXECUTE FUNCTION trg_set_updated_at();
CREATE TRIGGER goal_bump_version BEFORE UPDATE ON goal FOR EACH ROW EXECUTE FUNCTION trg_bump_version();

-- FR-PEN-002/003: Pillar 3a annual contribution tracking against an effective-dated limit. The
-- limit value itself is reference-data configuration (V18), not hard-coded here (FR-PEN-006).
CREATE TABLE pension_contribution_tracking (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id            UUID NOT NULL REFERENCES account(id),
    contribution_year         SMALLINT NOT NULL,
    contributed_amount           NUMERIC(20,4) NOT NULL DEFAULT 0,
    limit_amount_at_year             NUMERIC(20,4),   -- resolved from reference config at read/compute time, cached here for display
    currency                            CHAR(3) NOT NULL,
    UNIQUE (account_id, contribution_year)
);
CREATE INDEX idx_pension_contribution_tracking_account ON pension_contribution_tracking(account_id);
