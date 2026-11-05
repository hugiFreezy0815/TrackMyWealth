# Screen specifications: sprint 7 (UX-02, #377)

These specs describe the seven screens the frontend developer builds in sprint 7
(2026-11-10 to 2026-11-23). Each spec lists every field with its label key, its format and the
endpoint and response field it comes from. It also covers every state, every action with the
request it sends, and the accessibility rules.

| # | Screen | Spec | Figma (EN / DE) |
|---|---|---|---|
| S1 | Overview: portfolio tabs and the first tab (whole workspace), plus the overview name in setup and Settings | [overview.md](overview.md) | M03, M20, M02, M13 / W01, W09 |
| S2 | Portfolio View: one tab per portfolio, one section per account type | [portfolio-view.md](portfolio-view.md) | M05, M06 / W02, W03 |
| S3 | Account detail with its paged transaction list | [account-detail.md](account-detail.md) | **M25** (new: EN 115:2999, DE 115:3443), M10 / W06 |
| S4 | Transaction detail with category change and change history | [transaction-detail.md](transaction-detail.md) | M21 / W06 side panel |
| S5 | Add portfolio (catalogue or own) | [add-portfolio.md](add-portfolio.md) | M14 / W11 |
| S6 | Add account and its starting value (opening balance or valuation) | [add-account.md](add-account.md) | M17, M16 / dialog like W11 |
| S7 | Import statement: file, review with duplicates, commit | [import-statement.md](import-statement.md) | M08, M09 / W05 |

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
- **#376 (information architecture) has a proposed implementation:** see
  [UX-01 information architecture](../01-information-architecture.md). Until PO approval the
  navigation decision is provisional: phone Overview + More; web Overview + Settings, with
  portfolio tabs **inside** Overview rather than a duplicate top-level Portfolios screen.
  Investments and Cash Flow are hidden in Sprint 7 (the S1 cash-flow teaser remains).
  Breakpoints: <600px phone, 600–1023px tablet, ≥1024px desktop.
  Approval of #376 and ADR 0007 (#384) is still required before treating the navigation
  as accepted for implementation.
- **#378 (visual foundations) is not merged yet.** Component names used here (`Amount`,
  `ValueStatusBadge`, `ScopeBar`, `PortfolioTabs`, `SectionCard`, `AccountRow`,
  `TransactionRow`, `EmptyState`, `ErrorBanner`, `LoadingSkeleton`, `ValueChart`,
  `PeriodSwitch`, `ChangeChip`) are the inventory #378 must define. The list at the end of this
  file says which screen uses which.
- **#379 (glossary and copy) owns the final wording.** The EN/DE texts here are drafts with
  their message keys, written as complete ICU messages.
- **German addresses the member with "du" (PO decision 2026-11-05).** Every German text in the
  app uses the informal form ("Name deiner Übersicht", "Bitte lade neu"). The DE texts in
  `docs/api/error-codes.md` still use "Sie". #379 rewrites them, and the app uses its own
  "du" texts for every message key.

## Rules for every screen

1. **No figure is computed on the client (FR-API-010).** The app shows, formats and sorts what
   the server sends. It never adds, subtracts, converts or derives a percentage. Where a screen
   needs a figure the API does not send yet, the spec names the story that adds it. Until that
   story merges, the element is **hidden**: no placeholder, no client-side workaround.
2. **Amounts are strings.** Every `format: decimal` field is a `DecimalString`. It is formatted
   for display only (#378: `de-CH` `1'234.56`, `de-DE` `1.234,56`, `en` `1,234.56`; the currency
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
