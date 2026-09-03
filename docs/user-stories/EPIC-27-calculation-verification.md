# EPIC 27 — Calculation Verification & Golden Dataset

Section 46-47, NFR-CALC-*. This epic does not introduce new tables; it establishes the test
infrastructure that every calculation-bearing epic above (07, 09, 10, 11, 12, 13, 14, 15, 16, 17,
19) is required to plug into. Sequence it as an early spike (define the fixture format and CI
wiring) and then grow it incrementally as each of those epics lands its own cases — do not treat
it as one story done at the very end.

---

## US-27-01 — Golden verification dataset with independently verified expected results

**Actor:** Developer / QA
**Objective:** NFR-CALC-005 — a fixed reference dataset with known correct answers, run on every
change to any calculation path.
**Story:** As a developer, I want a fixed, version-controlled dataset of synthetic households
exercising every case in specification section 47 (V-01 through V-21), each with an
independently-verified expected result, run automatically on every change touching a calculation
path, so that a regression in corporate-action handling, cost basis, or currency conversion is
caught in CI before release.
**Preconditions:** None — this is the first story of the epic.
**Acceptance criteria:**
- Given the 21 cases listed in specification section 47 (forward split, reverse split with
  fractional residue, merger, spin-off, ISIN change, in-kind transfer with no cost basis, FIFO
  partial sale across three lots, USD/CHF currency attribution, dividend with withholding,
  large mid-period deposit before a fall, opening-balance reconstruction, cross-depot/cross-
  listing consolidation, card-settlement single-counting, mortgage payment split, multi-asset ETF
  decomposition, Pillar 3a in allocation, price-series holiday/gap handling, duplicate-import
  idempotency, two structurally different CSV formats, debit/credit-column CSV, template-version
  change), when the test suite runs, then each is implemented as an automated fixture with its
  expected output stored alongside it and verified independently of the implementation (not
  merely "matches what the code currently produces").
- Given any of the epics above lands a story that touches a calculation path (cost basis, TWR/MWR,
  FX conversion, categorization, corporate actions, reconciliation), when its pull request is
  opened, then the full golden-dataset suite runs in CI and must pass.
**Applicable business rules:** NFR-CALC-005, section 47.
**Data requirements:** One fixture (input transactions/prices/corporate actions + expected output)
per V-case.
**Error/edge cases:** N/A — the fixtures *are* the edge cases.
**Authorization/privacy:** N/A (test infrastructure, runs against a disposable Testcontainers
database, never production data — NFR-OPS-006).
**Dependencies:** This story should start as soon as EPIC 07/14/15 land enough to support the
first few cases (V-01/V-02 splits, V-18 duplicate import) and grow incrementally; do not block it
on every dependent epic being fully complete first.
**Priority:** MUST.
**Definition of Done:** CI pipeline fails if any golden-dataset case regresses; each case traces
back to its originating epic's story (cross-referenced in this file's table below) so ownership is
clear.
**Data-quality behaviour:** N/A.

| Case | Epic / story |
|---|---|
| V-01 Forward split | EPIC 14, US-14-02 |
| V-02 Reverse split, fractional residue | EPIC 14, US-14-02 |
| V-03 Merger | EPIC 14, US-14-03 |
| V-04 Spin-off, basis apportionment | EPIC 14, US-14-03 |
| V-05 ISIN change, no economic event | EPIC 14, US-14-03 |
| V-06 In-kind transfer, no cost basis | EPIC 15, US-15-03 |
| V-07 FIFO partial sale, three lots | EPIC 15, US-15-02 |
| V-08 USD position, CHF reporting currency | EPIC 06, US-06-02 |
| V-09 Dividend with withholding | EPIC 07 (FR-TAXR-001), EPIC 16 |
| V-10 Large mid-period deposit before a fall | EPIC 16, US-16-02/03 |
| V-11 History begins after account opened | EPIC 16, US-16-04 / EPIC 25, US-25-04 |
| V-12 Same ETF, two depots, two listings | EPIC 13, US-13-02 / EPIC 15, US-15-04 |
| V-13 Card purchase settled next month | EPIC 09, US-09-02/03 |
| V-14 Mortgage payment split | EPIC 10, US-10-02 |
| V-15 Multi-asset ETF | EPIC 12, US-12-03 / EPIC 17, US-17-02 |
| V-16 Pillar 3a in allocation | EPIC 17, US-17-03 |
| V-17 Holiday + suspension in price series | EPIC 13, US-13-03 |
| V-18 Same import file twice | EPIC 07, US-07-04 |
| V-19 Two structurally different CSV formats | EPIC 07, US-07-04 |
| V-20 Debit/credit-column CSV, trailing summary row | EPIC 07, US-07-04 |
| V-21 Institution changes export format between imports | EPIC 07, US-07-03/04 |

---

## US-27-02 — Documented calculation methodology (net worth, TWR, MWR, cost basis, FX, ...)

**Actor:** Developer
**Objective:** NFR-CALC-003, section 46.
**Story:** As a developer, I want each calculation named in specification section 46 formally
documented (inputs, edge-case treatment, rounding, a worked example) in
`docs/architecture/calculation-methodology.md`, so that "how is this actually computed" has one
authoritative answer instead of being reverse-engineered from the implementation.
**Preconditions:** None.
**Acceptance criteria:**
- Given the eleven items in section 46's table (net worth, TWR, MWR, Modified Dietz, savings
  rate, cost basis, realised gain, FX conversion, position valuation, currency attribution,
  reconciliation difference), when the document is complete, then each has its inputs, edge-case
  treatment, and a worked numeric example matching the golden-dataset fixture that exercises it.
- Given `NFR-CALC-007` (a single documented rounding policy — where rounding occurs, to how many
  places, per currency, applied at presentation rather than accumulated through intermediate
  steps), when any calculation is implemented, then it follows this policy, verified by a
  dedicated rounding-policy test suite.
**Applicable business rules:** NFR-CALC-003/007, section 46.
**Data requirements:** N/A (documentation).
**Error/edge cases:** N/A.
**Authorization/privacy:** N/A.
**Dependencies:** Written incrementally alongside US-27-01; both should be substantially complete
before EPIC 16 (performance) is considered done.
**Priority:** MUST.
**Definition of Done:** Document exists, reviewed, and referenced from each calculation-bearing
epic's stories (as several already do above).
**Data-quality behaviour:** N/A.
