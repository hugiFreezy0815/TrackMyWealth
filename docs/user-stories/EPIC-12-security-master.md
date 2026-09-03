# EPIC 12 — Security Master & Issuers

Covers `security`, `issuer`, `security_identifier`, `security_asset_class_weight`,
`security_field_provenance`, `household_security_override` (V7). Section 19, FR-SMD-*, FR-IMD-*,
DM-24/25.

---

## US-12-01 — Lazy security-master creation on first reference

**Actor:** System (invoked from transaction entry / import)
**Objective:** FR-SMD-001/007, DM-25/28 — no bulk universe load; a master record is created only
on first reference.
**Story:** As the system, I want a `security` master record to be created only when a household
first references it (via a transaction, position, or watchlist entry), and shared globally
thereafter, so that ten households holding the same ETF create one record, not ten.
**Preconditions:** A household enters a `BUY` transaction for a security not yet in the master.
**Acceptance criteria:**
- Given a `BUY` transaction for ISIN `IE00B4L5Y983` not currently in `security`, when it is saved,
  then a new `security` row is created (looked up from an external reference-data provider or
  entered manually if none is configured — NFR-LIC-004/008 manual-price mode), and the
  transaction references it.
- Given a second household later buys the same ISIN, when their transaction is saved, then no new
  `security` row is created — the existing one is referenced and its shared price series
  (`listing`/`price`, EPIC 13/14) is now also relevant to them.
- Given a search at add-time queries an external universe, when the user has not yet confirmed a
  selection, then no `security` row is written (FR-SMD-007 — lookup without persistence).
**Applicable business rules:** FR-SMD-001/004/007, DM-25/28.
**Data requirements:** ISIN or a synthetic key (`security.isin IS NOT NULL OR synthetic_key IS
NOT NULL`, enforced by a `CHECK` constraint in V7).
**Error/edge cases:** No external provider configured (manual-only deployment, NFR-CON-004) — the
user must be able to create a minimal master record by hand (name, currency, asset class) and
have it work identically for everything downstream.
**Authorization/privacy:** `security` carries no `household_id` — it is global, shared reference
data (NFR-LIC-007); no RLS applies to it (documented in `database-schema.md` section 4).
**Dependencies:** EPIC 07 (transaction entry triggers this).
**Priority:** MUST.
**Definition of Done:** Integration test: two different households (two different
`app.current_household_id` RLS contexts) each buy the same ISIN and the test asserts exactly one
`security` row exists.
**Data-quality behaviour:** A security created with incomplete master data (e.g. missing GICS)
must expose that gap via `security_field_provenance`/a completeness indicator (FR-SMD-011),
rather than silently omitting it from analyses that need it.

---

## US-12-02 — Identifier resolution across ISIN, Valor, WKN, ticker

**Actor:** System
**Objective:** FR-SMD-008 — the same security arriving under different identifiers from different
institutions must resolve to one master record; ISIN authoritative, fallback requires
confirmation.
**Story:** As the system, I want a security reported by one broker with a Valor number and by
another with only a ticker to resolve to the same `security` record when they are in fact the
same instrument, so that positions don't silently fragment into duplicate securities.
**Preconditions:** A `security` row exists with `isin` set and a `security_identifier` row for
`VALOR`.
**Acceptance criteria:**
- Given an import row carrying only a Valor number that matches an existing `security_identifier`,
  when resolved, then it links to the existing `security` automatically (ISIN-equivalent
  confidence — Valor/WKN are treated as authoritative once mapped).
