# EPIC 10 — Budgeting & Cash Flow

Covers `budget`, `budget_line`, `savings_rate_methodology` (V14) plus the cash-flow
classification rules on `transaction` (V10). Section 15-16, FR-CF-*, FR-BUD-*.

---

## US-10-01 — Internal transfers never count as income or expense

**Actor:** System / Workspace member confirming a match
**Objective:** RULE-007, FR-CF-001/005, DM-05 — the classic failure mode of combined trackers.
**Story:** As a workspace member, I want a transfer between two of my own accounts to be excluded
from income/expense totals entirely, so that moving money to savings or into a brokerage account
is never misread as spending.
**Preconditions:** Two accounts owned by the same workspace.
**Acceptance criteria:**
- Given a CHF 500 transfer from a current account to a savings account, when both legs are
  imported, then the matching service links them via `counterparty_account_id`, sets
  `is_internal_transfer = true` on both, and cash-flow reports exclude both from income/expense.
- Given a transfer into a `SECURITIES` account followed by a security purchase, when cash-flow is
  computed, then neither the transfer nor the purchase is counted as a consumer expense
  (FR-CF-002) — only categorised spending is.
- Given only one leg has been imported so far, when viewed, then it is shown as an unmatched
  transfer candidate rather than counted as income/expense by default (FR-CF-005).
**Applicable business rules:** RULE-007/008, FR-CF-001/002/003/004/005, DM-05.
**Data requirements:** None beyond the two transactions.
**Error/edge cases:** A same-amount, same-day coincidence between two unrelated transactions in
different accounts — must be presented as a *candidate* match requiring confirmation for
ambiguous cases, not auto-applied silently when confidence is low.
**Authorization/privacy:** Workspace-scoped.
**Dependencies:** EPIC 07.
**Priority:** MUST.
**Definition of Done:** Integration test for the transfer-to-savings and transfer-to-investment
scenarios, asserting exclusion from cash-flow totals.
**Data-quality behaviour:** N/A.

---

## US-10-02 — Mortgage/loan payment splits into interest (expense) and principal (balance-sheet)

**Actor:** System / Workspace member
**Objective:** FR-CF-006, FR-NW-005 — a common and material workspace-budgeting error otherwise.
**Story:** As a workspace member, I want a mortgage or loan payment to be automatically separated
into its interest component (a real expense) and principal component (a balance-sheet movement,
not consumption), so that my spending and savings-rate figures are not distorted.
**Preconditions:** A `MORTGAGE` or `LOAN` account with an amortisation schedule
(`amortisation_schedule_entry`).
**Acceptance criteria:**
- Given a CHF 2,000 monthly mortgage payment where the amortisation schedule says CHF 800 is
  interest and CHF 1,200 is principal, when the payment is recorded, then cash-flow reporting
  shows CHF 800 as an expense and CHF 1,200 as debt repayment (a savings-rate-relevant,
  non-expense category, `transaction_type = 'DEBT_REPAYMENT'` for the principal portion).
- Given the savings-rate methodology setting `include_mortgage_principal = true` (the default,
  `savings_rate_methodology`), when savings rate is computed, then the principal repayment counts
  toward it.
**Applicable business rules:** FR-CF-006, FR-NW-006, FR-BUD-009.
**Data requirements:** `amortisation_schedule_entry` rows for the account.
**Error/edge cases:** No amortisation schedule has been entered yet — the whole payment must be
flagged as an estimate (interest/principal split unknown) rather than silently treated as 100%
interest or 100% principal (PR-011).
**Authorization/privacy:** Workspace-scoped.
**Dependencies:** EPIC 05 (mortgage/loan accounts), EPIC 11 (amortisation schedule).
**Priority:** SHOULD.
**Definition of Done:** This is V-14 in the golden verification dataset (EPIC 27) — implement as
an automated regression test.
**Data-quality behaviour:** As above — unknown split must be visibly estimated, never silently
guessed with full confidence.

---

## US-10-03 — Budget proposal derived from actual transaction history

