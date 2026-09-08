# EPIC 26 — Pension & Retirement Accounts

Covers `account_pension`, `account_vested_benefits` (V5), `pension_contribution_tracking` (V14),
`pension_scheme_rule` (V18). Section 25a, FR-PEN-*. Explicitly **not** a tax feature (RULE-019) —
contribution-limit tracking is a savings-goal feature.

---

## US-26-01 — Multiple Pillar 3a accounts, one provider or several

**Actor:** Workspace member
**Objective:** FR-PEN-002, C4 — standard Swiss practice for staggered withdrawal.
**Story:** As a Swiss workspace member, I want to hold and track several Pillar 3a accounts (at
one provider or across several), so that staggered-withdrawal planning is representable.
**Preconditions:** A `PENSION_PROVIDER` institution (e.g. VIAC) exists.
**Acceptance criteria:**
- Given two `PENSION` accounts with `pension_scheme = 'CH_PILLAR_3A'` under the same institution,
  when both are created with distinct user-defined names, then both coexist without conflict
  (same pattern as EPIC 04's US-04-02, applied specifically to 3a).
- Given a workspace holds 3a accounts at two different institutions (e.g. VIAC and PostFinance),
  when consolidated pension totals are viewed, then both are summed correctly regardless of
  provider.
**Applicable business rules:** FR-PEN-002, C4, RULE-022.
**Data requirements:** `account_pension.pension_scheme`.
**Error/edge cases:** None beyond the general account-creation rules (EPIC 05).
**Authorization/privacy:** Workspace-scoped.
**Dependencies:** EPIC 04, EPIC 05.
**Priority:** MUST.
**Definition of Done:** Integration test creates two 3a accounts at two institutions and confirms
correct consolidated totals.
**Data-quality behaviour:** N/A.

---

## US-26-02 — Annual contribution-limit tracking against effective-dated configuration

**Actor:** Workspace member
**Objective:** FR-PEN-003/006 — a savings-goal feature; the limit is configuration data, never
hard-coded.
**Story:** As a Swiss workspace member, I want to see how much I've contributed to my Pillar 3a
this year against the applicable annual limit, with a reminder before the year-end deadline, so
that I can decide whether to top up before the deadline.
**Preconditions:** A `pension_scheme_rule` row exists for `CH_PILLAR_3A` covering the current
year (V18 reference data — populated via a reference-package import, EPIC 32, since the exact
figure changes annually and must never require a code release, per FR-PEN-006).
**Acceptance criteria:**
- Given contributions totalling CHF 6,000 recorded this year and a configured limit of CHF 7,258
  (illustrative — the real annual CH Pillar 3a limit must be sourced from official guidance at
  implementation time, not assumed by this story), when the tracking view is requested, then it
  shows CHF 6,000 contributed, CHF 1,258 remaining.
- Given no `pension_scheme_rule` row covers the current year (the reference data has gone stale),
  when the tracking view is requested, then it shows a clear staleness warning rather than
  silently applying a prior year's limit as current (FR-REF-011).
- Given the year-end deadline approaches (a configurable lead time), when a workspace member has
  remaining contribution capacity, then an optional reminder notification is available
  (FR-PEN-004) — off by default per NFR-NOT-02-style "no dark-pattern engagement loops," on by
  opt-in.
**Applicable business rules:** FR-PEN-003/004/006, FR-REF-011, RULE-019 (this is savings tracking,
never tax advice).
**Data requirements:** `pension_contribution_tracking` per account/year;
`pension_scheme_rule.annual_contribution_limit` effective-dated.
**Error/edge cases:** As above — stale/missing reference data must warn, never silently misapply.
**Authorization/privacy:** Workspace-scoped.
**Dependencies:** US-26-01, EPIC 32 (reference-data import for the limit itself).
**Priority:** SHOULD.
**Definition of Done:** Integration test for the normal case and the stale-reference-data case.
**Data-quality behaviour:** As above — this story is largely about the staleness case.

---

## US-26-03 — Occupational pension (Pillar 2 / bAV) as balance-and-entitlement, not a portfolio

**Actor:** Workspace member
**Objective:** FR-ACC-030, FR-PEN (v0.1 doc)-14/DM-16 — forcing it into a holdings model produces
meaningless performance figures.
**Story:** As a workspace member, I want to record my occupational pension (CH Pillar 2 vested
benefits, or DE bAV) as a balance updated from my annual certificate — vested benefit, interest
credit, employer/employee contributions — with no synthetic performance figure computed from it,
so that it contributes correctly to net worth without a misleading "return" attached.
**Preconditions:** A `VESTED_BENEFITS` account exists.
**Acceptance criteria:**
- Given the annual certificate reports a vested benefit of CHF 85,000, when entered, then
  `account_vested_benefits.vested_benefit_amount` is updated and `last_certificate_date` recorded;
  net worth reflects the new figure.
- Given this account type, when the performance view (EPIC 16) is requested for it, then no
  TWR/MWR is offered — the UI explicitly states performance is not applicable for a
  balance-only entitlement account, rather than silently showing 0% or an error.
- Given voluntary purchase capacity (Einkauf) is entered, when saved, then it is surfaced as a
  savings-goal input (FR-ACC-031), explicitly not as a tax-optimisation feature (RULE-019).
**Applicable business rules:** FR-ACC-030/031, DM-16, RULE-019.
**Data requirements:** `account_vested_benefits` fields (V5).
**Error/edge cases:** A user attempts to enter transactions/positions against a
`VESTED_BENEFITS` account — must be rejected; this account type has `has_transactions = false`,
`holds_positions = false` by convention (confirm defaults in the account-creation service from
US-05-01).
**Authorization/privacy:** Workspace-scoped.
**Dependencies:** EPIC 05.
**Priority:** SHOULD.
**Definition of Done:** Integration test enters a certificate update and confirms no performance
figure is offered for the account.
**Data-quality behaviour:** N/A.
