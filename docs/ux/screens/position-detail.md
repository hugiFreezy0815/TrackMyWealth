# S12 Position detail: one security across depots

**Figma** (page "🧭 Design round S9–S15 (#680–#683)"): M29 Position detail EN [193:514](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=193-514) / DE [193:884](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=193-884). Web: the right panel of W16.
**Requirements:** EPIC 15 (positions, cost basis), FR-SEC (security master data),
NFR-UX-003/004, FR-API-010.
**Rules for every screen:** see [README](README.md#rules-for-every-screen).

## Purpose

"What do I hold of this security, where, what did I pay, and where does each figure come
from?" This is a **holding** page, not a market page. The member came here about their own
money.

## Entry points

- A position row on [S11](investments.md), on S3's depot Positions tab, or an S15 issue about this
  security.
- Deep link `/investments/securities/{securityId}`; `?account={accountId}` preselects a depot.

## Layout (M29)

```
‹ Investments                                         [⋯]
[V] Vanguard FTSE All-World UCITS ETF
    ETF · Equity · IE00B3RBWM25  [⧉]
┌ YOUR HOLDING                                          ┐
│ CHF 31'204.10                                         │
│ 312.5 units · price CHF 99.85 on 31 Oct 2026          │
│ [Price from 31 Oct]                                   │
│ Unrealised gain  ↗ +CHF 4'120.30 · +15.2 %            │
│ Cost basis CHF 27'083.80   [Estimated]                │
└───────────────────────────────────────────────────────┘
Held in
┌ [SQ] Swissquote depot       250 units   CHF 24'963.30 ┐
│ [yuh] Yuh invest             62.5 units  CHF  6'240.80 │
└───────────────────────────────────────────────────────┘
Activity
OCTOBER 2026
┌ 03 Oct  Buy · 12.5 units at CHF 97.10   −CHF 1'213.75 ┐
│         Swissquote depot · fee CHF 9.00               │
│ 17 Sep  Dividend                          +CHF 41.20  │
│         Swissquote depot · tax CHF 14.42 withheld     │
└───────────────────────────────────────────────────────┘
Details  ▸   (ISIN, symbol, currency, asset class, country, data completeness)
```

- With one depot the "Held in" block is left out, and the header shows that depot's figures.
- With several depots the header shows **only figures the server sends for the whole holding**
  (G11-3). Until then it shows "Held in 2 depots" and no total. The per-depot rows carry the
  figures.

## Requests

1. `GET /api/v1/securities/{id}` → `SecurityResponse`: `displayName`, `isin`, `instrumentType`,
   `assetClass`, `denominationCurrency`, `securityCountry`, `completeness`.
2. Positions: the cached S11 responses (`GET /api/v1/accounts/{accountId}/positions` per
   depot), with the entries for `securityId = {id}` picked out. Picking entries is a lookup, not
   a computation.
3. Activity: **Gap G12-1.** Per-account transactions can be filtered by `uncategorized` only.
   A `securityId` filter (on the account list or the workspace list G9-2) is needed. Until then
   the Activity block is **hidden**.

## Fields

| # | Element | Source | Rule |
|---|---|---|---|
| 1 | Name | (1) `displayName`; `legalName` in Details | |
| 2 | Type line | (1) `instrumentType`, `assetClass`, `isin` | copy button for the ISIN (a11y label "Copy ISIN") |
| 3 | Holding value | one depot: (2) `valueInAccountCurrency`; several: G11-3 | `Amount`; status badge from `valueStatus` |
| 4 | Units and price | (2) `quantity`, `price`, `priceCurrency`, snapshot `asOf` | "price … on {asOf}" makes the price date visible |
| 5 | Unrealised gain | (2) `unrealisedGain`, `unrealisedGainPercent` | `PLChip`; null rules as in S11 |
| 6 | Cost basis | (2) `costBasis`, `costBasisEstimated` | null ⇒ "No cost basis" + "Add cost basis" (→ S15 item) |
| 7 | Held-in rows | (2) per depot | tap → S3 of that depot |
| 8 | Activity row | G12-1: `transactionType`, `tradeDate`, `quantity`, `unitPrice`, `feeAmount`, `taxWithheldAmount`, `netAmount` | "Buy · {q} units at {price}"; fee and tax as a second line |
| 9 | Data completeness `[advanced]` | (1) `completeness.missingFields[]` | "Missing: asset class, country". This explains the "Unknown" share in the allocation |

## Actions

| Action | Result |
|---|---|
| ⋯ → Import statement | [S7](import-statement.md) for the depot |
| ⋯ → Add transaction | hidden until M18 ships |
| Tap the value / gain | Explanation sheet: price source and date, cost basis source, FX |
| "Add cost basis" | [S15](data-checks.md) |

## Design decisions (vs. getquin's security page)

getquin's security page shows market data: market cap, P/E, analyst ratings, FAQ, a "Trade CFDs"
banner and a discussion tab. We leave all of that out. TrackMyWealth is a ledger of the
member's own wealth, it has no market data source, and a trading upsell does not belong in it.
Instead the page shows what getquin hides: the price date, the cost basis source, the
per-depot split, and the data that is missing about the security.

## API gaps

| Gap | Needed for | Owner story |
|---|---|---|
| G11-3 position aggregated per security | header total for several depots | see [investments.md](investments.md) |
| G12-1 transaction filter by `securityId` | Activity | #692 |
| price history per security | a price chart (later idea, not drawn) | later |

## Acceptance checks for the UI story

- Given a security held in one depot, the header shows that depot's value, units, price date
  and gain, and "Held in" is hidden.
- Given two depots, the header shows "Held in 2 depots" without a total until G11-3 ships.
- Given `costBasis = null`, the screen shows "No cost basis" and no gain.
- Given `completeness.complete = false`, Details lists the missing fields.
