# EPIC 19 — Consolidated Reporting

The top-level view tying together every prior epic. Section 26, FR-CON-*, FR-NAV-*. Depends on
essentially all migrations and all prior epics — sequence this near the end of the MVP build.

---

## US-19-01 — Consolidated view in the user's reporting currency, with consistent scope

**Actor:** Workspace member
**Objective:** FR-CON-001..006, FR-NAV-20..26.
**Story:** As a workspace member, I want one top-level view showing net worth, assets and
liabilities, cash flow, investments, allocation, performance and dividends, all converted to my
personal reporting currency, with a clearly visible and consistently-applied scope filter, so
that I never wonder whether the number on screen includes my pension or not.
**Preconditions:** A workspace with accounts across multiple institutions and currencies (the
representative scenario in specification section 43).
**Acceptance criteria:**
- Given the representative acceptance scenario (PostFinance CHF, DKB EUR, Broker X USD, VIAC CHF
  pension, Personal Assets with real estate + mortgage, user reporting currency CHF), when the
  consolidated view is requested, then every monetary figure is shown in CHF, using historically
  appropriate FX rates for historical figures (FR-CON-002).
- Given a scope filter is applied (e.g. "exclude pensions"), when any sub-view (net worth,
  allocation, performance) is subsequently viewed, then the same scope applies consistently
  across all of them, and the active scope is always visibly displayed (FR-CON-006, FR-NAV-25).
- Given every figure is drillable, when a user clicks the consolidated net-worth total, then they
  can drill to institution → account → position/transaction (FR-CON-005, FR-NAV-24).
**Applicable business rules:** FR-CON-001..007, FR-NAV-20..26, RULE-004/005/006.
**Data requirements:** Outputs of every prior epic.
**Error/edge cases:** A currency with no direct FX pair available for the requested date — falls
back per FR-CUR-010/012 rules (EPIC 06), visibly flagged.
**Authorization/privacy:** Workspace-scoped, respects per-member sharing grants for person-scoped
consolidated views (FR-TEN-011).
**Dependencies:** All prior epics (01-18).
**Priority:** MUST.
**Definition of Done:** End-to-end integration test reproduces specification section 43's
representative acceptance scenario in full, including the specific acceptance conditions it lists
(VIAC holdings in allocation, cross-depot ETF consolidation, reconciliation on manual balance
entry, mortgage payment split, USD currency attribution).
**Data-quality behaviour:** FR-CON-007 — where the consolidated view includes unreconciled
accounts, stale prices, or estimated valuations, this must be indicated at the level of the
headline figure itself, not only in a detail view a user might not open.

---

## US-19-02 — Consolidated totals reconcile against the sum of institution summaries

**Actor:** Developer / QA (correctness story)
**Objective:** FR-CON-008.
**Story:** As a developer, I want the sum of every institution's own net value (each converted to
the reporting currency) to equal the consolidated net worth, with any residual explicitly
identified rather than silently absorbed, so that two independently-computed paths to the "same"
number can never quietly disagree.
**Preconditions:** US-19-01, US-04-03 (institution summaries).
**Acceptance criteria:**
- Given the representative acceptance scenario, when both figures are computed independently
  (institution-by-institution sum vs. the single consolidated computation), then they match
  exactly, or, if a genuine residual exists from FX-conversion-timing differences, it is reported
  as a named, visible line item rather than folded silently into one of the two totals.
**Applicable business rules:** FR-CON-008, FR-CUR-010 (direct-pair rule, EPIC 06's US-06-02).
**Data requirements:** None beyond the above.
**Error/edge cases:** None beyond the reconciliation itself.
**Authorization/privacy:** N/A.
**Dependencies:** US-19-01, EPIC 06 (US-06-02).
**Priority:** SHOULD.
**Definition of Done:** Automated regression test asserting the two computation paths agree,
added to the golden-dataset suite (EPIC 27) so a future change that breaks this is caught in CI.
**Data-quality behaviour:** N/A (this story is itself a correctness guarantee).

---

## US-19-03 — Beginner vs. advanced progressive disclosure

**Actor:** Beginner workspace member / Advanced workspace member
**Objective:** PR-006, NFR-UX-001.
**Story:** As a beginner investor, I want a clear, understandable summary by default (simple
return, plain-language net worth), and as an advanced investor I want the same screen to let me
drill into TWR/MWR, currency attribution and GICS-level allocation, so that the product serves
both without overwhelming either.
**Preconditions:** US-19-01.
**Acceptance criteria:**
- Given a workspace member with no explicit preference set, when the consolidated view loads,
  then the default headline performance figure is simple absolute return/P&L (FR-PERF-013), not
  TWR/MWR.
- Given the same member expands "advanced metrics," when shown, then TWR, MWR, currency
  attribution and GICS drill-down become available in place without a separate screen/mode
  switch that loses their current scope/date-range selection.
**Applicable business rules:** PR-006, NFR-UX-001, FR-PERF-013.
**Data requirements:** None beyond prior epics' outputs.
**Error/edge cases:** None.
**Authorization/privacy:** N/A.
**Dependencies:** US-19-01, EPIC 16.
**Priority:** SHOULD.
**Definition of Done:** UI-level acceptance test (out of backend scope, but the API contract must
expose both the simple and advanced figures in one response so the frontend does not need two
round trips).
**Data-quality behaviour:** N/A.
