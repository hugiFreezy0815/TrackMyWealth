# S9 Section view: one account type across all portfolios

**Figma:** M26 Section view (phone, to be drawn) · W15 Section view (web, to be drawn). DE mirrors
use the same names in the DE sections.
**Requirements:** FR-NW (net worth by section), FR-INS-013 (portfolio context), FR-CON-007 /
NFR-UX-004 (value status), FR-API-010, NFR-UX-001/003/005.
**Rules for every screen:** see [README](README.md#rules-for-every-screen).
**Inspiration:** getquin "Cash balance" and "Liabilities" pages (screenshots of 2026-11-22). We keep
the idea of one list per type across banks and drop what does not fit our rules. See
"Design decisions" below.

## Purpose

The overview answers "where is my money?" by **portfolio** (one tab per bank). Members also
ask the question the other way round: "how much cash do I have, all banks together?" and
"what do I owe in total?" The Section view answers it. It lists one section of the net worth
(Cash & savings, Depot, Pillar 3a, Pension, Crypto, Credit card, Property & mortgage, Other)
across every portfolio, with its total, its accounts and their recent transactions.

## Entry points

- A section tile on the overview (S1, field 16): the tile becomes a link.
- A section header in the overview's "By type" list (S1 update, see [overview.md](overview.md)).
- Deep link `/overview/sections/{section}` with `section` the lower-case API value, for example
  `/overview/sections/cash-and-savings`.
- Web: breadcrumb "{overview name} › {section}" in the header.

The Depot section opens [S11 Investments](investments.md) instead, filtered to all depots, as
soon as S11 is built. Until then it opens this screen.

## Layout

### Phone (M26)

```
‹ Overview                                        [👁]
Cash & savings
┌ TOTAL · 5 ACCOUNTS                                  ┐
│ CHF 48'206.10                        [Estimated]     │
│ As of 20 Nov 2026                                    │
│ (↗ +CHF 1'204.50) past month                         │   ← HISTORY API: sections[].change (#477)
└──────────────────────────────────────────────────────┘
Accounts                                  [↕ Value ⌄]
┌ [PF] Privatkonto            CHF 12'480.30          > ┐
│      PostFinance · Current account · •••• 4021       │
│ [yuh] Yuh savings pocket    CHF  8'900.00          > │
│      Yuh · Savings account                           │
│ [DKB] Girokonto             EUR  3'210.55          > │
│      DKB · Current account · •••• 7730               │
│                             ≈ CHF 3'005.12            │
│ [DKB] Tagesgeld             EUR 25'000.00  [Price…] > │
│      DKB · Savings account · •••• 7731               │
└──────────────────────────────────────────────────────┘
Recent activity                               See all
NOVEMBER 2026
┌ 19 Nov  ↙ Salary Example AG       +CHF 6'420.00     ┐
│          Privatkonto · Income                        │
│ 18 Nov  ↗ Rent November           −CHF 1'850.00     │
│          Privatkonto · Housing                       │
└──────────────────────────────────────────────────────┘
```

- The header card uses the same `SectionCard` and `Amount` as S2's sections.
- Accounts: an `AccountRow` v2 with the portfolio's logo, the account name, and a subtitle line
  "{portfolio} · {account type} · {masked identifier}". The masked identifier is left out when
  the server sends none.
- "Recent activity" shows the newest 10 transactions of all accounts in the section,
  grouped by month (see the S3 update). "See all" opens the full list.
- For **liability sections** (Credit card, Property & mortgage when the account is a loan) the
  amount reads as a debt: label "Owed" / "Offen", magnitudes as sent (like S1 field 13), and the
  credit card row shows "Statement {date} · due {dueDate}".

### Web (W15)

- Breadcrumb "My finances › Cash & savings" above the title.
- Main column: header card, then the account list as a table (Account | Portfolio | Type |
  Identifier | Value | Status), then "Recent activity" as a table (Date | Description | Account |
  Category | Amount).
- Right rail (360 px): "In and out this month" card (S1 fields 23–26, scoped to the section's
  accounts **only when the API offers it**, see gaps), then a "Data checks" card listing the
  section's open issues ([S15](data-checks.md)).
- Below 1024 px the phone order in one column.

## Requests

1. `GET /api/v1/net-worth?currency=Cur` → `sections[]` entry with `section = {section}`:
   `subtotal`, `valueStatus`, `complete`, `accountCount`. Usually already cached from S1.
2. The accounts of the section: for every portfolio, `GET /api/v1/portfolios/{id}/view` →
   `sections[]` with `section = {section}` → `accounts[]` (`AccountRow`). S1 already holds the
   portfolio list, and S2 caches the views. **Gap G9-1:** a single
   `GET /api/v1/accounts?section={section}` with `maskedIdentifier` and `portfolioName` would
   replace the N calls. Until then the N view calls are bounded by the portfolio count; run at
   most four at a time.
3. Recent activity: **Gap G9-2.** A workspace-wide transaction list
   `GET /api/v1/transactions?section=&page=&size=` does not exist yet. Until it does, the
   "Recent activity" block is **hidden**. The app must not merge per-account pages on the client,
   because such a merge sorts across accounts and pages inconsistently.
4. Change chip: `GET /api/v1/net-worth/history?period=1M` → `sections[].change` (#477). Hidden
   while that field is not sent.
5. Credit cards in the section: `GET /api/v1/accounts/{cardAccountId}/statement` →
   `periodEnd`, `dueDate`, `paid`, `closingBalance`. Alternatively `AccountRow.statementDate`
   and `dueDate` from request 2 are enough for the row line, and are preferred.

## Fields

| # | Element | Label key (EN / DE) | Format | Source |
|---|---|---|---|---|
| 1 | Title | `section.{code}` (same keys as S1 tiles) | | route `section` |
| 2 | Total label | `section.total` "Total · {count, plural, one {# account} other {# accounts}}" / "Gesamt · …" | | (1) `accountCount` |
| 3 | Total | | `Amount`, large, *Cur* | (1) `subtotal` |
| 4 | Status | `ValueStatusBadge`, `total.incomplete` | | (1) `valueStatus`, `complete` |
| 5 | Change chip | `history.*` as S1 field 8 | `ChangeChip` | (4), hidden until delivered |
| 6 | Account row: logo, name | | | portfolio `logoUrl`; `AccountRow.name` |
| 7 | Account row: subtitle | `section.accountSubtitle` "{portfolio} · {type} · {identifier}" | | portfolio `name`; `accountType.{code}`; `maskedIdentifier` |
| 8 | Account row: value | | `Amount` in account currency | `balance`, `currency`; `valueKnown=false` ⇒ "–" |
| 9 | Account row: converted value | "≈ {amount}" | secondary | **Gap G9-1**: `AccountRow` has `valueInPortfolioCurrency`, not a value in *Cur*. Shown only when the portfolio currency equals *Cur*; otherwise hidden |
| 10 | Account row: status | `ValueStatusBadge` | | `valueStatus`, `valueSourceDate` |
| 11 | Card row line | `section.card.statement` "Statement {date} · due {dueDate}" | short dates | `statementDate`, `dueDate` |
| 12 | Sort menu | "Value" (default, as sent), "Name", "Portfolio" | | Sorting rows is ordering, not a computation, so it is allowed (FR-API-010 forbids only derived figures) |

## Actions

| Action | Result |
|---|---|
| Tap an account | [S3 Account detail](account-detail.md); for a loan [S10](loan-detail.md); for a depot [S11](investments.md) filtered to it |
| Tap the total | Explanation sheet: which accounts count, as-of date, FX rates (from the views' `conversionRate*`), status |
| "See all" (Recent activity) | Full transaction list of the section (needs G9-2) |
| Breadcrumb (web) | Back to the overview tab it came from |

## States

| State | Rule |
|---|---|
| Section without accounts (stale deep link) | `EmptyState` "No accounts of this type yet" + "Add account" → [S6](add-account.md) |
| One portfolio view fails | Its accounts are missing; an `ErrorBanner` "Some accounts could not be loaded" with Retry; the total from request 1 stays, with "Not all values are included" |
| Loading, refreshing, offline, error | README |

## Design decisions (vs. the getquin example)

| getquin | TrackMyWealth | Why |
|---|---|---|
| Rows show only the masked number | Portfolio, type **and** masked identifier | Two accounts at the same bank with the same name (as in the example) cannot be told apart otherwise |
| Liabilities shown with a minus sign | Magnitude + "Owed" label, as on S1 | PO 2026-11-09: liabilities are shown as sent (magnitudes) |
| Merged transactions without account name | Every row names its account | In a cross-account list the account is the first question |
| Side card with a spending percentage and no context | Section cash flow only from the API, with an explanation sheet | FR-API-010, NFR-UX-003 |
| Value status absent | Badge per account and on the total | NFR-UX-004 |

## API gaps

| Gap | Needed for | Owner story |
|---|---|---|
| G9-1 `GET /api/v1/accounts?section=` with `maskedIdentifier`, `portfolioName`, value in the requested currency | one request instead of N; field 9 | new story (EPIC 04), to be filed |
| G9-2 workspace transaction list with `section` / `accountId[]` filters, paged | Recent activity, "See all" | new story (EPIC 07), to be filed. It is also the base of the later Cash flow tab |

## Acceptance checks for the UI story

- Given two portfolios with cash accounts in CHF and EUR, the Cash & savings view lists all of
  them, with the total from `/net-worth` and no figure computed in the app.
- Given two accounts with the same name at one bank, their subtitles differ by the masked
  identifier.
- Given a credit card with a statement date, the row reads "Statement 30 Nov · due 20 Dec".
- Given that G9-2 is not delivered, the Recent activity block does not render, and no
  placeholder shows.
- Tapping a liability total shows magnitudes without an added minus sign.
