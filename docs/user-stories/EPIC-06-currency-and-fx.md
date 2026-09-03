# EPIC 06 — Currency & FX Management

Covers `fx_rate` (V8) and the currency-conversion rules in section 11. FR-CUR-*.

---

## US-06-01 — Store and retrieve FX rates as a dated parallel series

**Actor:** System (background job) / Developer
**Objective:** FR-CUR-007, FR-PRC-013 — FX rates are a parallel time series with the same
provenance rules as prices.
**Story:** As the system, I want to fetch and store daily FX rates for every currency pair
actually needed by a household's accounts/securities, so that historical and current conversions
are always available from stored data.
**Preconditions:** At least one account/security exists in a non-reporting currency.
**Acceptance criteria:**
- Given CHF is a user's reporting currency and they hold a USD account, when the daily FX job
  runs, then a `fx_rate` row for `(USD, CHF, today, source)` is stored.
- Given a rate for a given date/pair/source already exists, when the job runs again, then no
  duplicate is created (`UNIQUE(base_currency, quote_currency, rate_date, source)`).
- Given a weekend/holiday with no published rate, when a conversion is requested for that date,
  then the last available prior rate is used and the result is marked as carried-forward
  (FR-CUR-012, PR-011) — never silently treated as if it were an exact rate for that date.
**Applicable business rules:** FR-CUR-007/009/012, FR-PRC-013.
**Data requirements:** CHF and EUR are first-class at launch; USD/GBP and others supported
(FR-CUR-009).
**Error/edge cases:** Provider unavailable — degrade to last stored value, mark stale
(NFR-CON-003), never block access to already-stored data (PR-012).
**Authorization/privacy:** `fx_rate` carries no household_id (shared data, NFR-LIC-006/007); no
tenant check needed on read.
**Dependencies:** EPIC 30 background jobs (for the scheduled fetch); this story's storage/read
contract can be built and tested independently with manually inserted rates.
**Priority:** MUST.
**Definition of Done:** Integration test for same-day exact rate, weekend carry-forward, and the
staleness flag.
**Data-quality behaviour:** Carried-forward and stale rates must be visibly marked wherever they
are used to produce a displayed figure (PR-011).

---

## US-06-02 — Direct-pair conversion, never chained through an intermediate currency

**Actor:** Developer (correctness story)
**Objective:** FR-CUR-010 — chained conversion compounds rounding error and can make a container
summary and the consolidated view disagree on the same account.
**Story:** As a developer, I want every currency conversion to use the direct
source-currency-to-target-currency pair when a rate for that pair exists, rather than converting
through the container or reporting currency as an intermediate step, so that two views computing
the same underlying value never silently disagree.
**Preconditions:** Rates exist for `USD/CHF` directly; a scenario also has `USD` account inside a
`EUR` container reported in a `CHF` user reporting currency.
**Acceptance criteria:**
- Given a USD account inside a EUR-denominated container, reported to a CHF user, when the
  consolidated (CHF) and the institution summary (EUR, then separately converted to CHF for
  comparison in a test) are computed, when both ultimately express the same USD amount in CHF,
  then they agree to the configured rounding policy (NFR-CALC-007), because both use the direct
  `USD/CHF` pair rather than chaining `USD→EUR→CHF`.
- Given no direct pair exists for an unusual currency, when conversion is attempted, then the
  system falls back to a documented, explicit chain (e.g. via a common intermediate) and marks
  the result as such, rather than silently failing.
**Applicable business rules:** FR-CUR-010/011.
**Data requirements:** None beyond FX rates.
**Error/edge cases:** No rate available at all, in any chain — the figure must be refused/flagged
rather than computed with a stale or missing rate silently defaulted to 1.0.
**Authorization/privacy:** N/A.
**Dependencies:** US-06-01.
**Priority:** MUST.
**Definition of Done:** A test scenario with three currencies (USD account, EUR container, CHF
user) asserting all views agree to the rounding policy — this is the V-08/V-19-adjacent
correctness case from the golden dataset (EPIC 27).
**Data-quality behaviour:** N/A (this story is itself a data-quality guarantee).

---

## US-06-03 — Conversion date convention is explicit per figure class

**Actor:** Developer (correctness story)
**Objective:** FR-CUR-011 — transaction-date rate for realised flows, valuation-date rate for
balances/holdings, period-end rate for closing positions; mixing conventions silently is a common
source of unexplainable differences.
**Story:** As a developer, I want the FX rate date used for any given figure to follow one
documented convention based on the figure's class, so that "why does this number not match what I
calculated by hand" has one clear, consistent answer.
**Preconditions:** None (design/implementation story).
**Acceptance criteria:**
- Given a realised dividend received in USD, when its CHF-equivalent is computed for cash-flow
  reporting, then the rate used is dated to the transaction's booking/value date.
- Given a USD position's current value, when displayed in CHF, then the rate used is dated to the
  valuation date (today, or the report's as-of date), not the position's acquisition date.
- Given a closing net-worth figure for a past month-end, when computed, then the period-end rate
  is used consistently, and the methodology document (`docs/architecture/calculation-methodology.md`,
  to be authored per NFR-CALC-003 — see EPIC 27) states this explicitly.
**Applicable business rules:** FR-CUR-011.
**Data requirements:** None.
**Error/edge cases:** None beyond consistent application.
**Authorization/privacy:** N/A.
**Dependencies:** US-06-01, US-06-02.
**Priority:** MUST.
**Definition of Done:** Documented in the calculation-methodology reference and covered by unit
tests for each figure class named above.
**Data-quality behaviour:** N/A.
