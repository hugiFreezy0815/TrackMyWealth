# EPIC 15 — Investment Portfolios & Holdings

Covers `account_snapshot`/`snapshot_holding` (V11, shared with EPIC 25), `tax_lot`/
`tax_lot_disposal` (V11), `position`/`position_history` (V12). Section 18, FR-DEP-*.

---

## US-15-01 — Positions are derived from the ledger and rebuildable

**Actor:** System
**Objective:** FR-DEP-001/002, FR-DAT-008 — position is fully derived, never a source of truth.
**Story:** As the system, I want each account's current positions to be computed from its
transaction history (and reconciled against snapshots), stored in `position` for fast reads, and
fully rebuildable on demand, so that a bug in the derivation logic can always be fixed by
recomputation rather than requiring a data-repair script.
**Preconditions:** A `SECURITIES` account with `BUY`/`SELL` transactions.
**Acceptance criteria:**
- Given a sequence of buys and sells, when the position-rebuild job runs for the account, then
  `position` reflects the correct net quantity and `tax_lot` reflects the correct remaining lots
  under the account's configured cost-basis method.
- Given the rebuild job is run twice in a row with no new transactions, when compared, then the
  result is byte-for-byte identical (NFR-CALC-008 — recomputation is idempotent).
- Given a backdated transaction is inserted (a correction dated in the past), when the rebuild job
  runs, then only derived data from that date forward is invalidated and rebuilt (FR-JOB-006), not
  the account's entire history.
**Applicable business rules:** FR-DEP-001/002, FR-DAT-008, NFR-CALC-008, FR-JOB-006.
**Data requirements:** None beyond the transaction ledger.
**Error/edge cases:** A `SELL` with no matching prior `BUY` (would produce a negative position) —
must raise a reconciliation difference (FR-DEP-007/FR-REC-002), never silently produce a negative
quantity.
**Authorization/privacy:** Workspace-scoped (RLS on `position`).
**Dependencies:** EPIC 07, EPIC 25.
**Priority:** MUST.
**Definition of Done:** Integration test for idempotent rebuild and for the backdated-edit
targeted-recomputation behaviour.
**Data-quality behaviour:** A position with an estimated cost basis (e.g. from a transfer-in with
no reported basis) must have `is_estimated = true` (PR-011).

---

## US-15-02 — Cost-basis method is configurable per account and applied consistently

**Actor:** Workspace member
**Objective:** FR-DEP-004/005, FR-DAT (v0.1 doc)-01, section 46.
**Story:** As a workspace member, I want to choose FIFO, LIFO or average cost per securities
account (defaulting to FIFO), and decide whether fees are included in cost basis, so that realised
gains match how I actually think about the account and can be reconciled against broker
statements that may report differently.
**Preconditions:** A `SECURITIES` account (`account_securities` extension row, V5).
**Acceptance criteria:**
- Given three acquisition lots at different prices and a partial sale, when realised gain is
  computed under FIFO, then the earliest lot(s) are consumed first, and the result matches the
  documented worked example in the calculation methodology (EPIC 27).
- Given the same scenario recomputed under average cost after the workspace changes the setting,
  when displayed, then the change is applied prospectively/retrospectively as documented (the
  service layer must fully rebuild `tax_lot`/`tax_lot_disposal` for the account, since cost basis
  is derived, never stored as ground truth — FR-DAT-008).
- Given `fees_included_in_cost_basis = true`, when a lot's unit cost is computed, then the
  transaction's `fee_amount` is folded in; given `false`, fees are tracked separately.
**Applicable business rules:** FR-DEP-004/005, DM-04.
**Data requirements:** `account_securities.default_cost_basis_method`,
`fees_included_in_cost_basis`.
**Error/edge cases:** Changing the method on an account with a long history — must be an
asynchronous job (large recomputation), not a blocking request.
**Authorization/privacy:** Workspace-scoped.
**Dependencies:** US-15-01.
**Priority:** MUST.
**Definition of Done:** This is V-07 in the golden verification dataset (EPIC 27 — "partial sale
under FIFO with three acquisition lots").
**Data-quality behaviour:** N/A.

---

## US-15-03 — Custodian transfer with no reported cost basis

**Actor:** Workspace member
**Objective:** FR-DEP-006 (v0.1 doc: FR-POR-06), FR-REC-008.
**Story:** As a workspace member who transferred a position between brokers, I want to enter a
cost basis manually when the receiving broker reports none, with the historical market price at
transfer date offered as a starting suggestion, so that my realised-gain figures are not simply
wrong or blank for that position.
**Preconditions:** A `TRANSFER_IN` transaction/snapshot holding with no cost basis reported.
**Acceptance criteria:**
- Given a transferred-in position with `reported_cost_basis IS NULL`, when the user opens the
  resolution flow, then the historical closing price on the transfer date is offered as a
  proposed cost basis.
- Given the user accepts or edits the proposal, when saved, then a `tax_lot` is created with
  `is_cost_basis_estimated = true` until the user explicitly confirms it as final (at which point
  it may be marked confirmed, still visibly distinct from a broker-reported basis).
**Applicable business rules:** FR-DEP-006, FR-REC-008.
**Data requirements:** Historical price for the transfer date (EPIC 14 backfill).
**Error/edge cases:** No historical price is available for that date either (e.g. delisted
security) — the user must be able to enter cost basis with zero suggestion, still marked
estimated.
**Authorization/privacy:** Workspace-scoped.
**Dependencies:** EPIC 14, EPIC 25.
**Priority:** MUST.
**Definition of Done:** This is V-06 in the golden verification dataset ("in-kind transfer between
custodians, no cost basis reported").
**Data-quality behaviour:** As above — this story exists specifically to make an estimate visible
rather than absent.

---

## US-15-04 — Cross-depot consolidation of the same security

**Actor:** Workspace member
**Objective:** FR-DEP-003.
**Story:** As a workspace member holding the same ETF in two different depots, I want to see one
consolidated exposure figure while still being able to drill into each depot's own position, so
that concentration and allocation analysis is correct across my whole workspace.
**Preconditions:** Two `position` rows for the same `security_id` in different accounts.
**Acceptance criteria:**
- Given 100 units in Depot A and 50 units in Depot B of the same security, when consolidated
  holdings are viewed, then total exposure shows 150 units (valued using each position's correct
  listing per US-13-02), with drill-down to the two contributing 100/50 positions.
**Applicable business rules:** FR-DEP-003.
**Data requirements:** None beyond the two `position` rows.
**Error/edge cases:** The two positions were acquired on different listings (different trading
currencies) — the consolidated value must correctly sum each in its own currency before
converting, not average two different currency-denominated unit prices together.
**Authorization/privacy:** Workspace-scoped.
**Dependencies:** US-15-01, US-13-02.
**Priority:** MUST.
**Definition of Done:** This is V-12 in the golden verification dataset, shared with US-13-02.
**Data-quality behaviour:** N/A.
