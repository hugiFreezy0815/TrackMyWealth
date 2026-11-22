# S15 Data checks: find and fix what makes a figure unreliable

**Figma:** M35 Data checks (phone) · M36 Units check (phone) · W18 Data checks (web panel). To be
drawn; DE mirrors. M15 Reconcile (existing) is the fix screen for balance differences.
**Requirements:** NFR-UX-003/004 (explainability, value status), FR-REC (reconciliation),
EPIC 15 (cost basis), US-07-17 (duplicates), FR-API-010.
**Rules for every screen:** see [README](README.md#rules-for-every-screen).
**Inspiration:** getquin's "Is everything imported correctly? Yes / No" card, the "We found an
invalid transaction" banner and its "position has negative units" message (screenshots of
2026-11-22). getquin reports problems but gives no way to fix them. S15 gives every problem one
cause and one fix.

## Purpose

Value status badges say **that** a figure is uncertain. S15 says **why**, and leads to the
fix. It is one inbox for the whole workspace, so the member does not have to visit every
account.

## Entry points

- S1 data status banner (field 5): it now counts **all** open checks, not only unreconciled
  accounts. "{n} checks need your attention · Review".
- Web header: a "Data checks" icon button with a count badge (hidden at 0).
- Any `ValueStatusBadge` explanation sheet: "Fix" when a check belongs to that value.
- Import done (S7 v2), the S11 units check card, S12 "Add cost basis".
- Route `/checks`, `/checks?account={id}`, `/checks?kind={kind}`.

## Layout (M35)

```
‹ Overview
Data checks
3 checks affect your net worth
( All 5 ) ( Balances 1 ) ( Investments 3 ) ( Transactions 1 )
┌ ⚠ Girokonto differs from its statement                ┐
│   DKB · EUR 42.10 more in your ledger than on the     │
│   statement of 30 Oct. Likely cause: a booking         │
│   imported twice.                                     │
│   Affects: net worth, Cash & savings      [Reconcile ›]│
├───────────────────────────────────────────────────────┤
│ ⓘ 2 positions have no cost basis                      │
│   Swissquote depot · their gain cannot be shown        │
│                                      [Add cost basis ›]│
├───────────────────────────────────────────────────────┤
│ ⓘ 1 security could not be identified                  │
│   Yuh · "Unknown fund (CH)" from the 3 Oct statement   │
│                                       [Find security ›]│
├───────────────────────────────────────────────────────┤
│ ⓘ 3 transactions need a category                      │
│   Privatkonto                              [Review ›] │
└───────────────────────────────────────────────────────┘
Done recently  ▸
```

`IssueCard`: severity icon + title (what is wrong) · body (where, how much, likely cause) ·
"Affects:" (which totals carry a badge because of it) · one primary fix action. No colour-only
meaning.

Severity:
- **Affects a total** (⚠ warning): the issue changes net worth or a section total (unreconciled
  balance, unknown value, negative units).
- **Limits detail** (ⓘ info): the totals are right, but a detail is missing (no cost basis,
  unidentified security, uncategorised transactions).

## Check kinds and their sources

| Kind | Title (EN draft) | Source today | Fix action |
|---|---|---|---|
| `BALANCE_DIFFERS` | "{account} differs from its statement" | `GET /api/v1/accounts` → items with `valueStatus = UNRECONCILED`; the details come from `GET /api/v1/accounts/{id}/reconciliations?size=1` → `differenceAmount`, `asOf`, `probableCause` | M15 Reconcile (accept / dismiss / reopen endpoints exist) |
| `VALUE_UNKNOWN` | "The value of {account} is unknown" | `valueStatus = UNKNOWN` | add a valuation / opening balance ([S6](add-account.md) step 2) or import a statement |
| `VALUE_STALE` | "{account}: newest statement is from {date}" | `valueStatus = STALE`, `valueSourceDate` | import a statement ([S7](import-statement.md)) |
| `COST_BASIS_MISSING` | "{n} positions have no cost basis" | `AccountRow.missingCostBasisCount` per depot | per position: enter a cost basis. This works today by replacing the newest holdings snapshot with `reportedCostBasis` set on that holding (`PUT /api/v1/accounts/{accountId}/snapshots/{snapshotId}` + `If-Match`, the other holdings unchanged). **G15-2** asks for a single-position endpoint so the app need not resend the whole snapshot. #589 also reads it from statements |
| `COST_BASIS_FOREIGN` | "{n} positions have a cost basis in another currency" | `foreignCurrencyCostBasisCount` | info only, with the explanation; #527 resolves it |
| `SECURITY_UNRESOLVED` | "{n} securities could not be identified" | open holdings imports with `holdings.unresolvedCount > 0` | "Find security" (`POST /api/v1/securities` by ISIN), in the S7 v2 review |
| `SECURITY_INCOMPLETE` | "{security}: asset class and country missing" | `SecurityResponse.completeness` | info; explains "Unknown" in the allocation |
| `UNCATEGORISED` | "{n} transactions need a category" | per account `GET …/transactions?uncategorized=true&size=1` → `totalElements` | S3 with the Uncategorised filter |
| `NEGATIVE_UNITS` | "{security}: more units sold than bought" | **G15-1** (no source) | open the sell transaction; "Import an older statement" or "Add the missing buy" (M18) |
| `SELL_WITHOUT_BUY`, `STATEMENT_GAP`, `DUPLICATE_SUSPECTED` | … | **G15-1** | per kind |

