-- =============================================================================================
-- V83: a loan or mortgage payment, split into interest and principal (US-10-02, #328)
-- =============================================================================================
-- FR-CF-006/FR-NW-005: one payment from an ordinary account to a loan is written to the ledger
-- as up to three rows, which this table groups and explains:
--
--   * the principal: a DEBT_REPAYMENT pair, the paying account's outflow and the loan's inflow
--     (its incoming leg names the outflow in related_transaction_id), flagged as an internal
--     transfer - a balance-sheet movement, never spending;
--   * the interest: an EXPENSE on the paying account (a real cost), naming the loan in
--     counterparty_account_id and, when there is a principal, the outflow in
--     related_transaction_id, so it is removed and restored with it.
--
-- split_source says where the split came from: SCHEDULE (the loan's amortisation schedule entry
-- for the period), STATED (given by the caller, e.g. a statement booking interest and instalment
-- separately), ESTIMATED (outstanding balance x interest rate / 12) or UNKNOWN (nothing to split
-- by). UNKNOWN is recorded as one DEBT_REPAYMENT pair for the whole amount that is NOT flagged as
-- an internal transfer, so cash flow keeps it pending review instead of guessing (PR-011); its
-- interest and principal stay NULL here. split_estimated is the PR-011 flag: true for ESTIMATED
-- and UNKNOWN, and for SCHEDULE when the payment differs from the entry's total.
--
-- external_id is the caller's optional idempotency key, unique per loan account.
--
-- A row is never updated or deleted by the application: the ledger rows it names are append-only
-- (V68), and removing a payment voids or soft-deletes those rows, not this record of how it was
-- split. It names the rows as first written; a restored void's copies link to each other through
-- related_transaction_id (US-07-07), not to this row.
-- =============================================================================================

CREATE TABLE loan_payment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace (id),
    loan_account_id UUID NOT NULL REFERENCES account (id),
    paying_account_id UUID NOT NULL REFERENCES account (id),
    booking_date DATE NOT NULL,
    currency CHAR(3) NOT NULL,
    total_amount NUMERIC(20, 4) NOT NULL CHECK (total_amount > 0),
    interest_amount NUMERIC(20, 4) CHECK (interest_amount >= 0),
    principal_amount NUMERIC(20, 4) CHECK (principal_amount >= 0),
    split_source TEXT NOT NULL CHECK (
        split_source IN ('SCHEDULE', 'STATED', 'ESTIMATED', 'UNKNOWN')
    ),
    split_estimated BOOLEAN NOT NULL,
    amortisation_schedule_entry_id UUID
    REFERENCES amortisation_schedule_entry (id) ON DELETE SET NULL,
    principal_transaction_id UUID REFERENCES transaction (id),
    interest_transaction_id UUID REFERENCES transaction (id),
    external_id TEXT,
    created_by UUID NOT NULL REFERENCES app_user (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_loan_payment_accounts CHECK (loan_account_id <> paying_account_id),
    -- Known split: both parts, adding up to the payment. Unknown: neither, and no interest row.
    CONSTRAINT chk_loan_payment_split CHECK (
        (
            split_source <> 'UNKNOWN'
            AND interest_amount IS NOT NULL
            AND principal_amount IS NOT NULL
            AND interest_amount + principal_amount = total_amount
        )
        OR (
            split_source = 'UNKNOWN'
            AND split_estimated
            AND interest_amount IS NULL
            AND principal_amount IS NULL
            AND interest_transaction_id IS NULL
        )
    ),
    CONSTRAINT chk_loan_payment_rows CHECK (
        principal_transaction_id IS NOT NULL OR interest_transaction_id IS NOT NULL
    ),
    CONSTRAINT chk_loan_payment_schedule CHECK (
        split_source = 'SCHEDULE' OR amortisation_schedule_entry_id IS NULL
    )
);

CREATE INDEX idx_loan_payment_loan_account ON loan_payment (loan_account_id, booking_date);
CREATE INDEX idx_loan_payment_paying_account ON loan_payment (paying_account_id);
CREATE INDEX idx_loan_payment_workspace ON loan_payment (workspace_id);
CREATE INDEX idx_loan_payment_schedule_entry ON loan_payment (amortisation_schedule_entry_id)
WHERE amortisation_schedule_entry_id IS NOT NULL;
CREATE UNIQUE INDEX uq_loan_payment_principal_transaction
ON loan_payment (principal_transaction_id)
WHERE principal_transaction_id IS NOT NULL;
CREATE UNIQUE INDEX uq_loan_payment_interest_transaction
ON loan_payment (interest_transaction_id)
WHERE interest_transaction_id IS NOT NULL;
CREATE UNIQUE INDEX uq_loan_payment_external_id
ON loan_payment (loan_account_id, external_id)
WHERE external_id IS NOT NULL;

ALTER TABLE loan_payment ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_payment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON loan_payment
USING (workspace_id = current_workspace_id());
