# Screen specifications: sprint 7 (UX-02, #377)

These specs describe seven primary workflow screens plus the blocking update-required system
state for sprint 7 (2026-11-10 to 2026-11-23). Workflow specs list fields, format, endpoint
sources, states, actions and accessibility rules. The update-required spec documents platform
variants, localized copy, Figma node IDs and recovery behavior.

| # | Screen | Spec | Figma (EN / DE) |
|---|---|---|---|
| S1 | Overview: portfolio tabs and the first tab (whole workspace), plus the overview name in setup and Settings | [overview.md](overview.md) | M03, M20, M02, M13 / W01, W09 |
| S2 | Portfolio View: one tab per portfolio, one section per account type | [portfolio-view.md](portfolio-view.md) | M05, M06 / W02, W03 |
| S3 | Account detail with its paged transaction list | [account-detail.md](account-detail.md) | **M25** (new: EN 115:2999, DE 115:3443), M10 / W06 |
| S4 | Transaction detail with category change and change history | [transaction-detail.md](transaction-detail.md) | M21 / W06 side panel |
| S5 | Add portfolio (catalogue or own) | [add-portfolio.md](add-portfolio.md) | M14 / W11 |
| S6 | Add account and its starting value (opening balance or valuation) | [add-account.md](add-account.md) | M17, M16 / dialog like W11 |
| S7 | Import statement: file, review with duplicates, commit | [import-statement.md](import-statement.md) | M08, M09 / W05 |
| S8 | Update required: blocking native and web system state (EN/DE, light/dark) | [update-required.md](update-required.md) | M24 / W14 (desktop + phone) |

### Next design round (planned, 2026-11-22)

These specs come from the PO's getquin screenshots of 2026-11-22. getquin served as inspiration only:
each spec has a "Design decisions" table that says what TrackMyWealth does differently. They are
**planned**: nothing here is in sprint 7, and the "hidden until the API sends it" rule applies
to every element.

