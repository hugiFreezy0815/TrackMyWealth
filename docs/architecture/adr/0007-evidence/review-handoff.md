# ADR 0007 review handoff (#384)

**Purpose:** Lets a reviewer or tester who has not followed #384 see the evidence behind the
web-client decision and repeat the acceptance tests.  
**Decision document:** [ADR 0007](../0007-web-client-approach.md), status **Accepted** (Option B, PO, 2026-11-06).  
**Issue:** [#384](https://github.com/hugiFreezy0815/TrackMyWealth/issues/384).
**Raw evidence:** [this folder](README.md).

This file is the test record behind the decision. The decision itself, with the accepted
residual risks, is in the ADR's Decision section.

## 1. Decision: Option B (accepted by the PO, 2026-11-06)

Keep **one Expo Router app** for native and web. For the dense desktop transaction view, use a
**semantic HTML `<table>` in a web-specific React DOM component**, with a React Native list on
native devices. Share domain logic, the generated API types and authenticated client, request
and cache/query logic, decimal/locale formatting, design tokens and mutations.

Fork **only the presentation component** (for example `transaction-table.web.tsx` next to
`transaction-table.tsx`); route files stay shared, because Expo Router needs a
platform-neutral route. This is the kind of genuine capability difference the
[development standards](../../development-standards.md) ("Don't fork behavior per-platform unless
the underlying capability genuinely differs") allow: native has no `<table>`. Everything else
uses `Platform.OS` checks, not duplicate components.

| Option | Architectural assessment | Decision consequence |
| --- | --- | --- |
| A: RNW list in Expo web | Shares more presentation code, but the tested `Pressable`-row design exposed **zero column headers** to Chromium's accessibility tree. RNW `role` props (`table`, `row`, `cell`) were **not** tried. | Needs equivalent, tested table semantics and navigation before it could be chosen. |
| **B: semantic DOM table in Expo web** | Exposes native table headers, keeps the single app, auth and router, and has no material measured bundle-size cost against the RNW-only export. | **Accepted** by the PO on 2026-11-06. |
| C: separate React web app | Independent web UI, but duplicates the app shell, authentication, routing, state and deployment. **Not benchmarked**: no separate React app was built. | Consider only if concrete, irreducible Expo limitations are demonstrated. |

Do **not** build an ARIA spreadsheet/grid interaction model by default. Use native table
navigation, one focusable activation control per row and standard Tab order first. If
spreadsheet-like arrow-key navigation turns out to be necessary, specify and test its full focus
model separately.

## 2. Evidence

The development-server numbers are in ADR 0007, section
[Automated browser evidence](../0007-web-client-approach.md#automated-browser-evidence-2026-11-05);
the production-build numbers are in the ADR's
[PERF-01 section](../0007-web-client-approach.md#production-build-traces-perf-01-2026-11-06).
Raw files are in [this folder](README.md). What each source can and cannot support:

| Source | Supports | Does not support |
| --- | --- | --- |
| Chromium run [#42111099580](https://github.com/hugiFreezy0815/TrackMyWealth/actions/runs/42111099580) | 20 automated cases (RNW/DOM × 1k/10k rows × five repeats) passed, including the bounded render window and scripted ArrowDown/Enter; the filtered CSV download was checked in the **first run of each of the four combinations** (4 of 20). Column-header counts in the browser accessibility tree. | Production timings (Expo **development** server), real NVDA/VoiceOver behaviour. The keyboard test focuses a container by script. |
| Repeat Chromium test inside run [#42179686520](https://github.com/hugiFreezy0815/TrackMyWealth/actions/runs/42179686520) | Same 20 cases passed again. | Ranking the renderers by load time: first-visible medians were roughly double the first run (DOM 1,735–1,808 ms, RNW 1,503–1,629 ms) and DOM was the slower one this time. Development-server timings vary too much between runs to rank them. |
| Export run [#42179686520](https://github.com/hugiFreezy0815/TrackMyWealth/actions/runs/42179686520) | Whole-app Expo web exports with only one renderer differ by a few hundred bytes. | The size of the table route alone, or of a separate React web app. |
| [PERF-01](perf-01-results.json) (2026-11-06, production export, Apple M5, Chrome 156) | Production-build load, filter and scroll timings with and without 4× CPU throttling; median 0 frames over 50 ms while scrolling, in either renderer (5 such frames in 3 of 80 runs, all unthrottled cold). Chrome traces in [`perf-01-traces/`](perf-01-traces). | Low-end Windows/Android hardware (CPU throttling is only a stand-in), a real network, or server paging. |
| [Keyboard and zoom checks](keyboard-zoom-results.json) (2026-11-06) | Real Tab/arrow presses on the production build: Tab order, sort state, off-screen selection, filtered CSV, 320 px / 200 % zoom / forced colours. | What a screen reader announces. |
| [PR #500](https://github.com/hugiFreezy0815/TrackMyWealth/pull/500) | The accidentally merged benchmark route and fixtures were removed from `main`. | Anything about the decision itself. |

A green check in GitHub Actions is not a substitute for reading the raw measurements and their
caveats.

## 3. Constraints for the production table

### Accessibility risks seen in the spike

Items 1–7 come from reading the spike's source; the keyboard and zoom checks (section 4)
confirmed items 1, 3 and 6.

1. The spike puts `aria-sort={undefined}` on a nested sort button instead of stating the
   direction on the sorted `<th>`. S3 sorts through a **sort menu**, not column headers; if
   production keeps that menu, `aria-sort` is not needed, and the menu announces the active
   order. If headers become sort controls, `aria-sort` goes on the `<th>` itself.
2. The spike's own keyboard test focuses an outer container by script. With real Tab presses a
   row *is* reachable (section 4, AT-01), but only after every filter and sort control.
3. **Confirmed:** after 60 × ArrowDown the selected row is off-screen (DOM) or not even mounted
   (RNW) and the list does not scroll. Selection, scroll position and focus must stay in step;
   returning from S4 must restore focus to the row.
4. Spacer rows are not data rows in the accessibility tree. Both renderers mount **25 data
   rows**; DOM's 27 mounted `<tr>` include 2 hidden spacer rows, and its 26 accessibility-tree
   rows are 25 data rows plus the header row. That does not prove NVDA can move through 10,000
   records.
5. Render and scroll diagnostics share a polite live region; on an empty result it reads "0
   filtered transactions · 0 virtualised rows mounted · post-change paint callback …".
   Production announces result counts and selection only.
6. **Confirmed:** at 200 % zoom both renderers cut dates and amounts to "202…" and "+ C…"
   ([DOM](at-07-dom-200-percent-zoom.png), [RNW](at-07-rnw-200-percent-zoom.png)); at 320 px the
   page scrolls sideways. Amounts must never be truncated; the table needs a reflow or a
   narrow-screen layout.
7. The spike sets `aria-selected` on a `<tr>` in a plain `<table>`. ARIA supports
   `aria-selected` on rows only inside `grid`/`treegrid`, so the selected state is probably not
   announced. Expose selection with `aria-current="true"` on the row, or in the name of the
   row's activation button, with **one** labelled activation control per row.

### Data and API

- **Server paging, with Load more.** The account-detail list (#494) uses the server-paged
  `PageResponse<T>` contract: `page` is zero-based, `size` is 50 (cap 200)
  ([API conventions](../../../api/conventions.md)). On web, S3 has a "Load more" button and
  "Showing {shown} of {totalElements}" ([S3 spec](../../../ux/screens/account-detail.md)), so the
  loaded rows **grow** with each Load more. #494 decides whether to cap the loaded rows and
  window them, or to accept unbounded growth for realistic account sizes. Never fetch all rows
  to sort or filter on the client.
- **Reload from page 0.** Any filter or sort change, and any change made in S4 (category,
  correction, removal), reloads from page 0 with the same filter and sort (S3 spec; API
  conventions "Offset paging and refresh after imports"). Accessibility consequence: after a
  category save under the Uncategorised filter the row leaves the list, so focus needs a defined
  target (the next row, or the list heading if it was the last) and the new result count is
  announced. After Load more, focus stays on the button or moves to the first new row; #494
  decides which and tests it.
- **Filters:** the API's only list filter is `uncategorized`. The spike's free-text search and
  category filter have no production equivalent.
- **Sorting:** only `bookingDate` and `amount`, with a server-side `id` tie-breaker. S3 offers
  them as a four-option sort menu (Newest/Oldest first, Largest/Smallest amount). Sorting by
  Description or Category needs an API contract change first. The spike's five sortable columns
  are not the production design.
- Keep workspace authorization on the server (RLS) and use the shared auth client; no second
  login flow.
- Row detail and category controls have accessible labels and keep keyboard focus after a change.

### CSV export

CSV export is **not** in the S3/S4 spec; the spike has it only to compare renderers. If it is
added later:

- Export only the authorized, filtered result the user is looking at.
- Treat every text cell as untrusted: descriptions and payees come from bank imports. Prefix
  cells that start with `=`, `+`, `-`, `@`, tab or carriage return with `'` (OWASP CSV
  injection), then quote and escape. The spike's `csvCell` misses `-`; do not copy it.
- Exempt only amount cells that match a strict decimal pattern (`^-?\d+(\.\d+)?$`), and write the
  API's `DecimalString` value unchanged, so negative amounts stay numbers.

## 4. Acceptance tests

### Running the spike (production build, localhost only)

The spike code exists only at the tagged commit. Its route **skips sign-in on web** so it can run
without a backend. Serve it on `127.0.0.1` only, never on the network, a tunnel or a deployment,
and use the built-in invented data only. (`npm run web` is not suitable: Expo's dev server
listens on the local network by default.)

```bash
git fetch origin tag spike-384-evidence --no-tags   # a plain `git fetch` does not get it
git worktree add --detach ../tmw-384 spike-384-evidence
cd ../tmw-384/mobile
npm ci
CI=1 npx expo export -p web --output-dir ../../tmw-384-dist
node <repo>/docs/architecture/adr/0007-evidence/scripts/serve.mjs "$(cd ../../tmw-384-dist && pwd)" 8384
```

Open `http://127.0.0.1:8384/spike-web-transactions?renderer=dom&rows=10000` (and `renderer=rnw`,
`rows=1000`). To repeat the automated runs: `npm i -D playwright` in a scratch folder, then
`node perf.mjs http://127.0.0.1:8384 <outDir>` and `node keyboard.mjs http://127.0.0.1:8384
<outDir>/keyboard-zoom-results.json` from [`scripts/`](scripts) (they use the installed Google
Chrome).

On Windows use **NVDA + Chrome**; on macOS use **VoiceOver + Safari**. In Safari, first turn on
*Settings → Advanced → Press Tab to highlight each item on a webpage*, or Tab skips buttons.
Record the actual OS, browser, screen-reader version, viewport, zoom, speech and focus.

| ID | Action | Pass when |
| --- | --- | --- |
| AT-01 | From the address bar, Tab through filters, sort controls, table and first row; reverse with Shift+Tab. | Every control and a row are reachable in a logical order without a mouse; focus is always visible. |
| AT-02 | DOM mode: jump to the table (NVDA `T`; VoiceOver `VO+Cmd+T`), move by cells (NVDA `Ctrl+Alt+Arrows`; VoiceOver `VO+Arrows`), read a row (VoiceOver `VO+R`). | The table is announced as a table; moving between cells announces the column name with the value; amounts are read with sign and currency. |
| AT-03 | Change the sort to Date and Amount, both directions. | The new order is announced or discoverable without sight; focus stays on the sort control; order is correct. |
| AT-04 | Select a row, open S4 details, return to the row. | Selection is announced; details get focus and are read; Escape/back returns focus to the same row. |
| AT-05 | Move the selection to a row outside the rendered window at 1k and 10k rows. | The selected row scrolls into view and keeps focus; position is announced correctly. |
| AT-06 | Filter, reset, produce an empty result, export CSV. | The new result count is announced once and politely; the CSV holds exactly the filtered rows. |
| AT-07 | 200 % zoom, 320 px CSS width, Windows high-contrast / forced colours. | No truncated amount or date, no sideways page scroll, focus visible. |
| PERF-01 | Production build, both renderers at 1k and 10k, cold and warm, with traces. | First visible under 1 s and filter under 100 ms (median) on the stated hardware; median 0 frames over 50 ms while scrolling. |

Defects found in the spike get a production mitigation and an owner in #494 or #378; the
disposable route is never shipped. Rerun AT-01..AT-07 on the #494 table itself before it ships,
because the spike's virtualisation is not the production design.

### Review record

Add one row per test run. Automated results never count as a screen-reader Pass.

| ID | Tester + date | OS / browser / AT / viewport | Renderer + rows | Result | Notes / evidence |
| --- | --- | --- | --- | --- | --- |
| PERF-01 | Claude Code (automated), 2026-11-06 | macOS 26.7.1, Apple M5, Chrome 156 headless, 1440×900; no throttling and 4× CPU throttling | Both, 1k and 10k, 5 cold + 5 warm each | **Pass on medians** (against the criteria above) | Medians: first visible 78–107 ms (4×: 255–383 ms), filter 45–60 ms (4×: 53–67 ms), 0 frames over 50 ms while scrolling; 5 such frames in 3 of 80 runs, all unthrottled cold. First visible within about 10 % between renderers; filtering 10k rows unthrottled: DOM 45 ms, RNW 58 ms. [Results](perf-01-results.json), [traces](perf-01-traces). |
| AT-01 (keyboard only) | Claude Code (automated), 2026-11-06 | Chrome 156 headless, real Tab presses | Both, 10k | **Pass, with remark** | Every control reachable, focus visible, Shift+Tab works. First row at Tab stop 9 (RNW) / 14 (DOM: five header sort buttons first). |
| AT-03 (state only) | Claude Code (automated), 2026-11-06 | as above | Both, 10k | **Fail (spike)** | DOM: no header gets `aria-sort` after sorting; RNW has no headers. See risk 1. |
| AT-05 (keyboard only) | Claude Code (automated), 2026-11-06 | as above | Both, 10k | **Fail (spike)** | After 60 × ArrowDown the list does not scroll; the selected row is off-screen (DOM) or unmounted (RNW). See risk 3. |
| AT-06 (no AT) | Claude Code (automated), 2026-11-06 | as above | Both, 10k | **Pass, with remark** | CSV holds exactly the 2,000 filtered rows. The live region reads performance diagnostics (risk 5). |
| AT-07 | Claude Code (automated), 2026-11-06 | Chrome 156: 320×640, 720×450 @2× (200 %), forced colours | Both, 10k | **Fail (spike)** | Dates and amounts truncated at 200 %; sideways scroll at 320 px; forced colours OK. See risk 6. |
| AT-01 with VoiceOver | hugiFreezy0815 (PO), 2026-11-06 | macOS 26.7.1, Safari 27.0, VoiceOver | DOM and RNW, 1k | **Pass** | A transaction row is reachable by Tab and Shift+Tab with VoiceOver on; focus visible. Speech not transcribed. |
| AT-02 with VoiceOver | hugiFreezy0815 (PO), 2026-11-06 | as above | DOM, 1k | **Pass** | Table found and navigable; column names read with cell values. Speech not transcribed. |
| AT-02 with VoiceOver | — | as above | RNW | **Not run** | Known RNW limitation: the RNW list exposes no table or column headers (accessibility tree, risk for Option A). |
| AT-03 with VoiceOver | hugiFreezy0815 (PO), 2026-11-06 | as above | DOM and RNW, 1k | **Pass** | The new order is announced through the sort menu and the Ascending/Descending button. The table header carries no sort state (risk 1); production uses the S3 sort menu, which does not need it. |
| AT-04 with VoiceOver | hugiFreezy0815 (PO), 2026-11-06 | as above | DOM and RNW, 1k | **Pass** | Selection announced; details receive focus and are read; return to the row works. Speech not transcribed. |
| AT-01..AT-04 with NVDA | Unassigned | Windows, Chrome, NVDA | DOM and RNW, 1k | Not run | Human test; needs a Windows machine. |

The spike failures are expected: it is throwaway code. They become requirements for #494 (risks
1, 3, 5, 6, 7) and do not change the A/B/C comparison, because both renderers failed the same
way.

## 5. Questions for the reviewer

- Is **B** the most defensible provisional decision, and are A and C rejected for the right
  reasons?
- Do the development-server timings, production traces and whole-app bundle totals support the
  recommendation without overclaiming assistive-technology results?
- Are the accessibility risks in section 3 complete, and does each have an owner in #494 or #378?
- Does forking only the presentation component keep authentication, data and caching,
  formatting, authorization and routing genuinely shared?
- Are more automated or human checks needed before Sprint 7?

## 6. Acceptance gates

- [x] Automated synthetic Chromium comparison recorded with raw evidence.
- [x] Renderer-isolated Expo production bundle sizes recorded and scoped.
- [x] Benchmark route and fixtures removed from `main` (PR #500).
- [x] Production-build browser traces with hardware, browser version and repeat count (PERF-01, 2026-11-06).
- [x] Automated keyboard and zoom checks (AT-01, AT-03, AT-05..AT-07), 2026-11-06.
- [x] Independent architecture/UX review of this proposal and its limits (2026-11-06: accepted with corrections; corrections applied and confirmed in a second pass).
- [x] VoiceOver results (AT-01..AT-04, macOS 26.7.1 / Safari 27.0), 2026-11-06: pass; RNW AT-02 not run (no table semantics).
- [x] NVDA not tested: accepted by the PO as a residual risk on 2026-11-06. An NVDA + Chrome check of the #494 table is required before the web launch.
- [x] Owners for risks 1, 3, 5, 6, 7 in #494 and for the component inventory in #378 (issue comments, 2026-11-06).
- [x] **PO approved Option B on 2026-11-06, with the residual risks listed in the ADR's Decision section.**
- [x] ADR 0007 status changed from Proposed to Accepted in a separate change.
- [x] #384 closed by the merge of the Accepted change: ADR merged and approved, spike kept at tag `spike-384-evidence`, not merged.

**PO decision:** Option B. **Signer / date:** hugiFreezy0815 (PO), 2026-11-06. **Accepted residual risks:** see the [ADR's Decision section](../0007-web-client-approach.md#decision-po-2026-11-06).
