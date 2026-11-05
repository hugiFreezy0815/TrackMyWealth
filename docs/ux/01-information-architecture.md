# UX-01 — Information architecture and first-run journey

> **Status:** Proposed for product-owner approval · issue [#376](https://github.com/hugiFreezy0815/TrackMyWealth/issues/376) · Sprint 6
>
> **Authority:** Sprint-7 screen specifications in [`screens/README.md`](screens/README.md), the PO's 2026-10-20 terminology decision, and the 2026-11-05 S1–S7 scope decision. When a screen wireframe and a screen specification disagree, the *screen specification wins*.

## 1. Scope and vocabulary

The first tab is **Overview**, showing all portfolios and named with the user's own overview name; the word *workspace* is only a technical backend/API term and **never appears in the app**. A user may call the first tab “My finances” or leave it blank, in which case it displays **All portfolios / Alle Portfolios**. Subsequent tabs represent the server-ordered, user-named portfolios (for example “Yuh” and “PostFinance”). Institutions are banks/brokers associated with portfolios; an account belongs to one portfolio; a **Depot** is a securities account, not a portfolio.

The MVP is the seven approved [S1–S7 screens](screens/README.md). These are product screens scheduled for Sprint 7; #376 specifies structure and flows, **not an implementation claim**.

| Area | MVP visibility | Entry and destination | Unlocks later |
|---|---|---|---|
| **Overview / Übersicht** | Visible, primary | S1 first overview tab, portfolio strip; S2 individual portfolio tab | Immediately |
| **Portfolios** | Visible as a navigation shortcut to the *same S1/S2 tab strip*, never a separate financial scope | `/overview`, `/overview/{portfolioId}` | S1/S2; do not create a second portfolio screen |
| **Accounts** | Visible **inside a portfolio**, not a top-level tab | S3 account detail, S4 transaction detail | S3/S4 |
| **Add portfolio / Add account** | Visible as contextual actions | S5 → S6 | S5/S6 |
| **Import statement** | Visible from an account or portfolio context | S7; account selected/fixed before upload | S7 |
| **Settings / More** | Visible for account-level preferences and overview name | Overview name edit per S1; sign-out | Minimal settings in Sprint 7 |
| Investments / Depot performance | Hidden (not greyed out) | Existing Depot sections remain in S2 | EPIC 15/16; #478 positions enable S2's positions subsection |
| Cash flow navigation | Hidden; S1 retains its cash-flow teaser | None until screen is planned | Subsequent screen/story; S1 teaser is not a full Cash Flow page |
| Budget and Goals | Hidden | None | Budget / Goals epics |
| Import history / rollback, removed transactions | Hidden | None | Follow-up sprint |
| Notifications, search, transaction creation/correction | Hidden | None | Follow-up sprint |
| Administration / reference data | Not part of regular member navigation | Separate guarded admin surface, never visible to regular members | Admin capability and product scope |

**Clarification of existing mockups:** M/W screens showing Investments, Cash flow or Budget as active navigation items are future navigation proposals, **not permission to ship placeholders** in Sprint 7. The single account data source and portfolio tabs match S1/S2.

## 2. Navigation tree and stable routes

```text
App
├─ First-run / Authentication
│  ├─ First-time setup or Sign in → MFA challenge → signed-in app
│  └─ Name your overview + currency (S1 setup) [optional name]
├─ Overview (default entry, /overview) [S1]
│  ├─ First tab: {overviewName || "All portfolios"}
│  ├─ Portfolio tabs: /overview/{portfolioId} [S2]
│  │  ├─ Sections by account type; account row → /accounts/{accountId} [S3]
│  │  │  ├─ Activity → /accounts/{accountId}/transactions/{transactionId} [S4]
│  │  │  └─ Import statement → /accounts/{accountId}/imports/new [S7]
│  │  └─ Import statement → choose an account → S7
│  └─ + Add portfolio → /portfolios/new [S5]
│     └─ Add account → /portfolios/{portfolioId}/accounts/new [S6]
│        ├─ Enter opening balance / valuation → S1 or S3
│        └─ Import statement → S7 → S3 / S1
└─ More / Settings
   ├─ Name of your overview → edit name (S1)
   ├─ Privacy: hide amounts / show amounts
   ├─ Advanced detail preference (optional; in Sprint 7 disclosure works without a toggle)
   └─ Sign out → Sign in
```

These URLs are **design routes**, not claims that Expo Router already implements them. The API is always called by its distinct `/api/v1/…` path. The “Portfolios” navigation shortcut opens the existing portfolio tab strip rather than duplicating the overview data model.

For deep links, restore the session first; an anonymous member returns to the intended route **after** sign-in/MFA. If the account/portfolio is gone or inaccessible, use the S1/S2/S3 documented `NOT_FOUND` treatment and a safe route back to the overview; never expose an existence distinction across workspaces. An archived account uses S3's archived banner, not a deleted route. Back from S4 restores S3's previous filter/page/scroll if still valid; mutations invalidate it and start at page zero. On web, selection is encoded in the URL so reload/back/forward restore the pane.

## 3. Responsive navigation and layout

Breakpoints are **CSS viewport widths**, not physical device model names: phone `<600px`, tablet `600–1023px`, desktop web `>=1024px`. Support portrait and landscape. Do not use browser platform to force desktop layout on narrow windows.

| Dimension | Phone (<600px) | Tablet (600–1023px) | Desktop web (>=1024px) |
|---|---|---|---|
| Primary navigation | Bottom tabs: Overview, More; hide future areas | Bottom tabs or compact side rail when landscape/space permits; same two destinations | Persistent sidebar: Overview, Settings/More; future areas absent |
| Portfolio navigation | Horizontal scrollable strip **inside S1**, with selected tab scrolled into view | Scrollable/wrapping portfolio strip; 2-column cards where specs allow | Strip wraps; portfolio tabs share the Overview route |
| Contextual action | Prominent “+ Add portfolio” in tab strip; import within S2/S3 | Same actions, no hidden affordance | Sidebar-independent toolbar on content screens |
| Content | One column; cards/rows stacked | One/two columns subject to screen spec; avoid squeezed tables | Main content + 360px optional detail rail; S3 transaction table uses all required columns |
| S4 transaction detail | Pushed full-screen view with Back | Pushed detail or pane only if space and accessibility allow | Right detail panel, with URL and browser history |
| Focus / keyboard | Touch first, screen-reader actions | Keyboard when present | Visible focus ring; Tab through actions; arrows within composite controls; Enter activates; Escape closes drawer |
| Settings / sign-out | More tab | More route | Sidebar Settings; sign-out in settings |

### Low-fidelity wireframes

**Phone: 390px** (tabs are scrollable; other sections are not shown as disabled)

```text
┌────────────────────────────────┐
│ Overview                 (eye) │
│[All portfolios][Yuh][DKB] →   │
│[Currency: CHF ▾]              │
│┌ Net worth ────────────────┐ │
││ CHF 478'160  [Estimated]  │ │
│└────────────────────────────┘ │
│┌ Portfolios ───────────────┐ │
││ PostFinance     CHF 64'820 │ │
││ Yuh             CHF 17'520 │ │
│└────────────────────────────┘ │
│ [+ Add portfolio]              │
├────────────────────────────────┤
│    [Overview]       [More]     │
└────────────────────────────────┘
```

**Tablet: 800px** (same routes, two-column content when supported)

```text
┌─────────────────────────────────────────────────────┐
│ Overview                         [Privacy] [More]   │
│ [All portfolios] [Yuh] [DKB] [+ Add portfolio]      │
├────────────────────────┬────────────────────────────┤
│ Net worth / status     │ Portfolios                 │
│ Assets / liabilities   │ Cash-flow teaser           │
├────────────────────────┴────────────────────────────┤
│             [Overview]           [More]             │
└─────────────────────────────────────────────────────┘
```

**Desktop: 1440px** (right panel is context-sensitive; the sidebar is not a duplicate portfolio list)

```text
┌────────────────┬──────────────────────────────────────┬────────────────────┐
│ Overview       │ Overview              [+ Add] [Eye]  │ Summary / detail   │
│ Settings       │ [All portfolios] [Yuh] [DKB] [+]      │                    │
│                ├──────────────────────────────────────┤ Portfolios / cash  │
│                │ Net worth + status                   │ flow on S1         │
│                │ Assets / liabilities                 │                    │
│                │ S3: Date | Description | Cat. | Amt  │ S4 selected txn    │
└────────────────┴──────────────────────────────────────┴────────────────────┘
```

On S3, the S4 detail panel replaces the right contextual rail and is not a second route in a parallel app. In small desktop windows, the rail collapses and S4 becomes a page. A user's content focus stays visible at 200% zoom.

## 4. Scope, currency and data trust

- **The scope bar is always visible on data pages**, immediately beneath the Overview/portfolio tabs or above S3's data. It states the effective scope (all portfolios, named portfolio or named account), and the currency. In Sprint 7 the currency picker on S1 can change **currency only** because the net-worth endpoint does not offer arbitrary account filters; do not display an account selector that silently fails to scope server totals.
- The workspace currency comes from `GET /api/v1/workspace` `currency`; the overview name from `name`. Portfolios from `GET /api/v1/portfolios`; S1 uses `GET /api/v1/net-worth?currency=…`, `GET /api/v1/accounts`, and `GET /api/v1/cash-flow?month=…&currency=…`. The S2–S7 request mappings remain normative in their own specs.
- **No financial sums on the client.** Preserve decimal strings until display. For missing values render “–”, not CHF 0. Estimated/stale/unreconciled status uses text and icon in addition to colour; negative totals remain negative.
- Hide amounts via the eye control **across all screens**, including screen-reader labels; use a privacy cover in the system app switcher (FR-CLI-005). The web browser cannot guarantee OS-level recent-app thumbnail protection, so offer hide amounts and avoid representing it as equivalent to native OS protection.
- Each screen has its own loading, empty, error, offline and refresh treatment (S1–S7). A failed card must not make unrelated cards disappear.

## 5. First-run journey (target: under ten minutes)

**Entry:** Fresh single-workspace installation OR an invited member with no signed-in session. This is a **target budget**, not a measured usability result. The path is skippable/resumable; the user may choose manual starting values when no statement file is available.

```text
[App launch]
   │ signed-in? yes ───────────────────────────────┐
   │ no                                             │
   ▼                                                │
[Initial admin setup (only when unconfigured)]       │
  OR [Sign in]                                       │
   │                                                │
   ▼                                                │
[MFA challenge, where enabled] ─ auth failure → retry│
   │                                                │
   ▼                                                │
[Name your overview + currency] (optional name)      │
   │                                                │
   ▼                                                │
[Overview empty state (S1)] ◀─────────────────────────┘
   │ "+ Add your first portfolio"
   ▼
[Add portfolio (S5)] search institution OR create own
   │ created portfolioId
   ▼
[Add account (S6)] choose account type and currency
   │
   ├── Existing statement? ─ Yes → [Import file (S7)]
   │                              → preview / duplicates → confirm
   │
   └── No → [Opening balance / valuation (S6)] 
             (loans with no applicable starting-value step skip it)
   │
   ▼
[Overview first tab] → [S2 portfolio] → [S3 account]
   │
   └── Suggestions: add another account / later import; no blocked onboarding
```

| Step | Goal and owner screen | Public API | Target elapsed |
|---|---|---|---|
| 1 | Setup or sign in, MFA if required; existing sessions skip | Existing setup/auth/session endpoints; see `mobile/src/features/auth/` | 0:00–2:00 |
| 2 | Optional name (up to 60 chars) and currency; live preview shows exact first-tab name or fallback | `GET /api/v1/workspace`; `PUT /api/v1/workspace/name` with `If-Match`; currency endpoint per S1 | 2:00–2:45 |
| 3 | Empty-state Overview card leads directly to S5 | `GET /api/v1/portfolios`, `GET /api/v1/accounts`, `GET /api/v1/net-worth` | 2:45–3:15 |
| 4 | Create portfolio from catalogue or custom name | `GET /api/v1/institutions/catalogue`; `POST /api/v1/portfolios` | 3:15–4:45 |
| 5 | Create account and choose currency / type | `POST /api/v1/accounts` (+ identifier when given) | 4:45–6:15 |
| 6 | **Either** set opening balance / manual valuation **or** import file, review duplicates and commit | `POST /api/v1/accounts/{id}/opening-balance` or `POST …/valuations`; or S7 upload/parse/commit | 6:15–9:00 |
| 7 | See first Overview, confirm portfolio/account value and status | S1/S2 refresh on mutation; no client-computed totals | 9:00–10:00 |

The time budget for statement import assumes a **small supported file** with unambiguous format and no remediation; an unsupported file or an account-confirmation conflict is not forced through. It offers a documented manual-value alternative or a resumable import. Never prefill a value by guessing from incomplete import data.

**Empty/negative states:** zero portfolios → S1 onboarding call-to-action; portfolios but no accounts → S1 account call-to-action, not a fabricated error; network unavailable → keep entered information, allow Retry, no accidental double-submit; missing MFA → remain on sign-in, never bypass it; version conflict when naming → preserve draft and prompt to reload current name; import warnings/duplicates → S7 review before commit; `NOT_FOUND` on deep link → safe Overview fallback.

## 6. Secondary interaction flows

**Categorise a transaction:** S1 first/portfolio tab → S2 account row → S3 Activity list `GET /api/v1/accounts/{id}/transactions?page=0&size=50` → S4 transaction `GET /api/v1/accounts/{id}/transactions/{transactionId}` (requires #479, or row cache until merged) → CategoryPicker → `PUT …/category` with `If-Match` → show success, invalidate S3/S1 derived views and reload the paged list at page 0. Preserve selected account and display `VERSION_CONFLICT` instead of overwriting another member's change.

**Archive an account:** not a Sprint-7 action; do **not** render an active menu item in S3. Once a later story adds the edit/confirmation UI, require explicit confirmation, use `If-Match`, show the archived state, and navigate to the portfolio with totals refreshed. Archived account deep links remain readable if access permits; they do not count towards totals.

**Sign out:** More/Settings → Sign out → clear session credentials and cached financial data (including web storage/in-memory query cache), replace route with sign-in, and invalidate sensitive browser history state where feasible. MFA is required again if the server requests it. Sign-out must work from S4's side panel and any onboarding step.

## 7. Accessibility, localisation and implementation constraints

- WCAG **2.2 AA** targets: 44×44 pt touch targets, visible keyboard focus, correct heading order, adequate colour contrast, 200% browser zoom without clipped amounts, screen-reader descriptions for signs, currency and financial status. Keyboard: Tab navigates controls; arrows move inside portfolio tabs, Enter/Space activate, Escape closes overlay/drawer and returns focus to the opener.
- English and German (informal **du**) messages use complete ICU keys, not concatenated phrases; German fallbacks use **Alle Portfolios**. The glossary and production copy are owned by #379, and visual tokens are owned by #378. These wireframes use **content and layout**, not prescribed colours.
- No product routes/API calls for missing backend capabilities; screens hide those elements until dependent Sprint-7 stories (#477–#482) land. No reimplementation of server calculations.
- Use the existing Expo Router app and typed client as the current implementation baseline. Web data-dense implementation can be RNW or web-specific React DOM **inside the same Expo app** pending #384; do not add a separate product repository without ADR 0007 approval.

## 8. Review and acceptance checks

1. A PO can trace every visible navigation item to S1–S7; hidden areas name their unlock story/epic.
2. At 390, 800 and 1440 px, navigation remains available, scope/currency legible, and a selected S4 transaction is not lost to a browser reload.
3. A new user can follow the first-run table end-to-end with a synthetic account; an observer records the actual elapsed time and whether it is under ten minutes (**not yet observed**).
4. Sign-out, missing access, offline, optimistic-concurrency conflict and no-data states each have a defined recovery.
5. Localised copy does not show “workspace”, “household” or “container” to a regular member, and an empty name shows the exact EN/DE fallback.

### PO sign-off requested

- Confirm the MVP navigation: **Overview + More/Settings only**, with portfolio tabs *inside* Overview; Cash flow and Investments remain hidden even though their future mockups exist.
- Confirm the proposed **600px / 1024px** breakpoints and 360px desktop detail rail, subject to accessibility/zoom.
- Approve the first-run target and its manual starting-value fallback; usability verification remains a Sprint-7 prototype check.
- Revisit the web-native component split only after #384's measurements and ADR review.

**No claim of PO approval is made by this document.**
