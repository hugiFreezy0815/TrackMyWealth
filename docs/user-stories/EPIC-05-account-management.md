# EPIC 05 — Account Management

Covers `account` and its extension tables (V4, V5). Section 10, FR-ACC-*, G1-G6.

---

## US-05-01 — Create an account of any supported type under a container

**Actor:** Workspace member
**Objective:** FR-ACC-001/002, G1/G3.
**Story:** As a workspace member, I want to create an account of any supported type (cash,
savings, securities, managed mandate, pension, vested benefits, credit card, mortgage, loan,
crypto, custom asset) under a chosen institution, so that every real-world product I hold is
representable.
**Preconditions:** A `financial_institution` exists.
**Acceptance criteria:**
- Given a chosen `account_type`, when the account is created, then the correct capability
  defaults are applied (e.g. `SECURITIES` → `holds_positions = true`; `CASH` →
  `has_transactions = true, holds_positions = false`) and, where the type has one, the matching
  extension table row is created in the same transaction (e.g. `CREDIT_CARD` also creates
  `account_credit_card`).
- Given `account_type = 'CREDIT_CARD'`, when the service attempts to insert into
  `account_mortgage` for the same account (a defensive/negative test), then the database rejects
  it via `trg_extension_type_guard` regardless of what the application layer does.
- Given `nature` is never set directly by application code, when any account is created, then
  `nature` is correctly `ASSET` or `LIABILITY` per the `GENERATED ALWAYS AS` column (DB-12).
**Applicable business rules:** FR-ACC-001/002/010/012/013, G1-G4, DB-09..12.
**Data requirements:** `name`, `account_type`, `native_currency` required;
`financial_institution_id` must belong to the caller's workspace.
**Error/edge cases:** Attempting to create an account with no `financial_institution_id` must be
rejected (C2 — every account has exactly one container; use the workspace's Personal Assets
container as the default when the user has not chosen one, per FR-INS-011).
**Authorization/privacy:** Workspace-scoped write.
**Dependencies:** EPIC 04.
**Priority:** MUST.
**Definition of Done:** Parameterised integration test creates one account of every
`account_type` and asserts the correct extension row (or absence of one) and capability flags.
**Data-quality behaviour:** N/A.

---

## US-05-02 — Account type is immutable after creation

