# EPIC 18 — Institution Summary

Covers investment-specific aggregation within `financial_institution` (V3) summaries. Section 9.1.
Balance/liability/net-value aggregation and currency conversion are covered by **EPIC 04's
US-04-03** — this epic covers the investment-performance and allocation portion of the same
summary view, which depends on EPIC 15/16/17 being built first.

---

## US-18-01 — Institution summary includes investment performance for its accounts

**Actor:** Household member
**Objective:** Section 9.1's Institution Summary View content list — "investment performance,
TWR, MWR and asset allocation for investment accounts."
**Story:** As a household member, I want an institution's summary page to show TWR, MWR and asset
allocation aggregated across just that institution's investment accounts, so that I can compare
"how is my DKB depot doing" against "how is my Broker X depot doing" without switching to the
global consolidated view.
**Preconditions:** An institution with at least one position-holding account.
**Acceptance criteria:**
- Given an institution with two depots, when its summary is requested, then TWR/MWR are computed
  for the combined scope of just those two accounts (reusing EPIC 16's scope-parameterised
  calculation, not a separate implementation).
- Given the institution also holds a cash account with no positions, when the summary's
  allocation chart is shown, then the cash account contributes to the institution's total value
  but not to the security-level allocation breakdown (consistent with EPIC 05's capability
  model).
**Applicable business rules:** Section 9.1, FR-PERF-006 (scope includes institution level).
**Data requirements:** None beyond EPIC 15/16/17's outputs.
**Error/edge cases:** An institution with zero investment accounts (e.g. a pure banking
relationship) — the performance/allocation section is simply omitted or shown as "not
applicable," not an error.
**Authorization/privacy:** Household-scoped.
**Dependencies:** EPIC 04 (US-04-03), EPIC 15, EPIC 16, EPIC 17.
**Priority:** SHOULD.
**Definition of Done:** Integration test computes institution-scoped TWR and compares it against
the same accounts' figure computed at account scope directly, asserting they match.
**Data-quality behaviour:** Inherits the data-quality flags from the underlying account-level
computations (PR-011) — an institution summary must never present a cleaner picture than its
worst-quality contributing account.