**Actor:** Workspace member
**Objective:** FR-BUD-007 — budgets entered as intentions fail; budgets derived from observed
behaviour hold (BlueBudget's validated design thesis).
**Story:** As a workspace member creating my first budget, I want the system to propose category
amounts based on my own historical spending rather than presenting empty fields, so that I start
from something realistic.
**Preconditions:** At least a few months of categorized transaction history.
**Acceptance criteria:**
- Given 3+ months of categorized spending, when a new budget is created, then each `budget_line`
  is pre-populated with a proposed amount (e.g. a trailing average) per category, and
  `budget.derived_from_history = true`.
- Given insufficient history exists (a brand-new workspace), when a budget is created, then the
  proposal step is skipped gracefully and empty fields are presented instead — never a proposal
  computed from too little data presented with unwarranted confidence (PR-011).
**Applicable business rules:** FR-BUD-001..004/007.
**Data requirements:** Categorized transactions across at least one full period.
**Error/edge cases:** Irregular/annual costs skewing the trailing average — see US-10-04.
**Authorization/privacy:** Workspace-scoped.
**Dependencies:** EPIC 08.
**Priority:** SHOULD.
**Definition of Done:** Integration test with synthetic multi-month history asserts sensible
proposed amounts; a fresh-workspace test asserts graceful empty-field fallback.
**Data-quality behaviour:** As above.

---

## US-10-04 — Irregular and annual costs do not read as a monthly overspend

**Actor:** Workspace member
**Objective:** FR-BUD-008.
**Story:** As a workspace member, I want an annual cost (insurance premium, Serafe fee, tax
instalment) to be amortised across months or flagged as upcoming rather than blowing out a single
month's budget line, so that one large annual bill is not misread as suddenly overspending.
**Preconditions:** A `budget_line` marked `is_irregular = true` with `amortised_over_months` set.
**Acceptance criteria:**
- Given an annual CHF 1,200 insurance premium paid in March, when the budget's "Insurance" line
  is marked irregular with `amortised_over_months = 12`, then March's progress view shows CHF 100
  attributed to March (and each other month), not CHF 1,200 in March and CHF 0 elsewhere.
- Given the same line is not marked irregular, when March's actual spending is shown, then it
  correctly shows the full CHF 1,200 with no smoothing — amortisation is opt-in per line, not
  automatic.
**Applicable business rules:** FR-BUD-008.
**Data requirements:** `budget_line.is_irregular`, `amortised_over_months`.
**Error/edge cases:** A payment that recurs irregularly (not exactly annual) — amortisation is
best-effort and documented as such, not a promise of exactness.
**Authorization/privacy:** Workspace-scoped.
**Dependencies:** US-10-03.
**Priority:** SHOULD.
**Definition of Done:** Integration test for the amortised vs. non-amortised comparison above.
**Data-quality behaviour:** N/A.

---

## US-10-05 — Savings rate with a documented, configurable methodology

**Actor:** Workspace member
**Objective:** FR-BUD-009 — every published savings-rate definition differs; an undocumented
choice makes the figure unusable.
**Story:** As a workspace member, I want to see and control whether employer pension
contributions, mortgage principal and unrealised investment gains count toward my savings rate,
so that the headline number means what I think it means.
**Preconditions:** A workspace with income/expense/investment activity.
**Acceptance criteria:**
- Given the default `savings_rate_methodology` row (created per workspace), when savings rate is
  displayed, then the three toggles' current settings are shown alongside the figure (not just
  buried in settings), and changing a toggle immediately recomputes the displayed rate.
- Given `include_unrealised_gains = false` (the default), when a portfolio gains value without
  any new contribution, then savings rate is unaffected by that gain.
**Applicable business rules:** FR-BUD-009.
**Data requirements:** `savings_rate_methodology` per workspace.
**Error/edge cases:** None beyond correct toggle application.
**Authorization/privacy:** Workspace-scoped.
**Dependencies:** US-10-02, EPIC 16 (unrealised gains).
**Priority:** MUST.
**Definition of Done:** Unit test for each of the eight toggle combinations' effect on a fixed
synthetic dataset.
**Data-quality behaviour:** N/A.