**Actor:** Workspace member
**Objective:** FR-ACC-005/G5.
**Story:** As a workspace member, I want the system to prevent me from changing an account's type
after creation, and instead guide me to close-and-recreate with an explicit migration, so that
historical figures computed under the old type are never silently reinterpreted.
**Preconditions:** An existing account.
**Acceptance criteria:**
- Given an existing `CASH` account, when an update request attempts to change `account_type` to
  `SAVINGS`, then the request is rejected with a clear error referencing FR-ACC-005 (the API
  surfaces the DB trigger's rejection as a structured error, not a raw SQL exception).
- Given a genuine need to convert (e.g. a mismodelled account), when the user follows the
  close-and-recreate flow, then the old account is archived (`status = 'ARCHIVED'`), a new account
  is created with the correct type, and the UI explicitly explains that historical data was not
  migrated automatically (NFR-REL-003 — no silent relocation of user-visible balances).
**Applicable business rules:** FR-ACC-005, G5, DB-11.
**Data requirements:** None.
**Error/edge cases:** None beyond the above.
**Authorization/privacy:** Workspace-scoped write.
**Dependencies:** US-05-01.
**Priority:** MUST.
**Definition of Done:** Integration test attempts the forbidden update and asserts a structured
`409`/`422` (not a `500`) surfacing the DB constraint violation cleanly.
**Data-quality behaviour:** N/A.

---

## US-05-03 — Archive and restore an account without losing history

**Actor:** Workspace member
**Objective:** FR-ACC-003/006, FR-LIF-005, FR-STA-001.
**Story:** As a workspace member, I want to archive a closed account, so that it disappears from
current totals and selection lists but remains fully present in every historical report covering
the period it was active.
**Preconditions:** An existing `ACTIVE` account.
**Acceptance criteria:**
- Given an active account with transaction history, when it is archived, then `status` becomes
  `ARCHIVED`, `closed_at` is set, it is excluded from current net-worth/allocation totals, and it
  still appears in a historical net-worth chart covering a date range before the closure.
- Given an archived account, when it is restored within 30 days (FR-LIF-006), then `status`
  returns to `ACTIVE` and it reappears in current totals.
- Given an archived account, when 30 days have passed, then the restore action is no longer
  offered in the UI (the row itself remains in the data — only user-initiated restore is
  time-boxed).
**Applicable business rules:** FR-ACC-003/006, FR-LIF-005/006, FR-STA-001.
**Data requirements:** None.
**Error/edge cases:** Archiving an account with an open reconciliation difference — allowed, but
the difference stays visible in history rather than being silently dropped.
**Authorization/privacy:** Workspace-scoped write.
**Dependencies:** US-05-01.
**Priority:** MUST.
**Definition of Done:** Integration test archives, verifies exclusion from current totals and
inclusion in historical ones, then restores within the window.
**Data-quality behaviour:** N/A.

---

## US-05-04 — Capability flags drive UI/behaviour, never account_type branching

**Actor:** Developer (architectural constraint story)
**Objective:** FR-ACC-010/011/012, DM-20/21 — the proof case is a VIAC Pillar 3a (holds
positions) vs. a PostFinance Pillar 3a (interest-bearing only) — same `account_type`, different
capability.
**Story:** As a developer, I want every consumer of account data (consolidation, allocation, net
worth, navigation) to branch only on declared capability flags, never on `account_type`, so that
a VIAC-style position-holding pension account and a PostFinance-style balance-only pension account
are both handled correctly by the same code path.
**Preconditions:** Two `PENSION` accounts exist, one with `holds_positions = true` and one with
`holds_positions = false`.
**Acceptance criteria:**
- Given the position-holding pension account, when consolidated asset allocation is computed,
  then its positions are included exactly like a depot's (FR-ALL-008).
- Given the balance-only pension account, when the same allocation view is computed, then its
  balance is included as a value but contributes no security-level allocation breakdown, and no
  error occurs from the absence of positions.
- This is verified by an architecture test (e.g. ArchUnit "no method in the consolidation package
  references `Account.getAccountType()` in a conditional") in addition to the functional test —
  this is the concrete mechanism behind FR-NAV-17/FR-INS-012's extensibility verification.
**Applicable business rules:** FR-ACC-010/011/012, DM-17/20/21, FR-ALL-008.
**Data requirements:** None beyond the two pension accounts.
**Error/edge cases:** A capability flag is set inconsistently with the account's actual
configuration (e.g. `holds_positions = true` on an account with no `account_securities`-shaped
extension) — for `PENSION`/`SECURITIES` there is no extension-table mismatch to guard here since
capability is independent of the extension table; document this as a data-entry validation the
service layer must perform (positions may only be recorded for an account with
`holds_positions = true`).
**Authorization/privacy:** N/A.
**Dependencies:** US-05-01, EPIC 15 (positions), EPIC 17 (allocation).
**Priority:** MUST.
**Definition of Done:** Both the functional test and the architecture test pass in CI.
**Data-quality behaviour:** N/A.

---

## US-05-05 — Custom asset with dated manual valuations

**Actor:** Workspace member
**Objective:** FR-NW-003/004, section 10 custom-asset row (real estate, vehicles, precious
metals, collectibles).
**Story:** As a workspace member, I want to record a custom asset (e.g. a car or jewellery) with
a manually entered, dated valuation, so that it contributes to net worth even though no
institution or market price exists for it.
**Preconditions:** A `CUSTOM_ASSET` account exists under an institution (typically the workspace's
Personal Assets container).
**Acceptance criteria:**
- Given a new custom asset, when a valuation is entered for today's date, then a
  `custom_asset_valuation` row is created and the account's current value in net worth reflects
  it immediately.
- Given a later valuation is entered for a more recent date, when net worth history is viewed,
  then each period uses the valuation that was current for that period, not a straight-line
  interpolation between the two (PR-011 — no silent interpolation).
**Applicable business rules:** FR-NW-003/004, RULE-028.
**Data requirements:** `valuation_date`, `value`, `currency` required.
**Error/edge cases:** No valuation has ever been entered — the account's value must be shown as
unknown/estimated rather than zero (PR-011), never silently treated as €0 net worth.
**Authorization/privacy:** Workspace-scoped write.
**Dependencies:** US-05-01.
**Priority:** MUST.
**Definition of Done:** Integration test enters two valuations at different dates and asserts
correct historical attribution.
**Data-quality behaviour:** An account with no valuation at all must be flagged in the UI as
"value unknown," and excluded (with a visible note, not silently) from any total that requires a
numeric value.
