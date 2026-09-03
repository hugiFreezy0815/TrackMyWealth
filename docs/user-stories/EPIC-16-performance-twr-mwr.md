# EPIC 16 — Performance / TWR / MWR

Covers `daily_valuation` (V11, partitioned). Section 23, FR-PERF-*, RULE-030.

---

## US-16-01 — Daily valuation series is built and maintained per account

**Actor:** System
**Objective:** FR-PERF-010, RULE-030 — an architectural prerequisite, not a reporting feature;
retrofitting later requires a full historical recomputation per household.
**Story:** As the system, I want to maintain a daily value + external-cashflow record per
investment account, so that TWR can be computed without ever needing to reconstruct history after
the fact.
**Preconditions:** A `SECURITIES`/`PENSION`(position-holding)/`MANAGED_MANDATE` account with
positions and prices.
**Acceptance criteria:**
- Given an account with positions priced daily, when the daily valuation job runs, then a
  `daily_valuation` row is written for each date with `value` = Σ(position × price, converted to
  the account's currency) and `external_cashflow_amount` = net deposits/withdrawals that date.
- Given a backdated transaction changes historical positions, when the rebuild job runs (shared
  with EPIC 15's US-15-01), then `daily_valuation` rows from the affected date forward are
  recomputed, not the whole series (FR-JOB-006).
- Given a non-trading day (weekend/holiday, per US-13-03's trading calendar), when the series is
  built, then the prior day's value is carried forward with `valuation_method = 'CARRIED_FORWARD'`,
  never interpolated.
**Applicable business rules:** FR-PERF-010, RULE-030, FR-JOB-006.
**Data requirements:** `daily_valuation` partitioned table (V11); requires prices (EPIC 14) and
positions (EPIC 15) to be current.
**Error/edge cases:** An account with a gap in price data for part of its history — that stretch
must be flagged `is_estimated = true` and excluded from any metric that would otherwise silently
treat it as zero-return.
**Authorization/privacy:** Household-scoped (RLS via `account_id` join, transitively protected —
see `database-schema.md` section 4).
**Dependencies:** EPIC 14, EPIC 15.
**Priority:** MUST.
**Definition of Done:** Integration test builds a multi-week series across a weekend and a
backdated correction, asserting correct carry-forward and targeted recomputation.
**Data-quality behaviour:** As above.

---

## US-16-02 — Time-Weighted Return (TWR)

**Actor:** Household member
**Objective:** FR-PERF-003/011, section 46.
**Story:** As a household member, I want to see the Time-Weighted Return for an account, a
portfolio, or my whole household, so that I understand how the underlying investment strategy
performed independent of when I added or withdrew money.
**Preconditions:** US-16-01 complete for the relevant scope.
**Acceptance criteria:**
- Given a daily valuation series with two external cash flows during the period, when TWR is
  computed, then it is built by geometrically linking the sub-period returns bounded by those two
  cash flows (FR-PERF-011) — an internal movement (e.g. a purchase funded by cash already in
  scope) does not create a sub-period boundary.
- Given the period requested is YTD/1Y/3Y/5Y/Max/custom (FR-PRF-04, v0.1 doc numbering), when
  computed, then each period's TWR is available, with annualised and cumulative variants clearly
  and separately labelled (FR-PERF-014).
- Given an exact valuation at a cash-flow date is unavailable, when Modified Dietz is used as a
  documented approximation, then the period's result records that the approximation was used
  (FR-PERF-012), inspectable via "explain this number" (FR-PERF-016).
**Applicable business rules:** FR-PERF-003/010/011/012/014/016, section 46 methodology.
**Data requirements:** `daily_valuation` series for the scope.
**Error/edge cases:** A scope containing an account whose history is too incomplete (opening
balance never reconstructed) — FR-PERF-018 requires refusing the figure rather than computing a
misleadingly precise one; see US-16-04.
**Authorization/privacy:** Household-scoped.
**Dependencies:** US-16-01.
**Priority:** MUST.
**Definition of Done:** Known-answer test against a published worked TWR example
(NFR-CALC-006), plus V-10 in the golden dataset ("large mid-period deposit followed by a market
fall — divergence of TWR and MWR must be in the expected direction").
**Data-quality behaviour:** N/A (covered by US-16-04).

---

## US-16-03 — Money-Weighted Return (MWR / XIRR)

**Actor:** Household member
**Objective:** FR-PERF-004, section 46.
**Story:** As a household member, I want to see the Money-Weighted Return for an account,
portfolio or household, so that I understand my own actual return experience including the timing
of my contributions.
**Preconditions:** Dated external cash flows and a closing value for the scope.
**Acceptance criteria:**
- Given a series of dated external cash flows and a closing value, when MWR is computed via XIRR,
  then it returns the annualised rate that sets the net present value of all flows plus the
  closing value to zero, matching a published worked example to the documented convergence
  tolerance (NFR-CALC-006).
- Given the XIRR solver has no solution or multiple solutions for a pathological cash-flow
  pattern, when this occurs, then the result is refused with a clear explanation rather than
  returning an arbitrary root (section 46: "definition must state ... behaviour when no solution
  exists or several do").
- Given a discretionary account (`is_discretionary = true`), when its default headline metric is
  requested, then TWR is shown by default and MWR is available as a secondary figure (FR-ACC-022/
  FR-PERF-019) — reversed for self-directed accounts.
**Applicable business rules:** FR-PERF-004/019, FR-ACC-022, section 46.
**Data requirements:** Dated cash flows (from `transaction`) plus current value.
**Error/edge cases:** As above (no/multiple XIRR roots).
**Authorization/privacy:** Household-scoped.
**Dependencies:** US-16-01.
**Priority:** MUST.
**Definition of Done:** Known-answer test against a published XIRR worked example; V-10 in the
golden dataset asserts TWR and MWR diverge in the expected direction for a large mid-period
deposit before a fall.
**Data-quality behaviour:** N/A.

---

## US-16-04 — Insufficient data refuses the figure rather than estimating it

**Actor:** System
**Objective:** FR-PERF-018, PR-010 — a wrong number displayed confidently is worse than a figure
withheld with an explanation.
**Story:** As the system, when a requested performance figure cannot be reliably calculated (no
opening cost basis, an unreconciled gap, missing prices across a material period), I want to
state clearly that the figure cannot be computed rather than compute and display a technically-
derived-but-misleading number.
**Preconditions:** An account whose transaction history begins after the account was actually
opened (V-11 scenario), with no opening balance ever reconstructed.
**Acceptance criteria:**
- Given such an account, when TWR/MWR for "Max" period is requested, then the response explicitly
  states the figure cannot be reliably calculated for the portion before the reconstructed/known
  start date, and offers the "since data available" period instead as a substitute the user can
  request explicitly.
- Given the same account after the user completes opening-balance reconstruction (FR-REC-007,
  EPIC 25), when the figure is requested again, then it is now computable for the full period.
**Applicable business rules:** FR-PERF-018, PR-010/011.
**Data requirements:** None beyond the incomplete-history scenario.
**Error/edge cases:** N/A — this story *is* the edge-case handling for every other story in this
epic.
**Authorization/privacy:** Household-scoped.
**Dependencies:** US-16-02, US-16-03, EPIC 25 (opening balance reconstruction).
**Priority:** MUST.
**Definition of Done:** This is V-11 in the golden verification dataset ("account whose imported
history begins two years after opening — opening balance reconstruction; refusal to report
meaningless earlier returns").
**Data-quality behaviour:** This story is the data-quality behaviour.
