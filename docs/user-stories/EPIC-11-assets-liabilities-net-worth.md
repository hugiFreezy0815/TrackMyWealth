# EPIC 11 — Assets, Liabilities & Net Worth

Covers net-worth aggregation logic over `account`/extensions (V4/V5), `amortisation_schedule_entry`
(V5), `custom_asset_valuation` (V5), `position` (V12). Section 17, FR-NW-*.

---

## US-11-01 — Consolidated net worth, correctly signed

**Actor:** Household member
**Objective:** FR-NW-001, DM-12/RULE-*.
**Story:** As a household member, I want to see total assets minus total liabilities across every
account in the household, correctly signed, so that a mortgage or credit-card balance always
reduces net worth rather than being accidentally added.
**Preconditions:** A household with a mix of asset and liability accounts.
**Acceptance criteria:**
- Given CHF 50,000 in cash/investment accounts and a CHF 300,000 mortgage, when net worth is
  computed, then it correctly shows CHF -250,000, using each account's `nature` (generated
  column, V4) as the sign — never a hand-rolled sign computation in application code.
- Given the computation, when scoped by owner/institution/account/asset-class/currency
  (FR-NW-004), then each scope produces internally consistent totals that sum correctly to the
  household-wide figure.
**Applicable business rules:** FR-NW-001/004, DM-12, RULE-012 (net worth summing).
**Data requirements:** All accounts must have a current value resolvable via the DM-17 uniform
valuation interface (EPIC 05's US-05-04).
**Error/edge cases:** An account with no resolvable value (e.g. a custom asset never valuated) —
excluded with a visible flag, not treated as zero (see US-05-05).
**Authorization/privacy:** Household-scoped, respects sharing grants for person-scoped views.
**Dependencies:** EPIC 05, EPIC 06.
**Priority:** MUST.
**Definition of Done:** Integration test with a realistic multi-account household matching the
representative acceptance scenario in specification section 43.
**Data-quality behaviour:** Any contributing account with a stale price, an open reconciliation
difference, or an unresolvable value must be surfaced at the net-worth headline, not buried
(FR-CON-007/PR-011).

---

## US-11-02 — Historical net worth with contribution-vs-performance decomposition

**Actor:** Household member
**Objective:** FR-NW-002/005 — without this, a user who saved diligently during a falling market
cannot tell that they did the right thing.
**Story:** As a household member, I want to see how my net worth has changed over time, broken
down into money added/withdrawn versus value gained/lost, so that a market downturn does not read
as "I did something wrong."
**Preconditions:** Historical `daily_valuation` data (EPIC 25/15) and transaction history.
**Acceptance criteria:**
- Given a period where the household deposited CHF 5,000 and the portfolio lost CHF 2,000 in
  value, when the net-worth chart is viewed for that period, then it shows net worth up CHF
  3,000 overall, explicitly decomposed as +CHF 5,000 contributions and -CHF 2,000 performance.
**Applicable business rules:** FR-NW-002/005.
**Data requirements:** `daily_valuation.external_cashflow_amount` per account, aggregated.
**Error/edge cases:** A period with missing daily valuations for part of an account's history —
that portion of the decomposition must be flagged as estimated/unavailable (PR-011), not silently
interpolated.
**Authorization/privacy:** Household-scoped.
**Dependencies:** US-11-01, EPIC 16 (daily valuation series).
**Priority:** MUST.
**Definition of Done:** Integration test with a synthetic deposit-then-loss scenario asserting the
correct decomposition.
**Data-quality behaviour:** As above.

---

## US-11-03 — Real estate shown gross, net of financing, never double-counting debt

**Actor:** Household member
**Objective:** FR-NW-007, FR-POR-04 (v0.1 doc numbering).
**Story:** As a household member with a mortgaged property, I want to see its value both gross
and net of the linked mortgage, without the mortgage being subtracted twice.
**Preconditions:** A `CUSTOM_ASSET` (real estate) account linked via `account_mortgage.linked_asset_account_id`
to a `MORTGAGE` account.
**Acceptance criteria:**
- Given a CHF 900,000 property with a CHF 600,000 linked mortgage, when net worth is computed,
  then the property contributes +CHF 900,000 (asset) and the mortgage contributes -CHF 600,000
  (liability) — net CHF 300,000 — computed once each, never as a single pre-netted CHF 300,000
  asset value that would also then have the mortgage subtracted again.
- Given the household wants a "net of financing" view specifically, when requested, then it shows
  CHF 300,000 as a single figure, clearly labelled as net (FR-NW-007).
**Applicable business rules:** FR-NW-007, FR-POR-04.
**Data requirements:** `account_mortgage.linked_asset_account_id`.
**Error/edge cases:** A mortgage with no linked asset (e.g. entered before the property was) —
must still count correctly as a standalone liability; linking is optional, not required for
correctness of the liability side alone.
**Authorization/privacy:** Household-scoped.
**Dependencies:** EPIC 05.
**Priority:** SHOULD.
**Definition of Done:** Integration test asserts the gross+liability sum equals the net view.
**Data-quality behaviour:** N/A.

---

## US-11-04 — Liquidity view by accessibility

**Actor:** Household member
**Objective:** FR-NW-008 — pension assets are frequently the largest single component and are not
spendable.
**Story:** As a household member, I want to see net worth split by how accessible it actually is
(immediately available, available with notice, locked), so that I don't overestimate my real
financial flexibility because a large Pillar 3a balance is included in the headline figure.
**Preconditions:** A household with cash, investment and pension accounts.
**Acceptance criteria:**
- Given CHF 20,000 cash, CHF 100,000 in a depot, and CHF 150,000 in a Pillar 3a, when the
  liquidity view is requested, then cash is "immediately available," the depot is "available with
  notice" (or immediately, per a documented classification rule), and the 3a is "locked."
- Given the same figures, when the standard net-worth headline is shown, then it still shows the
  unified total (this view is additive, not a replacement).
**Applicable business rules:** FR-NW-008.
**Data requirements:** An accessibility classification per `account_type` (documented as
reference data, not hard-coded per-account).
**Error/edge cases:** A custom asset with genuinely ambiguous liquidity (e.g. a collectible) —
defaults conservatively to "locked" unless the user overrides.
**Authorization/privacy:** Household-scoped.
**Dependencies:** US-11-01.
**Priority:** SHOULD.
**Definition of Done:** Integration test with the three-account scenario above.
**Data-quality behaviour:** N/A.