- Given an import row carrying only a ticker with no ISIN/Valor/WKN, when resolved against
  multiple candidate securities sharing that ticker on different exchanges, then the system does
  **not** silently pick one — it requires explicit user confirmation (FR-SMD-008: "a fallback
  match on ticker plus exchange shall require user confirmation and shall never be applied
  silently").
**Applicable business rules:** FR-SMD-008.
**Data requirements:** `security_identifier` rows populated as each new identifier type is
encountered for a security.
**Error/edge cases:** Two different real-world securities that coincidentally share a ticker on
different markets — this is exactly the case the confirmation step protects against.
**Authorization/privacy:** N/A (global data).
**Dependencies:** US-12-01.
**Priority:** MUST.
**Definition of Done:** Integration test for the Valor-match-succeeds and
ticker-ambiguity-requires-confirmation paths.
**Data-quality behaviour:** N/A.

---

## US-12-03 — A fund is a weighted set of asset classes, never a single class

**Actor:** Household member / System
**Objective:** FR-CLS-004, DM-24, RULE-027 — schema-level requirement even before look-through
data is licensed.
**Story:** As a household member holding a multi-asset or bond ETF, I want its allocation to be
expressed as a weighted breakdown across asset classes rather than a single label, so that my
overall equity/fixed-income allocation is actually correct.
**Preconditions:** A `security` representing a multi-asset fund (e.g. 60% equity / 40% fixed
income).
**Acceptance criteria:**
- Given the fund's declared allocation (60/40), when `security_asset_class_weight` rows are
  populated, then two rows exist (`EQUITY` weight `0.60`, `FIXED_INCOME` weight `0.40`), both
  `is_estimated = true` if sourced from the fund's own declared allocation rather than licensed
  look-through data (FR-CLS-005).
- Given no allocation data is available at all for a newly created security, when consolidated
  allocation is computed, then that security's holding is excluded from the asset-class breakdown
  with a visible "allocation unavailable" flag, never silently defaulted to 100% one class.
**Applicable business rules:** FR-CLS-001/004/005, DM-24, RULE-027.
**Data requirements:** `security_asset_class_weight` weights per security, summing to ≤ 1 (should
sum to exactly 1 when complete — validated at the service layer, not a DB constraint, since
partial/estimated data is a normal transitional state).
**Error/edge cases:** Weights that don't sum to 1 (incomplete look-through) — must be visibly
flagged, not silently normalized to 100% and presented as complete.
**Authorization/privacy:** N/A.
**Dependencies:** US-12-01.
**Priority:** MUST.
**Definition of Done:** This is V-15 in the golden verification dataset (EPIC 27) — a multi-asset
ETF holding equities and bonds, asserting weighted decomposition rather than single-class
assignment.
**Data-quality behaviour:** As above.

---

## US-12-04 — User override of a security-master field never silently overwritten

**Actor:** Household member
**Objective:** FR-SMD-013, RULE-031 — a user-supplied value takes precedence and is never
silently overwritten by a later refresh.
**Story:** As a household member, I want to correct a security's classification for my own view
(e.g. a custom/illiquid holding with no market data) without affecting other households' view of
the same shared security record, so that my correction sticks and never leaks to anyone else.
**Preconditions:** A `security` row exists, potentially shared with other households.
**Acceptance criteria:**
- Given the household overrides "asset class" for a security, when the override is saved, then a
  `household_security_override` row is created (household-scoped, per V7), not a mutation of the
  shared `security` row.
- Given another household views the same security, when they read its asset class, then they see
  the original shared value, not the first household's override — no cross-household leakage
  (DM-25's explicit tenancy hazard).
- Given a scheduled refresh job later updates the shared `security` row's field from an external
  provider, when it runs, then it does not touch or need to know about any household's overrides
  at all (they live in a separate table read as an overlay at query time).
**Applicable business rules:** FR-SMD-013, RULE-031, DM-25.
**Data requirements:** `household_security_override(household_id, security_id, field_name)`.
**Error/edge cases:** Two different households override the same field differently for the same
shared security — both must be independently respected; this is exactly what the per-household
override table is for.
**Authorization/privacy:** Household-scoped read/write on the override table itself (should be
added to the RLS-protected table list — confirm it is in `V20`'s `household_scoped_tables` array).
**Dependencies:** US-12-01.
**Priority:** MUST.
**Definition of Done:** Integration test: two households override the same shared security's
field differently and each sees only their own override.
**Data-quality behaviour:** N/A.
