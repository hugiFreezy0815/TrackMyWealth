# EPIC 17 — Asset Allocation / GICS / SNB

Covers `security.gics_sub_industry_code`/`snb_*` (V7), `fallback_sector_taxonomy`,
`gics_structure_version` (V18). Section 21, 24, FR-CLS-*, FR-GICS-*, FR-SNB-*, FR-ALL-*.

---

## US-17-01 — GICS sector allocation, equity-nature instruments only

**Actor:** Workspace member
**Objective:** FR-GICS-001..006, FR-CAT (v0.1 doc)-40 — GICS classifies companies, not
instruments; a grouping dimension inside the equity sleeve.
**Story:** As a workspace member, I want to see my depot positions grouped by GICS sector,
industry group, industry and sub-industry, with the hierarchy derived by truncating the stored
8-digit code, so that sector allocation is consistent and never independently-stored fields
fall out of agreement.
**Preconditions:** Equity positions with `security.gics_sub_industry_code` populated.
**Acceptance criteria:**
- Given a position in a security with GICS sub-industry code `45102010`, when sector allocation is
  viewed, then it is grouped under Sector `45` (Information Technology), Industry Group `4510`,
  Industry `451020`, derived by truncation — not read from separately stored fields.
- Given a bond or cash position, when GICS allocation is computed, then it is never forced into a
  sector (FR-GICS-003) — it appears only on the asset-class axis (US-17-02).
- Given a position with no GICS classification at all (unlisted, custom, non-covered), when
  displayed, then it appears in an explicit, always-visible "Not classified" bucket
  (FR-GICS-009), never silently dropped from the chart.
**Applicable business rules:** FR-GICS-001..006/009, FR-CAT-30/34/40..45.
**Data requirements:** `security.gics_sub_industry_code`, `gics_structure_version`.
**Error/edge cases:** A fund/ETF holding — carries no GICS code of its own (FR-GICS-005); until
constituent look-through data is licensed (OPEN-019), it must be shown as "sector analysis
unavailable for this fund," not omitted from the chart's total or silently zeroed.
**Authorization/privacy:** Workspace-scoped.
**Dependencies:** EPIC 12, EPIC 15.
**Priority:** MUST.
**Definition of Done:** Integration test asserts correct truncation-derived grouping and the
"Not classified"/"unavailable for funds" visible-bucket behaviour.
**Data-quality behaviour:** As above — this story is largely about what happens when data is
missing, per FR-GICS-005/009/011.

---

## US-17-02 — Asset-class allocation via weighted decomposition

**Actor:** Workspace member
**Objective:** FR-CLS-001/004, RULE-027, FR-ALL-001.
**Story:** As a workspace member, I want overall allocation by economic asset class (equity,
fixed income, cash, real estate, commodities, crypto, ...) computed from each holding's weighted
`security_asset_class_weight` rows, so that a multi-asset ETF contributes correctly to more than
one class rather than being labelled a single "Fund" bucket.
**Preconditions:** Positions with `security_asset_class_weight` rows (EPIC 12's US-12-03).
**Acceptance criteria:**
- Given a workspace holding a pure-equity ETF and a 60/40 multi-asset ETF, when overall asset
  allocation is computed, then the multi-asset ETF's value is split 60% into the Equity total and
  40% into Fixed Income, summed correctly alongside the pure-equity holding.
**Applicable business rules:** FR-CLS-001/004, RULE-027, FR-ALL-001.
**Data requirements:** As above.
**Error/edge cases:** Weights that sum to less than 1 for a holding — the shortfall must appear
as an explicit "unclassified portion," not silently normalized away.
**Authorization/privacy:** Workspace-scoped.
**Dependencies:** EPIC 12 (US-12-03).
**Priority:** MUST.
**Definition of Done:** This is V-15 in the golden dataset, shared with US-12-03's story.
**Data-quality behaviour:** As above.

---

## US-17-03 — Pension holdings participate in consolidated allocation (differentiator D2)

**Actor:** Workspace member
**Objective:** FR-ALL-008, RULE-029 — "no analysed competitor does this... concentration analysis
that excludes it is simply wrong for these users."
**Story:** As a Swiss workspace member with a VIAC Pillar 3a holding equity funds, I want those
holdings included in my overall asset allocation, currency exposure and concentration analysis on
exactly the same basis as my regular depot, so that I don't unknowingly carry a large
concentrated equity position outside my own view of it.
**Preconditions:** A `PENSION` account with `holds_positions = true` (VIAC-style) and actual
`position` rows.
**Acceptance criteria:**
- Given a workspace with a taxable depot 70% in a global equity ETF and a Pillar 3a 100% in the
  same ETF, when consolidated allocation is viewed, then the Pillar 3a's holdings are summed
  together with the depot's for both the asset-class breakdown and single-issuer concentration
  detection (FR-ALL-010) — not shown as a separate, disconnected "pension" bucket.
- Given the same workspace, when the active scope of the allocation view is shown, then it
  explicitly states whether pensions are included (FR-ALL-009/FR-NAV-25 in the v0.1 doc) so the
  user is never unsure.
**Applicable business rules:** FR-ALL-008/009/010, RULE-029.
**Data requirements:** Pension-account positions must flow through the exact same allocation
computation as depot positions (DM-17 uniform interface — no special-casing by account type).
**Error/edge cases:** A PostFinance-style Pillar 3a with `holds_positions = false` (balance-only)
— contributes to net worth but not to security-level allocation, and this distinction must be
clear in the UI (see EPIC 05's US-05-04).
**Authorization/privacy:** Workspace-scoped.
**Dependencies:** EPIC 15, EPIC 26 (pension accounts).
**Priority:** MUST.
**Definition of Done:** This is V-16 in the golden verification dataset ("Pillar 3a holding
equity funds — inclusion in consolidated allocation and concentration").
**Data-quality behaviour:** N/A.

---

## US-17-04 — SNB classification stored orthogonally to GICS

**Actor:** Developer / Workspace member (advanced)
**Objective:** FR-SNB-001/003, D12/OPEN-007 — orthogonal axes, both reportable; exact taxonomy
TBD.
**Story:** As a developer, I want SNB institutional-sector and securities-category fields stored
on the security master independently of GICS, so that when the exact SNB taxonomy is confirmed
(OPEN-007), populating it requires no schema change and no GICS-derivation logic to unwind.
**Preconditions:** None (schema already supports this — `security.snb_institutional_sector_code`/
`snb_securities_category_code`, V7).
**Acceptance criteria:**
- Given a security with both GICS and SNB codes populated, when either is displayed, then neither
  is derived from the other, and updating one never mutates the other.
- Given OPEN-007 is resolved and the real SNB code list is available, when it is imported as
  reference data (EPIC 32), then existing `security` rows are updated via the standard
  reference-package import path, not a bespoke migration.
**Applicable business rules:** FR-SNB-001/002/003.
**Data requirements:** Free-text columns pending the taxonomy decision (documented in
`database-schema.md` section 6 as an explicit deferral).
**Error/edge cases:** None — this is largely a "don't couple these two things" guardrail story.
**Authorization/privacy:** N/A.
**Dependencies:** OPEN-007 resolution (external to engineering — flag as blocked until product
decision lands).
**Priority:** COULD (blocked on OPEN-007; do not schedule until the taxonomy is confirmed).
**Definition of Done:** Architecture test / code review checklist item: no code path computes
`snb_*` fields from `gics_*` fields or vice versa.
**Data-quality behaviour:** N/A.
