# EPIC 25 — Snapshots & Reconciliation

"The single most important addition to the baseline" per the requirements document. Covers
`account_snapshot`, `snapshot_holding`, `reconciliation_result` (V11). Section 12.1, FR-REC-*,
RULE-025.

---

## US-25-01 — Manual snapshot entry gives correctness before any connector exists

**Actor:** Workspace member
**Objective:** FR-REC-006 — "the mechanism by which a fully manual user still gets correctness."
**Story:** As a workspace member with no automated connector for my bank, I want to type in the
balance shown on my paper or PDF statement as a snapshot, so that I can verify my ledger-derived
balance is correct even without any integration.
**Preconditions:** An account exists.
**Acceptance criteria:**
- Given a workspace types CHF 12,345.67 as today's balance for their PostFinance cash account,
  when saved, then an `account_snapshot` row is created with `source = 'MANUAL'`.
- Given the account also has holdings (a depot), when a snapshot is entered with per-security
  quantities, then `snapshot_holding` rows are created alongside the balance snapshot.
**Applicable business rules:** FR-REC-006, RULE-025.
**Data requirements:** `snapshot_date`, `balance`, `currency`; optionally per-security
`quantity`/`reported_cost_basis`.
**Error/edge cases:** A second snapshot for the same account/date/source — rejected by the
`UNIQUE(account_id, snapshot_date, source)` constraint; the UI should offer "update today's
snapshot" instead of a silent failure.
**Authorization/privacy:** Workspace-scoped write.
**Dependencies:** EPIC 05.
**Priority:** MUST.
**Definition of Done:** Integration test enters a manual snapshot with holdings.
**Data-quality behaviour:** N/A (this story is the correctness mechanism itself).

---

## US-25-02 — Reconciliation engine compares ledger-derived state against the snapshot

**Actor:** System
**Objective:** FR-REC-002/003 — the core of the whole epic.
**Story:** As the system, I want to compare the ledger-derived balance/holdings for an account
against its most recent snapshot, and report any difference with a probable cause, so that
divergence between "what we computed" and "what the institution actually reports" is caught
immediately rather than discovered by the user months later.
**Preconditions:** An account with both transaction history and at least one snapshot.
**Acceptance criteria:**
- Given the ledger-derived balance is CHF 12,300.00 and the snapshot reports CHF 12,345.67, when
  the reconciliation job runs, then a `reconciliation_result` row is created with
  `difference_amount = 45.67` and `status = 'OPEN'`.
- Given the difference pattern matches a known signature (e.g. a fee amount exactly matching a
  round CHF 45.67 fee not present in the ledger), when classified, then `probable_cause` is set
  accordingly (`UNRECORDED_FEE`) rather than left as `UNKNOWN` (FR-REC-003) — best-effort, not
  guaranteed.
- Given the ledger and snapshot agree exactly, when the job runs, then no `OPEN`
  `reconciliation_result` row is created (or an existing one transitions to `RESOLVED`), and the
  account's reconciliation status shows "reconciled as of [date]" (FR-REC-005).
**Applicable business rules:** FR-REC-002/003/005, RULE-025.
**Data requirements:** Both `transaction`-derived state and `account_snapshot`/`snapshot_holding`.
**Error/edge cases:** Multiple snapshots exist for the same account at different dates — the
reconciliation job compares against the most recent one relevant to the comparison date, and an
older `OPEN` difference whose snapshot has since been superseded transitions to `SUPERSEDED`
(FR-STA-003), not left dangling as `OPEN`.
**Authorization/privacy:** Workspace-scoped.
**Dependencies:** US-25-01, EPIC 07, EPIC 15.
**Priority:** MUST.
**Definition of Done:** Integration test with a deliberately introduced CHF 45.67 discrepancy,
asserting the `reconciliation_result` row and its classification.
**Data-quality behaviour:** This story *produces* the data-quality signal every other epic's
"data-quality behaviour" section consumes (FR-CON-007, PR-011).

---

## US-25-03 — Guided resolution of a reconciliation difference

**Actor:** Workspace member
**Objective:** FR-REC-004 — explicit resolution paths, adjustments always visible.
**Story:** As a workspace member, I want to resolve an open reconciliation difference by either
adding the missing transaction, accepting the provider's figure as a visible adjusting entry, or
dismissing it with a reason, so that every account either agrees with its provider or has a
documented, visible reason why not.
**Preconditions:** An `OPEN` `reconciliation_result`.
**Acceptance criteria:**
- Given the user chooses "add the missing transaction," when they enter it, then the ledger is
  updated (as a normal new transaction per EPIC 07), the reconciliation job re-runs, and the
  difference resolves to `RESOLVED` if the numbers now agree.
- Given the user chooses "accept the provider's figure," when confirmed, then a visible adjusting
  `transaction` is created (never a silent correction — FR-REC-004 is explicit on this),
  `reconciliation_result.status` becomes `ACCEPTED`, and `resolution_transaction_id` points at
  the new entry.
- Given the user chooses "dismiss," when confirmed with a reason, then `status` becomes
  `DISMISSED` with `resolution_note` set, and it can be reopened later (FR-STA-003).
**Applicable business rules:** FR-REC-004, FR-STA-003.
**Data requirements:** `resolution_note` required for dismiss/accept.
**Error/edge cases:** Resolving a difference whose underlying snapshot has since been superseded
by a newer one — the resolution flow must refresh against the current snapshot before allowing
confirmation, not resolve against stale data.
**Authorization/privacy:** Workspace-scoped write.
**Dependencies:** US-25-02.
**Priority:** MUST.
**Definition of Done:** Integration test for all three resolution paths.
**Data-quality behaviour:** N/A.

---

## US-25-04 — Opening-balance and opening-holdings reconstruction

**Actor:** Workspace member
**Objective:** FR-REC-007 — without this, every account whose history predates the import shows
wrong returns from the first day.
**Story:** As a workspace member whose imported transaction history starts later than the account
itself was opened, I want to record a dated opening balance (and opening holdings with cost
basis, for investment accounts), so that performance and net-worth figures are correct from that
opening point rather than silently starting from zero on the date I happened to start importing.
**Preconditions:** An account whose earliest transaction is later than a known real opening date.
**Acceptance criteria:**
- Given a cash account opened two years ago but with transaction history only starting six months
  ago, when the user records an opening balance dated two years ago, then net-worth history
  before six months ago uses that opening balance as its starting point rather than being blank
  or zero.
- Given a securities account with the same gap, when the user records opening holdings (security,
  quantity, cost basis) as of the opening date, then `tax_lot` and `daily_valuation` (EPIC 15/16)
  can be built for the full period, with the reconstructed portion marked `is_estimated = true`
  where cost basis was not exactly known.
**Applicable business rules:** FR-REC-007, PR-011.
**Data requirements:** `account_snapshot.is_opening_balance = true`, dated appropriately;
`snapshot_holding` rows for investment accounts.
**Error/edge cases:** An opening balance recorded *after* the first real transaction already in
the ledger — the service layer must reject or clearly flag the inconsistency rather than silently
double-count the opening period.
**Authorization/privacy:** Workspace-scoped write.
**Dependencies:** US-25-01, EPIC 16 (US-16-04 depends on this).
**Priority:** MUST.
**Definition of Done:** This is V-11 in the golden verification dataset, shared with EPIC 16's
US-16-04.
**Data-quality behaviour:** As above.
