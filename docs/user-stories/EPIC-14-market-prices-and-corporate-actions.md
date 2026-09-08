# EPIC 14 — Market Prices & Corporate Actions

Covers `price` (V8, partitioned), `corporate_action` (V9). Section 22, FR-PRC-*.

---

## US-14-01 — Price backfill on first reference

**Actor:** System
**Objective:** FR-PRC-002/010 — without this, TWR, historical net worth and allocation charts have
no data before the user joined.
**Story:** As the system, when a security is first referenced by a workspace's activity, I want
to backfill its price history from an external provider back to the earliest transaction date
that references it, so that historical charts are populated from day one rather than starting
blank.
**Preconditions:** A newly created `security`/`listing` with a transaction dated in the past.
**Acceptance criteria:**
- Given a `BUY` transaction dated three years ago for a newly referenced security, when the
  backfill job runs, then `price` rows are populated for that listing from three years ago to
  today (subject to provider availability), as an asynchronous background job (FR-JOB-001/008),
  not a blocking part of the transaction save.
- Given no external provider is configured (manual-price deployment, NFR-CON-004), when the
  backfill job runs, then it does nothing and the security is fully usable with manually entered
  prices — this must not be an error state.
**Applicable business rules:** FR-PRC-002/010, NFR-LIC-004/008.
**Data requirements:** Listing with a resolvable external identifier for the provider.
**Error/edge cases:** Provider has no history that far back — backfill as far as available and
flag the earlier period as having no price data (affects TWR — see US-16-04/FR-PERF-018).
**Authorization/privacy:** Global data, no workspace scoping.
**Dependencies:** EPIC 13, EPIC 30 (background jobs).
**Priority:** MUST.
**Definition of Done:** Integration test with a mocked provider asserting the backfilled date
range matches the earliest transaction date.
**Data-quality behaviour:** Any date range without a backfilled price must be visibly marked as
missing wherever it affects a computed figure.

---

## US-14-02 — Raw prices are immutable; adjusted series are derived on read

**Actor:** Developer (correctness story)
**Objective:** FR-PRC-009, RULE-026 — a provider that retroactively adjusts a stored series
corrupts historical accounting irreversibly.
**Story:** As a developer, I want prices to be stored exactly as delivered and never mutated in
place when a corporate action occurs, computing any split/dividend-adjusted series on read
instead, so that cost basis and realised gains always have access to the price actually traded on
the day.
**Preconditions:** A `corporate_action` (e.g. a 2-for-1 split) is recorded for a security with
existing `price` history.
**Acceptance criteria:**
- Given a 2-for-1 split effective on date D, when the raw price series is queried for a date
  before D, then it still shows the pre-split price exactly as originally stored — never
  retroactively halved.
- Given the same query is made for an "adjusted" (split-adjusted) series, when computed, then the
  pre-split prices are halved on the fly for display/charting purposes, without writing anything
  back to the `price` table.
- Given a position's cost basis and realised gain are computed (EPIC 15), when they reference a
  pre-split purchase, then they use the raw (unadjusted) historical price actually paid, not an
  adjusted figure.
**Applicable business rules:** FR-PRC-007/009/015, RULE-026, DM-27.
**Data requirements:** `corporate_action` rows with `ratio_numerator`/`ratio_denominator`.
**Error/edge cases:** A corporate action is entered incorrectly and later reversed — `is_reversed`
flag on `corporate_action` (FR-PRC-015: "automatically applied corporate actions shall be visible
and reversible") must cause the derived/adjusted series to revert too, still without touching raw
`price` rows.
**Authorization/privacy:** N/A.
**Dependencies:** EPIC 13.
**Priority:** MUST.
**Definition of Done:** This is V-01 (forward split) and V-02 (reverse split with fractional
residue) in the golden verification dataset (EPIC 27).
**Data-quality behaviour:** N/A (this story is itself a correctness guarantee).

---

## US-14-03 — Mergers, spin-offs and ISIN changes preserve position-history continuity

**Actor:** System
**Objective:** FR-PRC-007, FR-SMD-010, FR-IMD-16 — successor linkage.
**Story:** As the system, when a merger, spin-off or ISIN change occurs, I want position history
to remain continuous across the event via successor linkage, rather than appearing as the
original position being sold and a new one bought.
**Preconditions:** A `corporate_action` of type `MERGER`/`SPINOFF`/`ISIN_CHANGE` referencing a
`successor_security_id`.
**Acceptance criteria:**
- Given security A is merged into security B (shares exchanged for shares), when the corporate
  action is applied, then existing positions in A are converted to positions in B with continuous
  acquisition-date history for cost-basis purposes (not reset to the merger date).
- Given a spin-off where security A distributes shares of new security C, when applied, then a
  new position in C is created with cost basis apportioned from A per the documented methodology
  (FR-PERF/46 calculation methodology reference), and A's own remaining cost basis is reduced
  accordingly.
- Given an ISIN change with no economic event, when applied, then the position does not appear as
  sold and rebought — quantity and acquisition date are unchanged, only the identifier reference
  moves to the new `security` row (linked via `security.successor_security_id`).
**Applicable business rules:** FR-PRC-007, FR-SMD-010, FR-IMD-16, RULE-*.
**Data requirements:** `corporate_action.successor_security_id`, `security.successor_security_id`.
**Error/edge cases:** A corporate action referencing a successor security that does not yet exist
in the master (should trigger lazy creation per EPIC 12's US-12-01, not fail).
**Authorization/privacy:** N/A.
**Dependencies:** US-14-02, EPIC 15 (tax lots).
**Priority:** MUST.
**Definition of Done:** This is V-03 (merger), V-04 (spin-off with basis apportionment) and V-05
(ISIN change, no economic event) in the golden verification dataset (EPIC 27).
**Data-quality behaviour:** N/A.

---

## US-14-04 — Staleness threshold and provider failover

**Actor:** System / Workspace member
**Objective:** FR-PRC-012/014, NFR-CON-003, PR-012.
**Story:** As a workspace member, I want to be told clearly when a price I'm looking at is stale
or when the market-data provider is unreachable, rather than seeing a confident but out-of-date
number with no indication, and I want the system to keep working from stored data regardless.
**Preconditions:** A configured staleness threshold; a scenario where the provider is
unreachable.
**Acceptance criteria:**
- Given the provider is unreachable for a scheduled refresh, when a workspace views a position's
  value, then the last stored price is shown, clearly marked stale, and access to all other
  stored data is unaffected (PR-012 — a failed dependency degrades only the affected feature).
- Given a price exceeds the configured staleness threshold (default: flag stale at 24h for
  actively-traded instruments — exact threshold TBD per deployment configuration), when displayed
  anywhere, then the staleness marker is consistent across every view that uses it (NFR-UX-004).
- Given two configured provider implementations exist, when the primary fails, then the secondary
  is used automatically (FR-PRC-014/FR-DAT-40 provider abstraction).
**Applicable business rules:** FR-PRC-012/014, NFR-CON-003, PR-011/012.
**Data requirements:** None beyond price provenance/retrieval timestamps.
**Error/edge cases:** Both configured providers unreachable simultaneously — must still serve
stored data, marked stale, never a hard error blocking the whole page.
**Authorization/privacy:** N/A.
**Dependencies:** US-14-01.
**Priority:** MUST.
**Definition of Done:** Integration test simulates a provider outage and asserts stored data
remains servable with the staleness flag set.
**Data-quality behaviour:** As above — this story is itself a data-quality guarantee.
