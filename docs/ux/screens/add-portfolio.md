# S5 Add portfolio

**Figma:** M14 Add portfolio, choose institution (72:2426) · W11 Add portfolio, institution
dialog (73:2623). DE mirrors in the DE sections.
**Requirements:** FR-INS-001 (the member names the portfolio; the catalogue name is the
default), FR-INS-013 (a new tab per portfolio), FR-NAV-10 (catalogue capabilities), FR-NAV-16
(Personal assets exists by default), FR-ADM-001/009 (portfolio-count limit), NFR-UX-005
(first run under 10 minutes).
**Rules for every screen:** see [README](README.md#rules-for-every-screen).

## Purpose

Creates a portfolio, the container behind a new overview tab. The member usually picks the real
bank or broker from the catalogue, which brings its logo and import templates. They may also
create a portfolio without one.

## Entry points

- "+ Add portfolio" at the end of the tab strip (S1).
- "Add your first portfolio" on the empty overview (M20). This is the **first step of the
  first-run journey**.
- Phone: a pushed screen. Web: a modal dialog (W11).

## Layout

### Phone (M14)

```
‹ Add portfolio
Step 1 of 2                        ← step 2 = Add account (S6)
Where is it held?
Pick the bank or provider. Its logo and import templates come with it.
[🔍 Search banks, brokers, 3a providers        ]
( All )( Switzerland )( Germany )                 ← country chips: GAP-CATALOGUE (#484)
SWITZERLAND
┌ [PF] PostFinance                         > ┐
│      Bank · CSV · PDF · CAMT               │   ← formats: GAP-CATALOGUE (#484)
│ …                                          │
GERMANY
│ [DKB] DKB                               ✓  │
└────────────────────────────────────────────┘
+ Not in the list? Create your own
────────── sticky bottom sheet once an entry is picked ──────────
New tab:  [DKB] DKB
Portfolio name    [ DKB                     ]
Portfolio currency[ EUR · Euro            ⌄ ]
                 [ Continue ]
```

### Web (W11)

A dialog 720 px wide. The catalogue is a 3-column grid of cards, and the name, currency and tab
preview sit in one row above "Cancel" and "Create portfolio".

## Requests

| Step | Request |
|---|---|
| Load and search the catalogue | `GET /api/v1/institutions/catalogue?query={q}&page=0&size=50&sort=name,asc` → `PageResponse<InstitutionCatalogueEntrySummaryResponse>` (`id`, `name`, `country`, `institutionType`, `identifier`, `logoUrl`). Search is debounced by 300 ms; an empty query lists everything. Infinite scroll uses the paging rules |
| Country chips | Delivered by #484: the server filters countries before paging and computes the filtered total; the chips use the country parameter. |
| Supported formats line | Delivered by #484: `importFormats` (CSV, PDF, CAMT, SPREADSHEET) per entry, sorted by the server. |
| Create | `POST /api/v1/portfolios` with `{ catalogueInstitutionId, name, portfolioCurrency }`; or for "create your own": `{ name, country, institutionType, portfolioCurrency }` → 201 `PortfolioResponse` (+ `Location`, `ETag`) |

## Fields

| Field | Label key (EN / DE draft) | Rule | Request field |
|---|---|---|---|
| Search | `addPortfolio.search` "Search banks, brokers, 3a providers" / "Banken, Broker, 3a-Anbieter suchen" | free text | `query` |
| Catalogue row | name, logo (`InstitutionLogo`), "{type} · {formats}" | tap selects. The selected row shows ✓ and opens the bottom sheet | |
| New-tab preview | `addPortfolio.newTab` "New tab:" | shows the logo and the typed name exactly as the tab will look | |
| Portfolio name | `addPortfolio.name` "Portfolio name" / "Name des Portfolios" | Prefilled with the catalogue name. **Required** without a catalogue entry. At most 100 characters. The member may rename it ("DKB Gemeinschaftskonto") | `name` |
| Portfolio currency | `addPortfolio.currency` "Portfolio currency" / "Portfoliowährung" | Picker of ISO currencies. Prefilled with the country's currency (CH → CHF, DE → EUR, otherwise the workspace currency). This is a convenience default, not a figure. Help text: "Totals of this tab are shown in this currency." | `portfolioCurrency` |
| Create your own: name, country, type | `addPortfolio.own.*` | Name required. Country is an ISO-2 picker. Type is one of `BANK, BROKER, PENSION_PROVIDER, PENSION_FUND, ASSET_MANAGER, CARD_ISSUER, CRYPTO_EXCHANGE, INSURER, PLATFORM, OTHER` with labels `institutionType.{code}` | `name`, `country`, `institutionType` |

There is no "Personal assets" entry: that portfolio exists by default (FR-NAV-16). W11's
"Personal assets" card is dropped.

## After creating

1. Insert the new portfolio into the cached `/portfolios` list (or reload it). The new tab
   appears in the strip and is selected.
2. Go straight on to **Add account (S6)** for this portfolio ("Step 2 of 2"). Back from S6
   lands on the new, empty Portfolio View (S2 empty state).

## States and errors

| Case | Treatment |
|---|---|
| Catalogue loading / failed | Skeleton rows / `ErrorBanner` with Retry. "Create your own" always works |
| No search hits | "No match for "{q}"." + "Create your own" pre-filled with the query as the name |
| `VALIDATION_FAILED` | Marks the field from `errors[].field` (`name`, `country`, …) |
| Portfolio limit reached (FR-ADM-009; the problem code arrives with the admin features story) | M24 "Limit reached" card instead of the button. Until the code exists, a 409 `CONFLICT` shows its catalogue message |
| Offline | Create is disabled with the reason. A typed name is kept |
| Double tap | The button is disabled while the request runs; one `POST` only |

## Accessibility

- The catalogue list is a radio group; the selected entry is announced as "selected".
- The bottom sheet takes focus when it opens.
- Logos are decorative (the name is the label).

## Differences from Figma

| Figma | Spec | Why |
|---|---|---|
| "Bank · CSV · PDF · CAMT" and country chips | Delivered by #484 | the catalogue now exposes formats and a server-side country filter |
| W11 "Personal assets" card | dropped | it exists by default |

## Acceptance checks for the UI story

- Given the member picks DKB and keeps the name, then `POST /portfolios` sends
  `catalogueInstitutionId` and `name: "DKB"`, the "DKB" tab appears selected, and S6 opens.
- Given "Create your own" with an empty name, then Create is disabled and nothing is sent.
- Given the search "post", then the list reloads from page 0 with `query=post`.