| # | Screen | Spec | Figma (EN / DE, to be drawn) |
|---|---|---|---|
| S9 | Section view: one account type (cash, cards, mortgage…) across all portfolios | [section-view.md](section-view.md) | M26 / W15 |
| S10 | Liability detail: loan schedule, repayments split, card statement (variant of S3) | [loan-detail.md](loan-detail.md) | M27, M27b / W06 |
| S11 | Investments: depots, positions, performance card, allocation, dividends card | [investments.md](investments.md) | M28 / W16 |
| S12 | Position detail: one security across depots | [position-detail.md](position-detail.md) | M29 / W16 panel |
| S13 | Performance breakdown sheet | [investments.md](investments.md#s13-performance-sheet-m30) | M30 / W16 rail |
| S14 | Dividends & interest | [investments.md](investments.md#s14-dividends--interest-m31) | M31 / W16 |
| S15 | Data checks inbox and units check | [data-checks.md](data-checks.md) | M35, M36 / W18 |
| S7 v2 | Import: several files, column mapping (no LLM), editable rows, trades table | [import-statement.md](import-statement.md#version-2-design-round-2026-11-22) | M32–M34 / W17 |
| S1/S2/S3 | Planned updates: section links, By type switch, masked identifiers, month groups | "Planned update" sections in [overview.md](overview.md), [portfolio-view.md](portfolio-view.md), [account-detail.md](account-detail.md) | M03, M05, M25 / W01, W02, W06 revised |

API gaps of the planned screens and their stories. UI stories: #684 (S9), #685 (S10), #686 (S11/S13), #687 (S12), #688 (S7 v2), #689 (S15), #690 (S3 update). Figma: #680–#683 (sprint 8).

| Gap | Screens |
|---|---|
| G9-1 accounts by section with masked identifier and converted value (#691) | S9, S1 U2 |
| G9-2 workspace transaction list with section / account / type / security filters (#692) | S9, S11, S12, S14 |
| G10-1/2 payment total on schedule entries; interest and principal per year (#693) | S10 |
| G11-1 depot value history (#694); G11-2 performance breakdown (#695); G11-3 positions per security and G11-4 allocation (#696); G11-5 income per month (#697); G11-6 security logos (later) | S11–S14 |
| G12-1 transaction filter by security (#692) | S12 |
| G7-1 upload without an account (#365); G7-2 member templates and G7-4 row correction (#698); G7-3 trade rows (#274) | S7 v2 |
| G15-1 data-checks endpoint and G15-3 snapshot confirmation (#699); G15-2 single cost basis write (optional, with #589) | S15 |

**Sprint-7 scope (PO decision 2026-11-05).** S1–S7 are in. They cover the first-run journey:
add a portfolio, add an account, import a statement or enter an opening balance, see the
overview. Not in sprint 7 (hidden, never disabled): edit/correct a transaction, add a
transaction by hand, removed transactions and restore, import history and rollback (M19),
holdings import (#367), notifications, search, the Cash flow and Investments tabs.

Figma file: <https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth>. The mockups
show the intended look. Where a mockup and a spec disagree, **the spec wins**: the mockups
were drawn before the API existed, and some details (for example "2.25% p.a.") have no
data source. Every such difference is listed under "Differences from Figma" in the spec.

## Inputs and assumptions

- API contract: [`api/openapi.yaml`](../../../api/openapi.yaml) on `main` (c09973e9).
  Types come from `mobile/src/api/schema.d.ts` (`npm run api:types`). Error handling follows
  [`docs/api/error-codes.md`](../../api/error-codes.md). Paging and the refresh rule follow
  [`docs/api/conventions.md`](../../api/conventions.md).
- **#376 (information architecture) is PO-approved as the Sprint-7 navigation and
  onboarding baseline (2026-11-05):** see
  [UX-01 information architecture](../01-information-architecture.md).
  Mobile navigation is **Overview + More**, desktop sidebar navigation is **Overview +
  Settings**, and portfolio tabs live **inside** Overview; there is no duplicate
  top-level Portfolios view. Investments and Cash Flow navigation entries are hidden
  until their full screens are implemented; never show disabled tabs or placeholders.
  S1's cash-flow teaser and S2's depot sections remain visible. Viewport breakpoints
  are <600px phone, 600–1023px tablet, and ≥1024px desktop, with Figma's 248px sidebar
  and a nominal 360px context rail. The optional overview name falls back to
  All portfolios / Alle Portfolios. Onboarding offers statement import or an
  applicable manual opening balance / valuation and targets **under ten minutes**,
  subject to measurement in Sprint 7. **ADR 0007 (#384) is Accepted** (Option B):
  web uses a semantic React DOM table, native uses a React Native list. Its accepted residual
  accessibility risks are production acceptance requirements for #494.
- **#378 (visual foundations):** [UX-03 component/token contract](../02-visual-foundations.md)
  defines the proposed tokens, accessible value-status treatments, currency formatting, chart
  renderer and all S1–S7 component inputs/states. It is pending independent review and PO approval
  until #378 is closed; its [light/dark gallery](../02-visual-foundations.html) is a static
  reference, not product code. The component map below remains the per-screen source of truth.
- **#379 (glossary and copy):** use the [UX-04 glossary and app catalogues](../03-glossary.md)
  for final wording. German addresses the member with **du** (PO decision 2026-11-05).

## Rules for every screen

1. **No figure is computed on the client (FR-API-010).** The app shows, formats and sorts what
   the server sends. It never adds, subtracts, converts or derives a percentage. Where a screen
   needs a figure the API does not send yet, the spec names the story that adds it. Until that
   story merges, the element is **hidden**: no placeholder, no client-side workaround.
2. **Amounts are strings.** Every `format: decimal` field is a `DecimalString`. It is formatted
   for display only, in one format for every UI language (PO 2026-11-09: apostrophe group,
   decimal point, e.g. `1'234.56`, `1'111'111.00`; see UX-02 section 3); the currency
   code comes before the number; a negative sign is always shown, never only red colour).
3. **Value status (NFR-UX-004).** Every value that has a `valueStatus` shows the
   `ValueStatusBadge` for any status other than `CURRENT`. A total uses the status the
   server sends for that total, never one worked out on the client.

   | `valueStatus` | Badge (EN / DE draft) | Key | Explanation (accessible text, tap for more) |
   |---|---|---|---|
   | `CURRENT` | none | - | - |
   | `ESTIMATED` | Estimated / Geschätzt | `valueStatus.estimated` | "This value rests on an approximation, for example a rate or transactions before the opening balance." |
   | `STALE` | Price from {date} / Kurs vom {date} | `valueStatus.stale` | "The newest statement, valuation or exchange rate behind this value is older than usual." Uses `valueSourceDate` when sent. |
   | `UNRECONCILED` | Differs from statement / Weicht vom Auszug ab | `valueStatus.unreconciled` | "The ledger and the bank statement disagree. Review the difference." Links to reconciliation (not in sprint 7: the link is hidden). |
   | `UNKNOWN` | Value unknown / Wert unbekannt | `valueStatus.unknown` | The amount is shown as "–", never as 0. |
   | unknown enum value | as `UNKNOWN` | - | The app handles unknown enum values safely (#452/#469). |

4. **`complete: false`** on any total shows the line "Not all values are included" / "Nicht alle
   Werte sind enthalten" (`total.incomplete`) under the total, and the badge's explanation
   lists the warnings.
5. **Optimistic concurrency.** Every changing request sends `If-Match` with the ETag of the
   version it read (strong ETag `"7"`, also available as `version` in the body). On
   `VERSION_CONFLICT` (412/409) the app shows the version-conflict state from M24
   ("This … was changed meanwhile" + "Review and save again" / "Discard my edit"), reloads and
   keeps the user's input. On `VERSION_REQUIRED` (428) it reloads and retries once.
6. **Errors** are handled by `code` with the reaction from the error catalogue: `RELOAD`,
   `RELOAD_AND_RETRY`, `REAUTHENTICATE` and so on. An unknown code is handled by its status.
   `NOT_FOUND` on a detail screen shows "This item no longer exists or is not available to you"
   with a back action. It never leaves a blank screen.
7. **Paged lists** use `size=50`. When the user changes a filter or sort, or after an import,
   commit, rollback or any change that affects the list, the app drops the loaded pages and
   reloads from `page=0` (conventions.md, "refresh after imports").
8. **Shared states (M24).** Every screen shows:

   | State | When | Treatment |
   |---|---|---|
   | Loading | first load | `LoadingSkeleton` in the shape of the content; nothing that looks like a value |
   | Refreshing | pull-to-refresh, or a reload after a change | content stays, with a small spinner in the header |
   | Offline | no connection | M24 offline banner; the last loaded data stays visible with "Last updated {time}"; changing actions are disabled with the reason as accessible text |
   | Error | a request fails | `ErrorBanner` with the message key of the `code` and Retry; the parts that did load stay visible |
   | Recalculating | after an import (FR-JOB-004, when the server says so) | M24 blue banner; not sent by the API in sprint 7, so not built |

9. **Authorization.** Every workspace member has full access (#389). There is no read-only
   state and no owner field. Admin screens are not part of these specs.
10. **Accessibility (WCAG 2.2 AA).** Touch targets are at least 44×44 pt. Focus moves top to
    bottom, left to right. Every amount has an accessible label that reads the sign and the
    currency in words ("minus 1,284.30 euros"). A badge's colour always has a text or icon next to
    it. Charts have a text alternative (start value, end value, change). Dynamic Type and
    browser zoom to 200 % must not cut off amounts: long amounts wrap below the label.
11. **Screen protection (FR-CLI-005).** All four screens show financial data:
    - In the app switcher, the app shows a blurred cover.
    - The eye icon on the overview (M03) hides every amount on every screen ("••••"). The
      setting is stored on the device, and screen readers read "hidden".
12. **Beginner and advanced view (NFR-UX-001).** In sprint 7 the advanced view adds technical
    detail only: FX rate and date, value basis, bank code and source. Each spec marks such fields
    `[advanced]`. Until the setting exists (#376), they show behind an "Details" disclosure.
13. **Explainability (NFR-UX-003).** Every key figure (net worth, portfolio total, section
    subtotal, account value) opens a short explanation sheet: what it includes, as of which
    date, at which FX rate, and its value status. All of it comes from the response fields
    named in the spec.

## Gaps and their owner stories

The PO decided on 2026-11-05 to put every gap below into sprint 7 (label `sprint-7`,
Iteration 7). Each UI element stays hidden until its story merges, so the UI stories never wait
on the backend.

| Gap | Screen | Owner story |
|---|---|---|
| Net-worth and portfolio value history (chart), period change, contribution vs. market/FX split, per-portfolio and per-class change | S1, S2 | #477 (US-11-06) |
| Net worth broken down by the Portfolio View's sections (Cash & savings, Depot, Pillar 3a, Property & mortgage…) | S1 | #481 (US-11-05) |
| Portfolio View: masked IBAN/card number, reconciliation state per account, card statement date, institution name, FX rate, last import per portfolio | S2, S3 | #482 (US-04-09) |
| Depot positions (name, ISIN, units, value, unrealised gain) | S2 | #478 (US-15-05) |
| Single-transaction read (deep link), portfolio name, counter-account name, bank code, import batch, amount in account currency | S3, S4 | #479 (US-07-37) |
| Transaction change history | S4 | #480 (US-07-38) |
| Catalogue: import formats per institution, country filter | S5 | #484 (US-04-10) |
| Interest rate on a savings account ("2.25% p.a.") | S2 | not in the MVP data model: dropped from the screen |
| Allocation and Performance sub-tabs of a depot | S2 | EPIC 16/17: later, tabs not shown |
| "Always categorise as…" merchant rule from the category picker (W06) | S4 | categorisation rules exist in the API but are not in sprint 7's screens; later story |

## Component use

| Component (#378) | S1 | S2 | S3 | S4 |
|---|---|---|---|---|
| `PortfolioTabs` (Figma component 71:223) | ✓ | ✓ | | |
| `Amount` | ✓ | ✓ | ✓ | ✓ |
| `ValueStatusBadge` | ✓ | ✓ | ✓ | |
| `ChangeChip` (↗ +CHF 6'842.10 · +1.45%) | ✓ | ✓ | | |
| `ValueChart` + `PeriodSwitch` | ✓ | ✓ | | |
| `SectionCard` | ✓ | ✓ | | |
| `AccountRow` | ✓ | ✓ | | |
| `PositionRow` | | ✓ | | |
| `TransactionRow` | | | ✓ | |
| `FilterChips` | | | ✓ | |
| `CategoryPicker` (sheet on phone, side panel on web) | | | ✓ | ✓ |
| `KeyValueList` | | | | ✓ |
| `HistoryTimeline` | | | | ✓ |
| `EmptyState`, `ErrorBanner`, `LoadingSkeleton`, `OfflineBanner`, `ConflictDialog` | ✓ | ✓ | ✓ | ✓ |
| `InstitutionLogo` (Figma 71:210) | ✓ | ✓ | ✓ | |

S5–S7 use `InstitutionLogo`, `Amount`, `EmptyState`, `ErrorBanner`, `LoadingSkeleton` and
`OfflineBanner` from above, plus: `Stepper` (step header), `ChipGroup` (radio chips),
`TextField`, `AmountField` (locale-aware decimal input that emits a `DecimalString`),
`CurrencyPicker`, `DateField`, `FilePicker` (document picker / drop zone with progress),
`CountTile`, `ImportRow` (with include `Switch`) and `BottomSheet` / `Dialog`.
