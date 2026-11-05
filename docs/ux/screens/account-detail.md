# S3 Account detail with its transaction list

**Figma:** **M25 Account detail** (new: EN 115:2999, DE 115:3443; built for this spec from M06's header and balance card and
M10's transaction list) · M06 Account, Depot positions (22:827) for depots · M10 Transactions &
categories (25:797) for the row and filter style · W06 Transactions & categorisation (32:1072)
for the web table and category panel.
**Requirements:** FR-TXN (ledger), FR-CAT (uncategorised filter, user override wins: US-08-02),
FR-REC (reconciliation status), US-05-03 (archive/restore), NFR-UX-004, FR-API-010.
**Rules for every screen:** see [README](README.md#rules-for-every-screen).

## Purpose

One account: its value, whether it matches the bank statement, and its transactions, newest
first. Members find uncategorised transactions here and give them a category.

## Entry points

- An account row in the Portfolio View (S2), or the data-status "Review" on the overview (S1).
- Deep link `/accounts/{accountId}`.

## Layout

### Phone (M25)

```
‹  DKB · Current account · EUR                       [⋯]
   Girokonto
┌ BALANCE                                              ┐
│ EUR 4'210.55                         [Estimated]     │
│ As of 20 Oct 2026                                    │
│ ✓ Reconciled · matches the statement of 30 Sep 2026  │
└──────────────────────────────────────────────────────┘
( All ) ( Uncategorised 3 )                  [↕ Date ⌄]
⚠ 3 transactions need a category · Review
TODAY · 20 OCT
┌ [🛒] Migros                               −EUR 84.35 ┐
│      Card payment                                    │
│      [Groceries]                                     │
│ [⇄] Transfer to Tagesgeld               −EUR 500.00 │
│      Transfer · [Internal transfer · excluded]       │
└──────────────────────────────────────────────────────┘
18 OCT
┌ [▭] Amazon.de                              −USD 42.00 ┐
│      Card payment · [Uncategorised]   ≈ EUR 39.99    │   ← ≈: GAP-TXN-READ (#479)
└──────────────────────────────────────────────────────┘
            Showing 50 of 214 · Load more
```

### Depot (`holdsPositions = true`, M06)

The header shows "Market value". Below it are the tabs **Positions** (GAP-POSITIONS (#478), the full
list as in S2's `PositionRow`) and **Activity** (the transaction list of this spec). The
"Performance" tab is not shown (EPIC 16).

### Web (W06 layout, scoped to one account)

- Header card across the main column. The transaction list is a table with the columns
  Date | Description | Category | Amount (no Account column).
- Selecting a row opens [S4](transaction-detail.md) in the right panel instead of a new screen.
- The category cell's ⌄ opens the category picker inline.

## Requests

1. `GET /api/v1/accounts/{accountId}` → `AccountSummaryResponse`: name, type, currency,
   capabilities, status, `version` (ETag), reconciliation, warnings.
2. The account's value with its status: the `AccountListItemResponse` for this account, taken
   from the S1/S2 cache, or else from `GET /api/v1/accounts?portfolioId={portfolioId}` (with
   `archived=true` for an archived account), picking `id = accountId`. *A single-account
   list item doesn't exist; this bounded list is the documented way.*
3. `GET /api/v1/accounts/{accountId}/transactions?page=0&size=50&sort=bookingDate,desc` (+
   `uncategorized=true` when that chip is on).
4. Count for the chip: `GET /api/v1/accounts/{accountId}/transactions?uncategorized=true&size=1`
   → `page.totalElements`.
5. `GET /api/v1/categories` (once per session, cached): category names for the chips.
6. Depot: `GET /api/v1/accounts/{accountId}/positions` (GAP-POSITIONS (#478)).

Request 3 needs `hasTransactions = true` from request 1. Without it, skip 3 and 4 (see States).

## Header fields

| # | Element | Label key (EN / DE draft) | Format | Source |
|---|---|---|---|---|
| 1 | Back + context line | `account.context` "{portfolio} · {type} · {currency}" | | (2) `portfolioName`; `accountType.{code}`; (1) `nativeCurrency` |
| 2 | Title | | | (1) `name` |
| 3 | Value label | `account.balance` "Balance" / "Saldo"; for `manualValuation`: `account.value` "Value" / "Wert"; for `holdsPositions`: `account.marketValue` "Market value" / "Marktwert" | | (1) capabilities |
| 4 | Value | | `Amount`, large | (2) `balance`, `currency`; `valueKnown=false` ⇒ "–" |
| 5 | Converted value | "≈ {amount}" | | (2) `balanceInWorkspaceCurrency`, `workspaceCurrency`; only if the currencies differ |
| 6 | Status badge | `ValueStatusBadge` | | (2) `valueStatus`, `valueSourceDate`, `warnings[]` |
| 7 | As-of line | `account.asOf` "As of {date}" | | (2) `asOf`; for a snapshot or valuation: "Statement of {valueSourceDate}" |
| 8 | Reconciliation line | see the table below | icon + text, never colour alone | (1) `reconciliation.status`, `.asOf`, `.openDifference`, `.currency`, `.reason` |
| 9 | Archived banner | `account.archived` "Archived on {date}. It no longer counts towards your totals." + "Restore" | | (1) `status = ARCHIVED`, `archivedAt` |

Reconciliation line (FR-REC; the wording is a draft for #379):

| `reconciliation.status` | Text (EN) | Style |
|---|---|---|
| `RECONCILED` | "Reconciled · matches the statement of {asOf}" | success |
| `OPEN_DIFFERENCE` | "Differs from the statement of {asOf} by {openDifference}" + "Review" (hidden in sprint 7: the reconcile screen M15 is not in scope) | warning |
| `DISMISSED_DIFFERENCE` | "Difference of {openDifference} accepted on {asOf}" | neutral |
| `NEVER` | "Not yet checked against a statement" | neutral |
| `NO_OPENING_BALANCE` | "Add an opening balance to check against statements" | neutral |
| `NOT_RECONCILABLE`, `CASH_SCOPE_NOT_APPLICABLE` | line hidden | |
| unknown | line hidden | |

## Transaction list

`TransactionRow`, grouped by `bookingDate` under headers "Today · {date}", "Yesterday · {date}"
and then "{date}". The grouping only follows the sort; it adds up nothing. When the list is
sorted by amount, the date headers are dropped and each row shows its date.

| # | Element | Source: `TransactionResponse` → field | Rule |
|---|---|---|---|
| 1 | Icon | `transactionType`, `internalTransfer` | type icon; transfer arrows for `internalTransfer`; warning icon if uncategorised |
| 2 | Title | `merchantDescription`, else `counterpartyName`, else the type label | one line, ellipsis; full text in the accessible label |
| 3 | Subtitle | type label `transactionType.{code}`; `counterpartyName` when it differs from the title | |
| 4 | Category chip | `categoryId` → `GET /categories` item: `nameDe`/`nameEn` by app language, "{parent} › {name}" on web | The category with `code = UNCATEGORIZED` (the shipped default; `categoryAssignedBy` is `null`) shows "Uncategorised" in warning style. `categoryId = null` means a type that is never categorised (settlement, trade, dividend), so there is no chip. `internalTransfer` shows "Internal transfer · excluded" (teal). A `categoryId` missing from the cached list triggers one reload of `/categories`, then "Category" as a fallback |
| 4a | Lifecycle | `voidedAt`, `replacesTransactionId`, `correctsTransactionId`, `restoresTransactionId`, `reconciliationAdjustment` | A voided original stays listed: amount struck through, badge "Voided" / "Storniert", and the accessible label says "voided, not counted". A reversing row (`replacesTransactionId`) shows "Reversal of {date}". A correction (`correctsTransactionId`) shows "Corrected". A reconciliation adjustment has no `merchantDescription`: its title is `transactionType.VALUATION_ADJUSTMENT` "Statement adjustment" |
| 5 | Amount | `amount`, `currency` | signed `Amount`; positive with "+" and gain colour; the sign is always shown |
| 6 | Converted amount `[advanced]` | **GAP-TXN-READ (#479)** `amountInAccountCurrency` | "≈ {amount}" only when `currency ≠` the account currency |
| 7 | Estimated FX | `fxRateEstimated` | "Estimated" badge next to 6 |
| 8 | Warnings | `warnings[]` | ⚠ icon. `BOOKED_BEFORE_OPENING_BALANCE` reads "Before the opening balance: not counted in the balance". Other codes use the glossary text (#379), or a generic "Check this transaction" for an unknown code |

### Filters and sort

- Chips: **All** and **Uncategorised {count}**. The count comes from request 4. The chip is
  hidden when the count is 0, and the filter then falls back to All.
- When the count is > 0, a banner shows: "{count, plural, one {# transaction needs} other {#
  transactions need}} a category" / "They are counted, never hidden in 'Other'." with
  "Review", which turns the Uncategorised chip on.
- Sort menu: "Newest first" (`bookingDate,desc`, default), "Oldest first" (`bookingDate,asc`),
  "Largest amount" (`amount,desc`), "Smallest amount" (`amount,asc`). `amount` sorts by the
  value in the account currency (conventions.md).
- Any change of filter or sort drops the loaded pages and reloads from page 0.

### Paging

- `size=50`. Phone: infinite scroll; the next page loads when the last 10 rows come into view.
- Web: a "Load more" button and the line "Showing {shown} of {page.totalElements}".
- After any change on S4 (category, correction, removal), after a returning import, and on
  pull-to-refresh: reload from page 0 and keep the filter and sort.
- A page request that fails shows an inline Retry at the end of the list. Already loaded rows
  stay.

## Actions

| Action | Result | Request |
|---|---|---|
| Tap a row | S4 (phone: push; web: right panel) | - |
| Category chip ⌄ (web) / long-press → "Change category" (phone) | `CategoryPicker` | `PUT /api/v1/accounts/{accountId}/transactions/{transactionId}/category` `{categoryId}` with `If-Match: "{transaction.version}"` → updated `TransactionResponse` replaces the row; reload request 4 |
| Uncategorised banner "Review" | Turns the Uncategorised filter on | |
| ⋯ → Archive account | Confirmation: "Archive {name}? It stays in your history but no longer counts towards your totals." | `POST /api/v1/accounts/{accountId}/archive` with `If-Match: "{version}"`. Afterwards reload 1–2, and S1/S2 must reload |
| Archived banner → Restore | | `POST /api/v1/accounts/{accountId}/restore` with `If-Match` |
| "+" add transaction (M18) | **Hidden** in sprint 7 | |
| Tap the value | Explanation sheet: value basis, `asOf`, `valueSourceDate`, the status explanation, `warnings[]` | loaded data |

Errors on these actions:

- `VERSION_CONFLICT`: conflict dialog, then reload the row or account.
- `ACCOUNT_ARCHIVED` (409, `SHOW`): "This account is archived; restore it first."
- `RECONCILIATION_ADJUSTMENT_LOCKED`: shown as its catalogue message.
- `NOT_FOUND`: back to S2 with a toast.

## States

| State | Rule |
|---|---|
| No transactions yet (`hasTransactions=true`, empty page 0, no filter) | `EmptyState` "No transactions yet" / "Import a statement or add a transaction." Actions are hidden when their screens aren't in sprint 7. |
| Filter returns nothing | "No uncategorised transactions" with a "Show all" button. |
| Account without a ledger (`hasTransactions=false`, e.g. a manually valued property) | No list and no filters. The text reads "The value of this account comes from {valueBasis}" (manual valuation, loan statement…), with the value's date. |
| Archived (`status = ARCHIVED`) | Archived banner. Changing actions are disabled with the reason "Restore the account to change it". The list stays readable. |
| Estimated / stale / unreconciled / unknown | Header badge (6) and reconciliation line (8). |
| Loading, refreshing, offline, error | README. A header error shows the cached S2 row values with Retry. |

## Differences from Figma

| Figma | Spec | Why |
|---|---|---|
| M10 is workspace-wide "Cash flow" with an Account line on every row | S3 is one account; no account line | The workspace-wide list (`GET /api/v1/transactions`) is the Cash flow tab, not in these four specs |
| M10 chips Transfers / Cards | Only All / Uncategorised | Only `uncategorized` is a filter of the account list |
| M06 "Invested", "Dividends YTD", "Cash in depot" | Hidden | No source; EPIC 15/16 |
| W06 bulk checkboxes, "Always categorise …", "Apply to 6 earlier" | Hidden | Bulk and rules are not in sprint 7 |

## Acceptance checks for the UI story

- Given an account with 120 transactions, the list loads 50, then 50, then 20, with no
  duplicates. After a category change it restarts at page 0 with the same filter.
- Given 3 uncategorised transactions, the chip reads "Uncategorised 3". After one of them gets a
  category, it reads "Uncategorised 2" (from the reloaded count, not decremented on the client).
- Given an internal transfer, it shows "Internal transfer · excluded" and no "Uncategorised".
- Given a voided imported transaction, the original row shows struck through with "Voided", and
  its reversal row shows "Reversal of {date}".
- Given a dividend (`categoryId = null`), no category chip shows and it isn't counted as
  uncategorised.
- Given `reconciliation.status = OPEN_DIFFERENCE`, the line shows the difference with an icon and
  text, not colour alone.
- Given an archived account, "Change category" is disabled and Restore sends `If-Match`.
