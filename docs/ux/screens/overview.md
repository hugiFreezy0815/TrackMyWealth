# S1 Overview: portfolio tabs and the first tab

**Figma:** M03 Overview, net worth (21:295) · M20 Overview, empty, first run (91:2675) ·
W01 Overview, dashboard (29:124). DE mirrors: same names in the DE sections.
**Requirements:** FR-INS-013 (tabs), FR-HOU-009 / #392 (overview name), FR-NW (net worth),
FR-CON-006 (scope bar), FR-CON-007 / NFR-UX-004 (value status), FR-API-010, NFR-UX-001/003/005.
**Rules for every screen:** see [README](README.md#rules-for-every-screen).

## Purpose

The member's home screen. It answers "What am I worth, can I trust the figure, and where is
it?" on one screen. The **first tab** shows the whole workspace. The tabs after it are the
portfolios, and each one opens the [Portfolio View](portfolio-view.md).

## Entry points

- After sign-in, MFA and first-run setup, this is the start screen.
- Phone: tab bar "Overview". Web: sidebar "Overview". Deep link `/overview`; `/overview/{portfolioId}`
  opens that portfolio's tab (S2).

## The tab strip (`PortfolioTabs`)

```
[ My finances ] [PF PostFinance] [yuh Yuh] [DKB DKB] [VIAC VIAC 3a] [⌂ Personal assets] [ + Add portfolio ]
  ───────────
```

| Element | Content | Source |
|---|---|---|
| First tab label | The workspace name **exactly as typed**: no prefix, no translation, no truncation below 24 characters (then "…" with the full name as accessible label) | `GET /api/v1/workspace` → `name` |
| First tab, no name | "All portfolios" / "Alle Portfolios" (`overview.tab.allPortfolios`); never "Total", never "Gesamtvermögen", never stored as the name | `name` is `null` |
| Portfolio tabs | One per active portfolio, **in the order the server sends** (creation order) | `GET /api/v1/portfolios` → `[].name` |
| Tab icon | `InstitutionLogo` from `logoUrl`; when `null`: the house icon if `personalAssetsDefault`, else initials of `name` (Figma "Custom initials") | `logoUrl`, `personalAssetsDefault`, `name` |
| Last tab | "+ Add portfolio" / "+ Portfolio hinzufügen" (`overview.tab.addPortfolio`) → [Add portfolio (S5)](add-portfolio.md) | - |

- Phone: the strip scrolls horizontally, and the selected tab scrolls into view.
- Web: the strip wraps to a second line instead of scrolling.
- The word "workspace" never appears.
- Selecting a tab never reloads the first tab's data. Each tab keeps its own cache for the session.
- Accessibility: the strip has `role=tablist`, each tab has `role=tab` with `aria-selected`, and
  the arrow keys move between tabs on web.

## First tab: layout

### Phone (M03)

```
Good evening                                   [🔔] [+]
Overview
[ My finances ][ PostFinance ][ Yuh ][ DKB ] …
[≡ All accounts · CHF ⌄]                 (🕓 As of 20 Oct)
⚠ 1 account differs from its statement · Review
┌ NET WORTH                                     (👁) ┐
│ CHF 478'160.56            [Estimated]              │
│ (↗ +CHF 6'842.10 · +1.45%) past month              │   ← HISTORY API (#477; frontend integration pending)
│ Net saving +CHF 3'635.70 · Market & FX +CHF 3'206.40│   ← HISTORY API (#477; frontend integration pending)
│ [ value chart ]                                     │   ← HISTORY API (#477; frontend integration pending)
│ ( 1W )( 1M )( YTD )( 1Y )( Max )                    │   ← HISTORY API (#477; frontend integration pending)
└─────────────────────────────────────────────────────┘
┌ Assets                                  Liabilities ┐
│ CHF 1'079'362.92                    CHF 601'202.36  │
│ ████████████████████████████░░░░░░░░░░░░░░░░░░░░    │
│ Includes Mortgage (CHF 600'000.00) and DKB Visa…    │
└─────────────────────────────────────────────────────┘
┌ Cash & savings ┐ ┌ Depot        ┐                     ← Section breakdown delivered (#481)
┌ Pillar 3a      ┐ ┌ Property & mortgage ┐
Portfolios                                    See all
┌ [PF] PostFinance       CHF 64'820.40  > ┐
│      3 accounts · CHF          +0.9%    │   ← change: HISTORY API (#477; frontend integration pending)
│ [DKB] DKB              EUR 76'856.45  > │
│      4 accounts · EUR   ≈ CHF 71'953.01 │
└─────────────────────────────────────────┘
October cash flow                              Details
┌ Income   Spending   Saved ┐
```

### Web (W01)

- Sidebar on the left. The header holds the title, Search (hidden in sprint 7),
  "Import statement" (→ S7 with the account picker), "Add account" (→ S6 with a portfolio picker) and the bell (hidden).
- Main column: the net-worth card, then Assets/Liabilities, then the asset-class tiles in one
  row of four.
- Right column (360 px): the Portfolios list, then the cash-flow card.
- Below 1024 px, the layout falls back to the phone order in one column.

## Fields

All amounts use `Amount`. *Cur* = the currency chosen in the scope bar; it defaults to the
workspace currency (`GET /api/v1/workspace` → `currency`).

| # | Element | Label key (EN / DE) | Format | Source: endpoint → field |
|---|---|---|---|---|
| 1 | Greeting | `overview.greeting.{morning,afternoon,evening}` "Good evening" / "Guten Abend" | by device time | none: the user's name is not in `/users/me` (only `userId, role, language`), so **no name** in sprint 7 |
| 2 | Title | `nav.overview` "Overview" / "Übersicht" | | |
| 3 | Scope bar | `overview.scope` "All accounts · {currency}" | | *Cur*; account scoping is not offered by `/net-worth`, so the menu offers **only the currency** in sprint 7 (workspace currency first, then CHF, EUR, USD, GBP) |
| 4 | As-of chip | `overview.asOf` "As of {date}" / "Stand {date}" | short date | `GET /api/v1/net-worth?currency=Cur` → `asOf` |
| 5 | Data status banner | `overview.dataStatus.unreconciled` (`count`) + `overview.warning.review` | | `GET /api/v1/accounts` → count of items with `valueStatus = UNRECONCILED` (a count of rows, not a money figure). Hidden when 0 |
| 6 | Net worth | `overview.netWorth` "Net worth" / "Nettovermögen" | `Amount`, large | `/net-worth` → `netWorth`, `currency` |
| 7 | Net worth status | `ValueStatusBadge` | | `/net-worth` → `approximate` ⇒ Estimated; `complete=false` ⇒ "Not all values are included"; `warnings[]` listed in the explanation sheet |
| 8 | Change chip + period label | `overview.change.pastMonth` "past month" / "letzter Monat" | `ChangeChip`: sign, amount, % | `GET /api/v1/net-worth/history?period=1M&currency=Cur` → `change.amount`, `change.percent` for the selected period. Hidden until then |
| 9 | Change split | `overview.change.split` "Net saving {saving} · Market & FX {market}" | | `GET /api/v1/net-worth/history?period=1M&currency=Cur` → `change.contributions`, `change.marketAndFx`, `change.capitalMovements` |
| 10 | Value chart | text alternative `overview.chart.a11y` "Net worth from {start} to {end}, {change}" | line chart, estimated stretches dashed | `GET /api/v1/net-worth/history?period=1M&currency=Cur` → `points[]` (`date`, `value`, `estimated`) |
| 11 | Period switch | `period.1W/1M/YTD/1Y/MAX` | segmented control; default 1M; the choice is remembered on the device | `GET /api/v1/net-worth/history` → `period` parameter |
| 12 | Assets | `overview.assets` "Assets" / "Vermögen" | `Amount` | `/net-worth` → `totalAssets` |
| 13 | Liabilities | `overview.liabilities` "Liabilities" / "Verbindlichkeiten" | `Amount`, loss colour, shown as sent (no sign added) | `/net-worth` → `totalLiabilities`: the server sends the **magnitude** of what is owed (positive; PO 2026-11-09, `AccountValuation`/DM-12). The label "Liabilities" carries the meaning, so the app adds no minus. Only an overpaid liability makes it negative, and then the server's minus shows |
| 14 | Assets/liabilities bar | decorative (`aria-hidden`), the amounts above carry the meaning | bar split in the ratio of the two amounts | 12, 13. Visual proportion only; no figure is shown from it |
| 15 | "Includes …" line | `overview.liabilities.includesOne` (`first`), `overview.liabilities.includes` (`first`, `second`), or `overview.liabilities.includesMore` (`first`, `second`, `count`) | names + `Amount` in the account's `currency`, shown as sent (magnitudes, like #13) | `/net-worth` → `accounts[]` with `nature = LIABILITY` and `valueKnown`, the two largest by absolute `value` first. Hidden when there are no liabilities |
| 16 | Section tiles | `overview.sections.*` (same section terminology as the Portfolio View) (Cash & savings, Pillar 3a, Pension, Depot, Crypto, Credit card, Property & mortgage, Other) | `Amount` + change for the selected period | **Delivered (#481)** → `/net-worth` `sections[]` (`section`, `subtotal`, `valueStatus`, `complete`) in server order; change from **HISTORY API (#477; frontend integration pending)** `sections[].change`. The section subtotal is available with #481; the change line remains hidden until #477. Only sections the server sends are shown. Phone: 2 columns (M03, PO 2026-11-09); web: 4 per row when each tile gets at least 160 px, else 2. The figure stays on one line: when it would not fit at 16 px it steps down in size (to 11 px at least), it never wraps or truncates |
| 17 | Portfolios header | `overview.portfolios` "Portfolios" + "See all" | | "See all" → Portfolios tab (#376). Hidden if that tab isn't built in sprint 7 |
| 18 | Portfolio row: logo, name | | | `GET /api/v1/portfolios` → `logoUrl`, `name` |
| 19 | Portfolio row: subtitle | `overview.portfolio.subtitle` "{count, plural, one {# account} other {# accounts}} · {currency}" | | `activeAccountCount`, `portfolioCurrency`; personal-assets default: `overview.portfolio.personalAssets` "Property, vehicles and more" |
| 20 | Portfolio row: total | | `Amount` in portfolio currency | `total`, `portfolioCurrency` |
| 21 | Portfolio row: converted total | `Amount` with `approximate` (spoken key `amount.a11y` with `approx`, `sign`, `amount`, `currency`) | `Amount`, secondary | `totalInWorkspaceCurrency`, `workspaceCurrency`; **only** when `portfolioCurrency ≠ workspaceCurrency` |
| 22 | Portfolio row: status / change | `ValueStatusBadge` if not `CURRENT`, else change % | | `valueStatus`, `complete`; change from **HISTORY API (#477; frontend integration pending)** (`portfolios[].change.percent`) |
| 23 | Cash-flow card title | `overview.cashFlow.title` "{month} cash flow" / "Cashflow {month}" | month name | current month |
| 24 | Income / Spending / Saved | `overview.cashFlow.income`, `overview.cashFlow.spending`, `overview.cashFlow.saving` | `Amount` | `GET /api/v1/cash-flow?month=YYYY-MM&currency=Cur` → the entry of `income[]`, `spending[]`, `saving[]` whose `currency` = *Cur*; `valueKnown=false` ⇒ "–" |
| 25 | Pending review | `overview.cashFlow.pendingReview` "{amount} still to review" | | `pendingReview[]` entry for *Cur*; hidden when zero or missing |
| 26 | Cash flow incomplete | `total.incomplete` | | `complete=false` |
| 27 | Details link | "Details" (design only) | | → Cash flow tab (M10). Hidden if not built in sprint 7 |
| 28 | Eye icon (hide amounts) | `privacy.hideAmounts` and `privacy.showAmounts` | toggle | device setting (FR-CLI-005) |

Rows 8–11 describe the pending #477 frontend integration. Their keys are design proposals.

## Requests and loading order

Fire these in parallel. Each card renders as its own data arrives, with a skeleton until then:

1. `GET /api/v1/workspace`: tab label and default currency. **Blocking for the tab strip only.**
2. `GET /api/v1/portfolios`: tabs and the portfolio list.
3. `GET /api/v1/net-worth?currency=Cur`: net-worth and assets/liabilities cards.
4. `GET /api/v1/accounts`: only to count `UNRECONCILED` for the data-status banner.
5. `GET /api/v1/cash-flow?month=…&currency=Cur`: cash-flow card.
6. `GET /api/v1/net-worth/history?period=1M&currency=Cur` is implemented on the backend by
   #477. Frontend rendering of the chart and period selector is still pending. Section breakdown
   (#481) is included in request 3.

Changing the scope currency reloads 3, 5 and 6. Pull-to-refresh (phone) or the browser reload
(web) reloads everything. After the member returns from an import, commit, rollback or account
change, reload everything.

## States

| State | Rule |
|---|---|
| Empty: no portfolio yet (M20) | `/portfolios` returns only the Personal assets default with 0 accounts, or nothing at all, **and** `/net-worth` lists no account. The tab strip shows the first tab (with the name or "All portfolios") and "+ Add". The net-worth card says "No figures yet" / "Add a portfolio to see your net worth. Nothing is estimated before your first data arrives." Below it: the "Get started · about 10 minutes" card with 3 steps and the primary button "Add your first portfolio". |
| Portfolios but no accounts | Net worth shows `Amount` 0.00 from the server (not "No figures yet"), with the hint `overview.noAccounts` "Add an account to a portfolio to see figures." |
| Loading, refreshing, offline, error | See the README. Each card fails on its own: a cash-flow error does not hide net worth. |
| Estimated (`approximate=true`) | Badge "Estimated" next to net worth; the explanation sheet lists `warnings[]` through the glossary (#379). |
| Incomplete (`complete=false`) | "Not all values are included", tap for the list of accounts with `valueKnown=false`. |
| Stale / unreconciled accounts | Banner #5 for unreconciled. Stale shows on the account rows (S2), not on the overview. |
| Negative net worth | Shown with a minus sign and loss colour. Never hidden, never shown as 0 (FR-NAV-15). |
| Archived accounts | Not included; the server leaves them out. |
| Many portfolios (> 8) | The strip scrolls; the list shows all of them (no paging, the list is bounded). |
| Hidden amounts | Every `Amount` shows "••••". The chart keeps its shape but loses its axis labels. |

## Actions

| Action | Result | Request |
|---|---|---|
| Tap a portfolio tab or row | Opens S2 for that portfolio | - |
| "+ Add portfolio" tab / "Add your first portfolio" | [Add portfolio (S5)](add-portfolio.md), then [Add account (S6)](add-account.md) | `POST /api/v1/portfolios`, `POST /api/v1/accounts` |
| Scope bar → currency | Reloads the figures in that currency | see above |
| Period switch | Reloads the chart and change | HISTORY API (#477; frontend integration pending) |
| "Review" in the data-status banner | 1 account: S3 for that account. More: a sheet listing those accounts, each opening S3 | - |
| Tap net worth / Assets / Liabilities | Explanation sheet (NFR-UX-003): what is included, `asOf`, currency, `approximate`, `warnings[]`, accounts with unknown value | data already loaded |
| Eye icon | Hides or shows all amounts app-wide | - |

## Overview name: first-run setup and Settings

The first tab's label is the workspace name. It is set in two places. Neither shows the word
"workspace".

### First-run setup step (M02 "Onboarding: overview name & currency", W10)

```
Name your overview
What should the first tab be called?
┌──────────────────────────────────────────┐
│ All portfolios                           │   ← placeholder
└──────────────────────────────────────────┘
Optional. You can change it any time in Settings.
Currency: [ CHF ⌄ ]
                          [ Continue ]
```

| Field | Label key | Rule | Request |
|---|---|---|---|
| Name | `setup.overviewName.label` "Name of your overview" / "Name deiner Übersicht"; placeholder `overview.tab.allPortfolios` | Optional. At most **60 characters**. Leading and trailing spaces are dropped by the server. Empty means no name, and the tab then shows the fallback. Characters the server refuses come back as `VALIDATION_FAILED` with `errors[].field = name` | `PUT /api/v1/workspace/name` `{ "name": "…" or null }` with `If-Match` from `GET /api/v1/workspace` |
| Currency | `setup.currency.label` | | `PUT /api/v1/workspace/currency` (#224) |

The live preview shows the first tab with the typed text, or the fallback while the field is
empty. "Continue" sends the name only if the member typed one.

### Settings (M13 "More: settings & members", W09)

The "Overview" group has a **Name** row: `settings.overviewName` "Name of your overview" /
"Name deiner Übersicht". It shows the current name, or the placeholder in grey. Tapping it
opens an edit sheet with the same field. The sheet has **Save** and **Clear name**; "Clear name"
sends `{ "name": null }`.

- On success the response's `name` and `version` replace the cached workspace, and the first
  tab updates right away.
- `VERSION_CONFLICT`: the conflict dialog. It reloads, shows the other member's name, and the
  typed text stays in the field.

## Differences from Figma

| Figma | Spec | Why |
|---|---|---|
| Liabilities "−CHF 601'202.36", "Mortgage (−CHF 600'000.00)" | "CHF 601'202.36" in the loss colour, "Mortgage (CHF 600'000.00)" | `/net-worth` sends liabilities as magnitudes; the app shows amounts as sent and never adds a sign (FR-API-010). PO 2026-11-09 |
| "Good evening, Anna" | "Good evening" | `/users/me` has no display name |
| "Prices 20 Oct, 17:35" | "As of 20 Oct" | `/net-worth` sends `asOf` as a date |
| Scope "All accounts · CHF" with an account filter | Currency only | `/net-worth` has no account scope |
| Bell, Search, Import/Add buttons (W01) | Hidden | Not in sprint 7 |
| Chart, change chip, portfolio % | Hidden until HISTORY API (#477; frontend integration pending) merges; class tiles use the delivered /net-worth sections (#481) | Rule 1 |
| Tiles "Investments", "Pension (3a)", "Real estate" (gross CHF 850'000) | Portfolio View section names: "Depot", "Pillar 3a", "Property & mortgage" (net of the mortgage) | One section vocabulary across the overview and the portfolio tabs (#481); the gross property value stays visible in Assets |
| Tile change "this month" | Change for the selected chart period | One period control for the whole card set (#477) |

## Acceptance checks for the UI story

- Given the workspace name "Family Hug", the first tab reads "Family Hug". Given no name, it
  reads "All portfolios" (EN) or "Alle Portfolios" (DE).
- Given `/net-worth` with `approximate=true`, the "Estimated" badge shows. Its accessible label
  reads "Net worth, 478160.56 Swiss francs, estimated" (spoken labels have no group
  separators, UX-02 section 3); the card shows "CHF 478'160.56".
- Given a portfolio in EUR and a workspace in CHF, its row shows the EUR total and "≈ CHF …".
  Given both in CHF, the "≈" line does not show.
- Given a cash-flow request that fails, the net-worth card still shows and the cash-flow card
  shows Retry.
- The app never calls an endpoint that does not exist yet. A GAP element is built once its
  operation is in `schema.d.ts` (after `npm run api:types`). Until then it is not rendered;
  the app never probes for a 404 at runtime.

## Historical value API status (#477)

The backend implements `GET /api/v1/net-worth/history?period={1W|1M|YTD|1Y|MAX}&currency=Cur`,
with server-sampled dated values, `points[]`, `change`, `sections[]`, `complete` and `warnings[]`.
The current-day point is included. The chart, change labels and period selector remain a **frontend
integration task**, not a claim that this UX has shipped.

`change.contributions` reports booked external net contributions in the requested currency.
`change.capitalMovements` reports changes from account inception and lifecycle inclusions
(such as archiving). `change.marketAndFx` is the remaining signed value change after
contributions and capital movements. These fields are nullable when attribution is incomplete.
Pending-review transfers, unresolved settlements or loan repayments, missing historical FX rates,
unknown valuations, and migrated accounts without reconstructable earlier lifecycle events must
be explained through `complete=false` and `warnings[]`; never display a missing attribution
as zero or label it a market gain.

For historical values, an account is included according to its effective state at each
business-date sample. Data from before lifecycle-event tracking may be restated by the account's
first available state and explicitly warned as incomplete. The section change and period total
are calculated on the backend; the client must never compute financial returns by subtracting
two local snapshots.
