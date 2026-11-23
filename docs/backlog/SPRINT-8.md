# Sprint 8 - Design-round screens on today's API, and an import that finds its account

Sprints are the GitHub label `sprint-8` plus Iteration 8 (2026-11-24 to 2026-12-07) on Project #1.
This file is the overview; the issues hold the full story text and Definition of Done. Written on
2026-11-23 from the design round of 2026-11-22 (specs S9–S15 and S7 v2 in `docs/ux/screens/`,
PR #679).

**Goal:** a member sees their loans and cards (S10), their investments (S11/S13, partial) and an
easier-to-scan account detail (S3 update). Foreign holdings get an unrealised gain. A statement
uploaded from one global Import button finds its own format **and** account, as in getquin.

**Start state:** sprints 1–7 have no open issues; nothing is carried over.

## PO decisions

- **2026-11-22:** the APIs of the design round (#691–#699) and the screens that need them (#684,
  #687, #688, #689) are in sprint 9. S11 ships partial in sprint 8: the "All depots" chart, return,
  allocation and dividends stay hidden until #694–#697.
- **2026-11-22:** no LLM ("AI") fallback for unknown import files. Instead: choose format or map
  columns (#688).
- **2026-11-23:** the server decides what a file is. The format is already detected today
  (FR-IMP-022, no CSV/PDF/CAMT choice in the app). Detecting the account was split from #365:
  - #365 is now the backend story (`POST /api/v1/imports`);
  - #702 is the app's global Import button.

  Both are in this sprint because #688 (several files, sprint 9) builds on them.
- Every UI story still renders only what the API on `main` sends ("hidden until the API sends
  it", `docs/ux/screens/README.md`).

## UX (first: the Figma review gates the UI stories)

The frames are drawn (EN + DE, light and dark). What is left is the PO's frame review.

| Issue | Task | Gates |
|---|---|---|
| #680 | Figma S9, S10 and the S1/S2/S3 updates | #685, #690 |
| #681 | Figma S11–S14 investments | #686 |
| #682 | Figma S7 import v2 | #702 (and #688 in sprint 9) |
| #683 | Figma S15 data checks | #689 (sprint 9) |

## Frontend stories

Committed (order = start order). Sizes marked * are estimates; the issue has none.

| # | Issue | Story | Spec | Size |
|---|---|---|---|---|
| 1 | #690 | US-05-09 Account detail: masked identifier, month groups, purpose lines | S3 update | S* ≈2 d |
| 2 | #685 | US-12-02 Liability detail: loan schedule, repayment split, card statement | S10 | M* ≈3 d |
| 3 | #686 | US-15-10 Investments with positions and performance card (partial) | S11, S13 | M* ≈4 d |
| 4 | #702 | US-07-46 Global Import button: the file finds format and account | S7 v2 (M32, single file) | M ≈3 d |

#702 starts when #365 has merged. Until then, the UI can be built against a mocked API.

## Backend stories

| # | Issue | Story | Needed by | Size |
|---|---|---|---|---|
| 1 | #365 | US-07-34 Upload without an account: server detects format and account | #702, #688 | M |
| 2 | #527 | US-15-06 Store the cost basis currency (foreign holdings get a gain) | #686 return figures | M |
| 3 | #589 | US-15-08 Read the cost basis from imported holdings statements | #686 | M (after #527) |

### Suggested tracks

| Days | Frontend A | Frontend B | Backend |
|---|---|---|---|
| 1-2 | #690 | #685 | #365 |
| 3-6 | #686 | #685, then #702 on a mocked API | #365, then #527 |
| 7-10 | #686 finish | #702 on the merged #365 | #527, then #589 |

The tracks are a suggestion; any story may be picked by whoever is free. Capacity is assumed as in
sprint 7: two frontend developers plus backend. The committed frontend work is about 12 days, which
leaves room to pull a sprint-9 story forward (the PO decides).

## PO answers (2026-11-23)

- **Who picks the account in #365/#702:** the server routes a file only when it can name the account
  for certain. If it cannot, the member decides: they pick from the candidates or create a new
  account.
  - "Change account" discards the batch and uploads the file again to the chosen account (no new
    endpoint).
  - #364 (move a batch in one call) is the next step after this sprint and stays unscheduled for
    now.
- **#527:** option 1 approved (store the cost basis currency, M).

## Open PO points

- **Frame review of #680–#683:** all frames are drawn (EN + DE, light and dark). Each issue has a
  "Ready for PO review" comment with its node IDs. The UI stories close only after the review.

## Risks

- **#365 slips:** #702 and then #688 (sprint 9) slip with it. Mitigation: backend starts with #365
  on day 1, and #702 builds its states against a mocked API.
- **The PO rule "nothing is imported twice":**
  - #365 must route every format (CSV, PDF, CAMT) through the existing duplicate check;
  - a double-upload integration test is part of its acceptance criteria.
- **S11 partial (#686):** the screen must look finished with the chart, return and allocation
  hidden. Check against the #681 frames.
