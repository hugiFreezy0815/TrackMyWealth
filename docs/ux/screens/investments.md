# S11 Investments, with S13 Performance and S14 Dividends

**Figma:** M28 Investments (phone) · M30 Performance sheet · M31 Dividends · W16 Investments (web).
All to be drawn; DE mirrors use the same names.
**Requirements:** EPIC 15 (positions, cost basis), EPIC 16 (performance), EPIC 17 (income),
FR-CON-007 / NFR-UX-004, FR-API-010, NFR-UX-001/003.
**Rules for every screen:** see [README](README.md#rules-for-every-screen).
**Inspiration:** getquin "Investments" page (portfolio chart, positions table, dividends,
performance breakdown; screenshots of 2026-11-22). See "Design decisions" for what we do
differently.

## Purpose

One place for everything the member holds in securities, all depots together or one at a time:
"What are my investments worth, what did I pay, am I up or down, and is this figure reliable?"

## Navigation

When S11 ships, the IA unhides **Investments**:
- Phone bottom tabs: **Overview · Investments · More**.
- Web sidebar: **Overview · Investments · Settings**.

The Depot tile on S1 and the depot rows on S2/S9 lead here. See
[01-information-architecture.md](../01-information-architecture.md) §2. Route `/investments`, and
`/investments?account={accountId}` for one depot.

## Layout

### Phone (M28)

```
Investments                                    [👁] [+]
( All depots ) ( Swissquote ) ( Yuh ) ( VIAC 3a )         ← DepotScopeChips
┌ MARKET VALUE                                         ┐
│ CHF 92'418.40                         [Estimated]    │
│ As of 21 Nov 2026 · prices from statements           │
│ (↗ +CHF 2'310.20 · Return 2.6 %) past month          │   ← HISTORY API (#477) for one depot
│ [ value chart ]                                      │
│ ( 1W )( 1M )( YTD )( 1Y )( Max )                      │
└──────────────────────────────────────────────────────┘
┌ ⓘ Check your units                          [Dismiss] ┐   ← S15 post-import check card
│ We read 12 positions from the 31 Oct statement.       │
│ Do the units match your broker?   [ Yes ] [ Compare ] │
└──────────────────────────────────────────────────────┘
┌ PERFORMANCE                                       >  ┐   → S13 sheet
│ Total return  +CHF 9'812.30 · +11.9 %                │
│ Cost basis missing for 2 positions                   │
└──────────────────────────────────────────────────────┘
Positions · 12                         [↕ Value ⌄]
┌ [V] Vanguard FTSE All-World         CHF 31'204.10  ┐
│     VWRL · 312.5 units · Swissquote  ↗ +CHF 4'120.30 │
│                                       +15.2 %        │
│ [i] iShares Core SPI                CHF 18'770.00    │
│     CSSPI · 120 units · VIAC 3a      no cost basis   │
│ [₿] Bitcoin ETP                     CHF  6'100.00    │
│     … [Price from 31 Oct]           ↘ −CHF 410.00    │
│                                       −6.3 %         │
└──────────────────────────────────────────────────────┘
┌ ALLOCATION                                           ┐
│ By asset class ▾                                     │
│ Equity    ██████████████████████░░░░  78.4 %         │   ← AllocationBar, server shares
│ Bonds     ████░░░░░░░░░░░░░░░░░░░░░░  12.1 %         │
│ Crypto    ██░░░░░░░░░░░░░░░░░░░░░░░░   6.6 %         │
│ Unknown   █░░░░░░░░░░░░░░░░░░░░░░░░░   2.9 %         │
└──────────────────────────────────────────────────────┘
┌ DIVIDENDS & INTEREST                              >  ┐   → S14
│ Received this year CHF 1'204.70                       │
│ ▁ ▁ ▃ ▁ ▁ ▇ ▁ ▁ ▂ ▁ ▁ ·  (months)                    │
└──────────────────────────────────────────────────────┘
Activity                                       See all   ← trades, dividends, fees
```

### Web (W16)

- Main column: scope chips, the value card with the chart, the check card, the positions
  table: Security | Depot | Units | Price | Cost basis | Value | Unrealised gain (amount, %) |
  Status. Column headers sort. A row opens [S12](position-detail.md) in the right panel.
- Right rail (360 px): Performance card (opens S13 in the rail), Allocation, Dividends &
  interest.
- Below 1024 px the phone order.

## Requests

| # | Request | Used for |
|---|---|---|
| 1 | The depot `AccountRow`s: `GET /api/v1/portfolios/{id}/view` per portfolio (as S9 request 2, cached from S2), the rows of section `DEPOT` (and `PILLAR_3A` rows with `positionsCount ≠ null`) | scope chips, per-depot `totalReturn`, `totalReturnPercent`, `missingCostBasisCount`, `foreignCurrencyCostBasisCount`, `positionsAsOf` |
| 2 | `GET /api/v1/accounts/{accountId}/positions` per depot (max 4 in parallel) | positions list (`AccountPositionResponse`) |
| 3 | `GET /api/v1/net-worth?currency=Cur` → `sections[]` with `section = DEPOT` | the "All depots" market value and status |
| 4 | `GET /api/v1/portfolios/{id}/history?period=` (#477) | the chart and chip **for a scope that is exactly one portfolio**. For "All depots", **Gap G11-1** (depot-section history) |
| 5 | `GET /api/v1/accounts/{accountId}/transactions?sort=bookingDate,desc&size=10` per depot | Activity. For "All depots" Gap G9-2 (workspace transaction list), so the block is hidden there |

### What "All depots" can and cannot show today

| Element | All depots | One depot |
|---|---|---|
| Market value | `/net-worth` DEPOT section `subtotal` (3) | `AccountRow.balance` |
| Change chip and chart | **hidden** (G11-1) | portfolio history (#477) if the portfolio holds only this depot, else hidden (G11-1) |
| Total return | **hidden** (G11-2: the sum of per-depot returns is a computation) | `AccountRow.totalReturn`, `totalReturnPercent` |
| Positions | the depots' lists one after the other, each under a depot header; **no merging** of the same security across depots (G11-3) | one list |
| Allocation | **hidden** (G11-4) | **hidden** (G11-4) |
| Dividends & interest | **hidden** (G11-5) | **hidden** (G11-5) |

This table is the honest MVP: S11 can ship on the existing API for one depot and as a list for
all depots. The rest appears when the server sends it.

## Positions list (`PositionRow` v2)

| # | Element | Source: `AccountPositionResponse` | Rule |
|---|---|---|---|
| 1 | Logo | security logo (G11-6) | initials of `displayName` until logos exist |
| 2 | Name | `displayName` | one line, ellipsis; full name in the accessible label |
| 3 | Sub line | ISIN short form or symbol, `quantity` "{n} units" / "{n} Stück", depot name (All depots only) | units with up to 6 decimals as sent, no rounding beyond the format rules |
| 4 | Value | `valueInAccountCurrency` (account currency); `value` + `priceCurrency` `[advanced]` | `valueStatus ≠ CURRENT` ⇒ badge; null ⇒ "–" |
| 5 | Unrealised gain | `unrealisedGain`, `unrealisedGainPercent` | `PLChip`: arrow + sign + amount, percent one decimal (#440). Null with `costBasis = null` ⇒ text `position.noCostBasis` "No cost basis" (with a link to fix: S15 item). Null for a foreign currency ⇒ `position.costBasisForeign` "Cost basis in {currency}" |
| 6 | Estimated cost basis | `costBasisEstimated` | `Estimated` badge next to 5 |
| 7 | Sort | Value (server order, default), Name, Gain % | ordering only |

Phone shows 20 rows and then "Show all {n}". The web table shows all rows: a snapshot is not
paged.

## S13 Performance sheet (M30)

Opens from the Performance card. Phone: a bottom sheet at full height. Web: the right rail.

```
Performance · Swissquote depot                  [×]
Since 1 Jan 2024 · as of 21 Nov 2026
Total return              +CHF 9'812.30   +11.9 %
─────────────────────────────────────────────────
Invested (cost basis)      CHF 82'606.10
Market value               CHF 92'418.40
Unrealised gain           +CHF 9'812.30
Realised gain                    –        ⓘ not available yet
Dividends & interest             –        ⓘ
Fees                             –        ⓘ
Taxes withheld                   –        ⓘ
─────────────────────────────────────────────────
⚠ 2 positions have no cost basis. Their value counts,
  their gain does not.                 [Add cost basis]
```

| Line | Source today | Gap |
|---|---|---|
| Total return, % | `AccountRow.totalReturn`, `totalReturnPercent` | – |
| Invested (cost basis) | **G11-2** (the sum of `costBasis` is a computation) | needs a server total |
| Market value | `AccountRow.balance` | – |
| Unrealised gain | **G11-2** | needs a server total |
| Realised gain, dividends & interest, fees, taxes | **G11-2** (EPIC 16/17) | per-depot performance breakdown endpoint |
| Money-weighted return | **G11-2** | as above |
| Missing cost basis line | `missingCostBasisCount`, `foreignCurrencyCostBasisCount` | – |

**Rules for this sheet:**
- A line whose figure is missing shows "–" and an ⓘ that says why ("Not available yet",
  "Needs the cost basis of 2 positions"). Lines are never left out silently: the member sees
  the shape of the breakdown.
- A figure the server marks as uncertain shows its `ValueStatusBadge`. **A percentage outside
  ±1'000 % gets no special casing in the app.** The server must flag it, and the sheet then shows
  the flag. (getquin showed a time-weighted return of several hundred percent next to a
  money-weighted return with the opposite sign, without comment.)
- Each line opens a one-paragraph explanation (NFR-UX-003): what it includes, from which date.

## S14 Dividends & interest (M31)

A full screen from the card (phone push, web main column).

- Header: "Received in {year}" total + year switch (this year, last year, all).
- `MonthBarChart`: 12 bars, a value label under each, and a text alternative listing the
  months that have payments.
- List grouped by month: security, date, gross, tax withheld, net (`grossAmount`,
  `taxWithheldAmount`, `netAmount` of dividend transactions).
- **No forecasts.** getquin shows "Forecasted" rows. We have no dividend calendar source, so
  forecasts are out of scope. The spec names it as a later idea, and no placeholder shows.
- Source: **G11-5**, an income endpoint (per month, per security) on the server. The list can come
  from G9-2 (workspace transactions filtered by type DIVIDEND/INTEREST). The monthly totals must
  come from the server.

## Actions

| Action | Result |
|---|---|
| Scope chip | Reload with `account` |
| Tap a position | [S12 Position detail](position-detail.md) |
| "+" | Menu: Import statement ([S7](import-statement.md)), Add transaction (hidden until M18 ships) |
| Check card "Compare" | [S15](data-checks.md) units comparison |
| "Add cost basis" | [S15](data-checks.md) item list, filtered to missing cost basis |

## States

| State | Rule |
|---|---|
| No depot | `EmptyState` "No investments yet" + "Add a depot" ([S6](add-account.md)) and "Import a holdings statement" ([S7](import-statement.md)) |
| Depot without snapshot (`positionsAsOf = null`) | `position.noSnapshot` "No positions yet. Import a holdings statement to see them." |
| `complete = false` on a positions response | `total.incomplete` under the value |
| Loading, refreshing, offline, error | README |

## Design decisions (vs. the getquin example)

| getquin | TrackMyWealth | Why |
|---|---|---|
| Donut "Allocation" (premium) | `AllocationBar` list with labels and server percentages | Readable on a phone, accessible, needs no colour legend |
| Positions table: Buy-in / Position / P/L, with "€0.00" buy-ins for unknown cost | "No cost basis" as text + a fix link; gain hidden | An unknown cost basis shown as 0 produces fake gains |
| Dividend forecast (premium) | No forecasts | No source; we never show invented figures |
| TTWROR / IRR shown without plausibility | Status badges and explanations; server flags | NFR-UX-003/004 |
| Referral, AI analysis, premium upsell cards | None | Not part of the product |
| "CHART BY getquin" | No branding in charts | Clutter |

## API gaps

| Gap | Needed for | Owner story |
|---|---|---|
| G11-1 value history of the depot section and of one depot account | chart and chip for "All depots" / one depot in a mixed portfolio | #694 |
| G11-2 performance breakdown per depot and for all depots (invested, unrealised, realised, income, fees, taxes, money-weighted return, plausibility flags) | S13, totals on S11 | #695 |
| G11-3 positions aggregated per security across depots | merged positions | #696 |
| G11-4 allocation shares by asset class, currency and region (from `SecurityResponse.assetClass`, …) | Allocation card | #696 |
| G11-5 income per month and per security | S14 | #697 |
| G11-6 security logo URL | row logos | later, low priority |
| cost basis from statements / currency | fewer "No cost basis" rows | #589, #527 (open) |

## Acceptance checks for the UI story

- Given one depot with 3 positions, one without cost basis, the list shows 2 gains and
  "No cost basis" for the third, and the Performance card says "Cost basis missing for 1
  position".
- Given "All depots", positions are grouped by depot, and no total return, chart or allocation
  renders while G11-1/2/4 are not delivered.
- Given a position with `valueStatus = STALE`, the row shows "Price from {date}".
- No figure on the screen is the result of client arithmetic (code review check plus a unit test
  on the view model).