**Gap G15-1 is the main one:** a workspace endpoint `GET /api/v1/data-checks` that returns the
open checks (kind, severity, affected account / security / transaction IDs, amounts, the totals
they affect, `probableCause`). Until it ships, S15 builds the list from the sources above.
That is a **count of rows**, never a money figure, so it does not break FR-API-010. The kinds
marked G15-1 stay hidden.

Loading cost without G15-1: the account list (1 call) and, per unreconciled account, its newest
reconciliation (at most 4 in parallel). Uncategorised counts reuse S3's request. S15 loads lazily:
the S1 banner count uses only the account list.

## Units check (M36): "Do the units match your broker?"

The card appears on [S11](investments.md) after a holdings statement was imported
(`positionsAsOf` newer than the member's last answer), and on S3 of that depot.

```
Check your units · Swissquote depot · 31 Oct 2026
Compare with your broker's app or statement.
┌ Vanguard FTSE All-World     312.5   [✓ Matches] [Differs] ┐
│ iShares Core SPI            120     [✓ Matches] [Differs] │
│ Bitcoin ETP                  41.2   [ Matches ] [✕ Differs]│
└───────────────────────────────────────────────────────────┘
          [ All match ]          [ Done ]
```

- "All match" marks every row and closes the card. The answer is stored **on the device** per
  snapshot (a convenience, not a record). **Gap G15-3:** a server "confirmed by member" flag
  on the snapshot would carry it to other devices.
- "Differs" asks "What does your broker show?" (units field), then offers:
  1. **Import a newer holdings statement** ([S7](import-statement.md)),
  2. **Correct the units of this snapshot**: `PUT /api/v1/accounts/{accountId}/snapshots/{snapshotId}`
     (`ReplaceAccountSnapshotRequest`) + `If-Match`. The value is then marked by the server as
     the member's entry.
- getquin's "No" leads nowhere. Ours always ends in one of these two fixes or "Ask me later".

## Inline signals on other screens

| Where | Signal |
|---|---|
| S1 data status banner | count of open checks of severity "Affects a total" + "Review" → S15 |
| S9 / S11 right rail (web) | "Data checks" card scoped to the section / depot |
| S11 / S12 position row | "No cost basis" text, an ⓘ for an unidentified security |
| S3 transaction row | ⚠ for a transaction named by a check (G15-1) |
| Any explanation sheet | "Fix" link to the matching check |

## Actions

| Action | Result |
|---|---|
| Filter chip | client filter over the loaded list (by kind group) |
| Primary fix | the screen named in the table |
| ⋯ → "Not a problem" | **only** for `BALANCE_DIFFERS` (dismiss reconciliation, exists) and info kinds stored on the device. Hidden for the others |
| "Done recently" | checks resolved in the last 30 days (G15-1); hidden until then |

## States

| State | Rule |
|---|---|
| No open checks | `EmptyState` "All checks passed" / "Alles geprüft": "Your figures are complete as far as we can tell." |
| A source fails | the loaded checks show; `ErrorBanner` "Some checks could not be loaded" + Retry |
| Loading, offline, error | README |

## API gaps

| Gap | Needed for | Owner story |
|---|---|---|
| G15-1 `GET /api/v1/data-checks` (kinds, severity, affected IDs, totals affected, resolved list) | one source, negative units, gaps, duplicates, "Done recently" | #699 |
| G15-2 write the cost basis of one position without resending the snapshot (optional; the snapshot `PUT` works) | simpler `COST_BASIS_MISSING` fix | EPIC 15, with #589 |
| G15-3 member confirmation on a holdings snapshot | units check across devices | #699 |

## Acceptance checks for the UI story

- Given one unreconciled account and one depot with 2 positions missing a cost basis, S15 shows
  two cards, and the S1 banner counts 1 (only the check that affects a total).
- Given "Reconcile" on a balance check, M15 opens for that account, and after an accept the
  check disappears.
- Given "Differs" on a position, the member can import a statement or correct the units, and
  the correction sends `If-Match`.
- No check card shows an amount the app computed. Counts are counts of rows.
