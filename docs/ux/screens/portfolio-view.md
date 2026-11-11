# S2 Portfolio View: one tab per portfolio

**Figma:** M05 Portfolio view, DKB tab (62:2128) · M06 Account, Depot positions (22:827, for the
position row) · W02 Overview, DKB portfolio tab (65:2549) · W03 Depot positions (31:441).
**Requirements:** FR-INS-013/014 (portfolio tabs, sections per account type), FR-NAV-13/15/16
(mixed contents, negative totals, Personal assets), FR-INS-SUM-003, FR-CON-007, FR-API-010.
**PO input:** #377 comments of 2026-10-20 (getquin-style tabs; only sections that exist;
negative totals allowed).
**Rules for every screen:** see [README](README.md#rules-for-every-screen).

## Purpose

Shows one portfolio, for example "DKB", "Yuh" or "Home". It shows the portfolio's total and how
that total developed, then **one section per account type it contains**. Each section looks the
way that type needs: a balance list for cash, positions for a depot, and so on.

## Entry points

- A portfolio tab in the [Overview](overview.md) tab strip, or a portfolio row on the first tab.
- Deep link `/overview/{portfolioId}`. On `NOT_FOUND` (deleted or archived meanwhile), the app
  switches to the first tab and shows a toast: "This portfolio no longer exists."

## Layout

### Phone (M05)

```
Overview
… [ Yuh ][ DKB ][ VIAC 3a ] …            ← PortfolioTabs, DKB selected
┌ [DKB] DKB                                  [Mixed] ┐
│ Institution DKB · Germany · portfolio currency EUR │
│ EUR 76'856.45                      [Estimated]     │
│ ≈ CHF 71'953.01   ⓘ 1 EUR = 0.9362 CHF · 20 Oct    │
│ (↗ +EUR 1'412.60 · +1.9%) past month               │  ← HISTORY API (#477; frontend integration pending)
│ [ value chart ]   (1M)(YTD)(1Y)(Max)               │  ← HISTORY API (#477; frontend integration pending)
└────────────────────────────────────────────────────┘
┌ [▣] Cash & savings                  EUR 19'210.55 ┐
│ Girokonto                              4'210.55  > │
│ Current account · DE12 •••• 4471                   │  ← identifier: `maskedIdentifier` (#482)
│ Tagesgeld          [✓ Reconciled]     15'000.00  > │  ← reconciled: `reconciliationStatus` (#482)
│ Savings                                            │
└────────────────────────────────────────────────────┘
┌ [↗] Depot                           EUR 58'930.20 ┐
│ (↗ Total return +7.8%)            ← null until EPIC 15/16 │
│ [iS] iShares Core MSCI World   30'512.55         > │  ← positions API delivered (#478)
│      310 units · IE00B4L5Y983  +11.2%              │
│ …                                                  │
│ 3 positions · as of 30 Sep 2026        Open depot >│
└────────────────────────────────────────────────────┘
┌ [▭] Credit card                    −EUR 1'284.30 ┐
│ Visa · statement 28 Oct          (🕓 Due in 8 days)│
└────────────────────────────────────────────────────┘
┌ [✓] File import · last import 18 Oct · 41 transactions ┐  ← `lastImport` (#482)
│ [ ⤒ Import statement ]                             │
└────────────────────────────────────────────────────┘
```

### Web (W02)

- Main column (as on phone): header card, then the section cards. The depot section shows
  positions as a table: Position (name + ISIN) | Units | Value | Unrealised gain (amount + %).
- Right column, top: "Portfolio details" with Name, Type ("Mixed (suggested)" when
  `portfolioTypeOverridden=false`), Institution, Portfolio currency and Accounts. "Edit" opens
  edit portfolio (not in sprint 7: hidden).
- Right column, below: "Cash flow · {month}". It needs a per-portfolio cash flow, which
  `/cash-flow` doesn't offer, so it's **hidden in sprint 7** (see Differences).
- Right column, last: "Data source" with the last import (`lastImport`, #482).

## Requests

1. `GET /api/v1/portfolios/{portfolioId}/view` → `PortfolioViewResponse`: the complete header, rows and import footer, including #482.
2. For each account in a `DEPOT` section: `GET /api/v1/accounts/{accountId}/positions`
   (**delivered by #478**: latest holdings snapshot, server-valued positions).
3. `GET /api/v1/portfolios/{portfolioId}/history?period=1M` (backend endpoint delivered by #477; chart integration pending).

The header uses the tab's `PortfolioListItemResponse` (already loaded by S1) until (1) arrives.
That way the name, logo and total never flash empty.

## Header fields

| # | Element | Label key (EN / DE draft) | Format | Source: `/portfolios/{id}/view` → field |
|---|---|---|---|---|
| 1 | Logo | | `InstitutionLogo` | `/portfolios` list → `logoUrl` (also in the view response) |
| 2 | Name | | title | `name` |
| 3 | Type badge | `portfolioType.{BANK,PORTFOLIO,PILLAR_3A,PENSION_FUND,CRYPTO,PERSONAL_ASSETS,MIXED}` "Bank", "Portfolio", "Pillar 3a", "Pension fund", "Crypto", "Personal assets", "Mixed" | neutral badge | `portfolioType`; unknown value ⇒ no badge |
| 4 | Subtitle | `portfolio.subtitle` "Institution {institution} · {country} · portfolio currency {currency}"; without an institution: "Portfolio currency {currency}" | | institution and country from the `/portfolios` list item (`institutionType`, `country`, catalogue name); `portfolioCurrency`. `institutionName` is available on the view and list item; without it show only "Portfolio currency {currency}" |
| 5 | Total | | `Amount`, large; may be **negative** | `total`, `portfolioCurrency` |
| 6 | Status | `ValueStatusBadge`; `total.incomplete` | | `valueStatus`, `complete`, `warnings[]` |
| 7 | Converted total | `Amount` with `approximate` (spoken key `amount.a11y` with `approx`, `sign`, `amount`, `currency`) | | `totalInWorkspaceCurrency`, `workspaceCurrency`; only if the currencies differ |
| 8 | FX hint `[advanced]` | `portfolio.fx.rateFrom` (`from`), the rate figure, then `portfolio.fx.rateTo` (`to`, `date`) | ⓘ opens explanation | `conversionRate`, `conversionRateDate` (#482). `{date}` is `conversionRateDate`, the valuation date. When `conversionRateCarriedForward` is true, mark the hint like a carried-forward value (PR-011); when `conversionRateStale` is true, use the stale marking. The rate is the one accounts in the portfolio currency are converted with; an account in a third currency is converted with its own rate |
| 9 | Change chip, chart, period switch | as S1 #8–#11 | | **HISTORY API (#477; frontend integration pending)** (portfolio scope) |

## Sections

Sections come **only** from `sections[]`, in the order the server sends: Cash & savings,
Pillar 3a, Pension, Depot, Crypto, Cards, Property & mortgage, Other. A section the portfolio
doesn't contain is not sent and not shown. The app never hard-codes which sections a portfolio
"should" have.

Every section is a `SectionCard`:

| Element | Label key | Source: `sections[]` → field |
|---|---|---|
| Icon + title | `section.{CASH_AND_SAVINGS,PILLAR_3A,PENSION,DEPOT,CRYPTO,CARDS,PROPERTY_AND_MORTGAGE,OTHER}` "Cash & savings", "Pillar 3a", "Pension", "Depot", "Crypto", "Credit card(s)", "Property & mortgage", "Other" | `section`; unknown value ⇒ title "Other", rows still shown |
| Subtotal | | `subtotal` (portfolio currency); `subtotalInWorkspaceCurrency` in the explanation sheet only |
| Status | `ValueStatusBadge`, `total.incomplete` | `valueStatus`, `complete` |

### Row display per section

Every row comes from `sections[].accounts[]` (`AccountRow`) and opens [Account detail (S3)](account-detail.md).

| Section | Row line 1 | Row line 2 | Right side | Extra |
|---|---|---|---|---|
| Cash & savings | `name` | account type label (`accountType.{code}`) · masked identifier (`maskedIdentifier`, delivered #482) | `balance` in `currency`; if `currency ≠ portfolioCurrency`: `valueInPortfolioCurrency` as "≈ …" | `ValueStatusBadge`; "✓ Reconciled" chip when `reconciliationStatus = RECONCILED` (delivered #482) |
| Pillar 3a, Pension | `name` | type label · "as of {valueSourceDate}" | value | `totalReturn` as `ChangeChip` "Total return {percent}" **only when not null**. The server sends `null` until performance is calculated (EPIC 15/16, PO decision on #390), so in sprint 7 the chip never shows. The code path is built and tested with a non-null fixture |
| Depot | `name` | "{positionsCount, plural, one {# position} other {# positions}} · as of {positionsAsOf}" | value | `totalReturn` chip; then up to **5 positions** (delivered by #478, sorted by account-currency value desc as sent) as `PositionRow`; "Show all {n} positions" opens S3 for the depot. Sub-tabs Allocation/Performance: **not shown** (EPIC 16/17) |
| Crypto | as Depot | | | as Depot |
| Cards | `name` | type label · "statement {date}" (`statementDate`, delivered #482) | `balance` (negative) | Due chip `portfolio.card.due` "{count, plural, one {Due in # day} other {Due in # days}}" from `dueDate`. Day count = calendar days from today to `dueDate`. This is date arithmetic, not a money figure. Overdue: `portfolio.card.overdue` "Overdue since {date}" in the warning colour |
| Property & mortgage | `name` | "Manual value · {valueSourceDate}" for a manually valued asset; type label for the mortgage | value | A mortgage with `linkedPropertyAccountId` is indented under its property |
| Other | `name` | type label | value | |

`PositionRow` (delivered by #478) has: logo initials of the name, `displayName`, the line
"{quantity} units · {isin}", `valueInAccountCurrency`, and `unrealisedGainPercent` as a
signed % with gain/loss colour **plus sign**. `costBasisEstimated` adds "Estimated". Without
a cost basis the % is "–" with the explanation "No cost basis yet" (M24, insufficient data).
A holding in another currency than the depot (`priceCurrency` ≠ `currency`) has no gain either,
even with a `costBasis`: the statement's cost basis has no currency of its own, so the server
sends `null` rather than a figure that may mix currencies (FR-PERF-018). The % is then "–" with
"Gain not available in {priceCurrency}".

### Value of a row

- `valueKnown=false`: the amount is "–" with the `UNKNOWN` badge, and the subtotal is marked
  incomplete by the server.
- The row amount is `balance` in the account's own `currency`. The portfolio-currency value is
  only shown "≈" when the currencies differ.

## Footer: data source (delivered #482)

"{fileFormat}: last import {date} · {count, plural, one {# transaction} other {# transactions}}"
from `lastImport`: `{date}` is `committedAt` (when the import was committed; the newest commit
is the one shown), `{count}` is `transactionCount`, `{fileFormat}` is one of `CSV`,
`SPREADSHEET`, `PDF`, `CAMT` (the codes the catalogue's `importFormats` uses, see [Add portfolio](add-portfolio.md)). `uploadedAt` is not shown. The "Import
statement" button opens [Import statement (S7)](import-statement.md) with the account picker.
No last import: "No import yet" + the button.

## States

| State | Rule |
|---|---|
| Empty portfolio (no accounts, `sections` empty) | `EmptyState` with `portfolio.empty.title` (`name`) and `portfolio.empty.body` with "Add account" → [S6](add-account.md) and "Import statement" → [S7](import-statement.md). The total shows 0.00 as sent. |
| Negative total | Shown as sent, with minus and loss colour (e.g. a "Home" portfolio with a mortgage larger than the property value). Never clamped to 0, never hidden (FR-NAV-15). |
| Personal assets default (`personalAssetsDefault=true`) | Same layout. Shows no institution or import footer. Type badge "Personal assets". |
| Estimated / stale / unreconciled / unknown | Badges per row, per section and on the total, each with the status the server sends for that level. |
| Positions not loaded or failed | The depot row and subtotal still show (they come from the view). The position list shows `ErrorBanner` with Retry inside the depot card only. |
| Archived account | Not sent by the view: not shown. |
| Loading, refreshing, offline, error | README. On a view error, the header keeps the list item's total from S1 and shows Retry. |

## Actions

| Action | Result | Request |
|---|---|---|
| Tap an account row | S3 for that account | - |
| "Show all positions" / tap a position | S3 for the depot (position detail M07 is not in sprint 7) | - |
| Tap the total or a subtotal | Explanation sheet: currency, `asOf`, `complete`, the warnings, "≈" conversion basis | loaded data |
| Period switch | as S1 | `/api/v1/portfolios/{portfolioId}/history?period=…` |
| Pull to refresh | Reloads 1–3 | |

## Differences from Figma

| Figma | Spec | Why |
|---|---|---|
| "Savings · 2.25% p.a." | "Savings" | No interest rate in the data model |
| Depot sub-tabs Positions / Allocation / Performance | Positions only | Allocation and performance come with EPIC 16/17 |
| "+7.8% YTD (TWR)" | "Total return {percent}" once `totalReturn` is sent; hidden in sprint 7 | `totalReturn` is `null` until EPIC 15/16; TWR is EPIC 16 |
| W02 "Cash flow · October" per portfolio | Hidden | `/cash-flow` is workspace-wide only |
| W02 "Edit" portfolio details | Hidden | Edit portfolio is not a sprint-7 screen |
| "1 EUR = 0.9362 CHF · 20 Oct" | Rate and valuation date shown when resolvable, marked when carried forward or stale (#482) | Accounts in a third currency use their own rate |

## Acceptance checks for the UI story

- Given a portfolio with a current account and a credit card, it shows exactly the sections
  "Cash & savings" and "Credit card", in that order, and no other.
- Given a "Home" portfolio whose mortgage exceeds the property value, the total shows with a
  minus sign. Its accessible label reads "minus".
- Given an account row with `valueStatus = STALE` and `valueSourceDate = 2026-09-14`, the row
  shows "Price from 14 Sep" (or the glossary's wording) next to the amount.
- Given a card with `dueDate` 8 days ahead, the chip reads "Due in 8 days". With `dueDate`
  `null`, there is no chip.
- Given an unknown `section` value from a newer server, the rows show under "Other" and nothing
  crashes.

## Historical portfolio API status (#477)

The backend delivers `GET /api/v1/portfolios/{portfolioId}/history?period={1W|1M|YTD|1Y|MAX}`.
It values accounts in the portfolio currency using portfolio membership and archive/restore
state effective on the sampled date. The separate `change.contributions` field represents
booked external capital contributions, `change.capitalMovements` covers internal movements
across portfolios and account inception/lifecycle or reassignment, and
`change.marketAndFx` is the residual after those flows. The history response also includes
`complete`, `warnings[]` and `points[].estimated`.

For unresolved transfers or loan repayments and missing FX/valuation data, attribution may
be null and the view must show incomplete-data warnings rather than a fabricated market return.
Historical state recorded before the new lifecycle tracking can be restated and is explicitly
flagged as uncertain. The backend API is available; the chart/period-switch UI remains pending.
