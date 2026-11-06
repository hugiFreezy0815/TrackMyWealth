# ADR 0007 review handoff (#384)

**Purpose:** Lets a reviewer or tester who has not followed #384 assess the proposed web-client
decision and run the human acceptance tests.  
**Decision document:** [ADR 0007](../0007-web-client-approach.md), status **Proposed**.  
**Issue:** [#384](https://github.com/hugiFreezy0815/TrackMyWealth/issues/384).
**Raw evidence:** [this folder](README.md).

This file is a checklist and test record, not sign-off. Nothing in it shows that a
screen-reader test, a production performance trace or PO approval has happened until the
review record in section 4 says so with a name and date.

## 1. Decision to review: Option B (proposed, not accepted)

Keep **one Expo Router app** for native and web. For the dense desktop transaction view, use a
**semantic HTML `<table>` in a web-specific React DOM component**, with a React Native list on
native devices. Share domain logic, the generated API types and authenticated client, request
and cache/query logic, decimal/locale formatting, design tokens and mutations. Put the two
presentations in `*.web.tsx` / `*.native.tsx` or equivalent.

| Option | Architectural assessment | Decision consequence |
| --- | --- | --- |
| A: RNW list in Expo web | Shares more presentation code, but the tested `Pressable`-row design exposed **zero column headers** to Chromium's accessibility tree. | Needs equivalent, tested table semantics and navigation before it could be chosen. |
| **B: semantic DOM table in Expo web** | Exposes native table headers, keeps the single app, auth and router, and has no material measured bundle-size cost against the RNW-only export. | **Proposed**, pending the checks in section 4 and explicit PO approval. |
| C: separate React web app | Independent web UI, but duplicates the app shell, authentication, routing, state and deployment. **Not benchmarked**: no separate React app was built. | Consider only if concrete, irreducible Expo limitations are demonstrated. |

Do **not** build an ARIA spreadsheet/grid interaction model by default. Use native table
navigation, focusable action buttons and standard Tab order first. If spreadsheet-like
arrow-key navigation turns out to be necessary, specify and test its full focus model separately.

## 2. Evidence

The measured numbers are in ADR 0007, section
[Automated browser evidence](../0007-web-client-approach.md#automated-browser-evidence-2026-11-05),
with the raw JSON in [this folder](README.md). They are not repeated here, so there is one place
to correct. What each source can and cannot support:

| Source | Supports | Does not support |
| --- | --- | --- |
| Chromium run [#42111099580](https://github.com/hugiFreezy0815/TrackMyWealth/actions/runs/42111099580) | 20 automated cases (RNW/DOM × 1k/10k rows × five repeats) passed, including the bounded render window, filtered CSV download and scripted ArrowDown/Enter; column-header counts in the browser accessibility tree. | Production Web Vitals or frame rates (Expo **development** server, synthetic data), or real NVDA/VoiceOver behaviour. The keyboard test focuses a container by script. |
| Export run [#42179686520](https://github.com/hugiFreezy0815/TrackMyWealth/actions/runs/42179686520) | Whole-app Expo web exports with only one renderer differ by a few hundred bytes. | The size of the table route alone, or of a separate React web app. |
| [PR #500](https://github.com/hugiFreezy0815/TrackMyWealth/pull/500) | The accidentally merged benchmark route and fixtures were removed from `main`. | Anything about the decision itself. |

A green check in GitHub Actions is not a substitute for reading the raw measurements and their
caveats.

## 3. Constraints for the production table

### Accessibility risks seen in the spike's source

These are **source-review observations, not witnessed assistive-technology failures**:

1. The spike puts `aria-sort={undefined}` on a nested sort button instead of stating the
   direction on the sorted `<th>`. A production table sets `aria-sort` on the header itself and
   gives sort buttons clear labels.
2. The test harness focuses an outer container before ArrowDown/Enter, so it **does not show**
   that a keyboard-only user can Tab to the first transaction row.
3. Updating the selected index does not prove that focus and the scroll position stay in step.
   Test off-screen rows, scroll-into-view and returning focus from S4.
4. Spacer rows are not data rows in the accessibility tree. 25–27 mounted rows do not prove that
   NVDA can move through 10,000 records.
5. Render and scroll diagnostics share a polite live region. Production announcements describe
   result counts and selection, not performance metrics.
6. A 504 px demo panel says nothing about reflow, 200 % zoom or a 320 px CSS viewport.

### Data and API

- **Page, don't virtualise.** The account-detail list (#494) uses the server-paged
  `PageResponse<T>` contract: `page` is zero-based, `size` defaults to 50 and is capped at 200
  ([API conventions](../../../api/conventions.md)). The browser never holds more than one page,
  so the spike's 10,000-row virtualisation is context for the renderer comparison, not a
  production requirement. Never fetch all rows to sort or filter on the client.
- **Only Date and Amount are sortable.** `GET /api/v1/accounts/{accountId}/transactions` accepts
  `sort` on `bookingDate` and `amount` only, with a server-side `id` tie-breaker. The S3 table
  columns Date, Description, Category, Amount ([S3 spec](../../../ux/screens/account-detail.md))
  therefore have sort controls on **Date and Amount only**. Sorting by Description or Category
  needs an API contract change first. The spike's five sortable columns are not the production
  design.
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
- Write amounts as plain numbers, not as text, so the prefix rule never touches them.

## 4. Human acceptance tests

### Running the spike

The spike code exists only at the tagged commit. Its route **skips sign-in on web** so it can run
without a backend: run it on `localhost` only, never with `--host lan`, a tunnel or a deployment,
and use the built-in invented data only.

```bash
git fetch origin tag spike-384-evidence --no-tags   # a plain `git fetch` does not get it
git worktree add --detach ../tmw-384 spike-384-evidence
cd ../tmw-384/mobile
npm ci
npm run web
```

Open the port Expo prints (usually 8081) and compare:

- `/spike-web-transactions?renderer=dom&rows=1000`
- `/spike-web-transactions?renderer=dom&rows=10000`
- `/spike-web-transactions?renderer=rnw&rows=1000`
- `/spike-web-transactions?renderer=rnw&rows=10000`

On Windows use **NVDA + Chrome** (optionally Firefox); on macOS use **VoiceOver + Safari**. Record
the actual OS, browser, screen-reader version, viewport, zoom, speech and focus, and pass/fail.

| ID | Action | Evidence to record |
| --- | --- | --- |
| AT-01 | From the browser chrome, Tab through filters, sort controls, table and first row; reverse with Shift+Tab. | Reachable without scripted focus; focus visible. |
| AT-02 | In DOM mode, use NVDA/VoiceOver table navigation to read column names and row cells. | Announced table and column information; navigable row count. |
| AT-03 | Sort Date and Amount in both directions. | Column and direction announced; focus kept; order correct. |
| AT-04 | Select a row, open S4 details, return to the row. | Announcement, focus and scroll position, action labels. |
| AT-05 | Reach a row outside the rendered window at 1k and 10k rows. | Correct position, no focus loss. |
| AT-06 | Filter, reset, produce an empty result, export CSV. | Result-count announcement; exported rows match the filter. |
| AT-07 | 200 % zoom, 320 px CSS width, high-contrast mode. | Visible focus, no clipped amounts or controls, reflow works. |
| PERF-01 | Capture repeatable production-build browser traces on a named device for both renderers at 1k and 10k. | Load, interaction and long-task traces with cold/warm method; no invented frame rates. |

Defects found in the spike get a production mitigation and an owner in #494 or #378; the
disposable route is never shipped.

### Review record

Add one row per test run, or link a dated external result. Do not mark a row Pass on the basis
of Playwright or the Chromium accessibility tree alone.

| ID | Tester + date | OS / browser / AT / viewport | Renderer + rows | Pass / Fail / Blocked / Not run | Speech, focus, evidence link |
| --- | --- | --- | --- | --- | --- |
| AT-01..AT-07 | Unassigned | — | — | Not run | — |
| PERF-01 | Unassigned | — | — | Not run | — |

## 5. Questions for the reviewer

- Is **B** the most defensible provisional decision, and are A and C rejected for the right
  reasons?
- Do the development-server timings and whole-app bundle totals support the recommendation
  without overclaiming production or assistive-technology results?
- Are the accessibility risks in section 3 complete, and does each have an owner in #494 or #378?
- Does the `*.web.tsx` / `*.native.tsx` split keep authentication, data and caching, formatting,
  authorization and routing genuinely shared?
- Are more automated or human checks needed before Sprint 7?

## 6. Acceptance gates

- [x] Automated synthetic Chromium comparison recorded with raw evidence.
- [x] Renderer-isolated Expo production bundle sizes recorded and scoped.
- [x] Benchmark route and fixtures removed from `main` (PR #500).
- [ ] Independent architecture/UX review of this proposal and its limits.
- [ ] NVDA/VoiceOver and keyboard results (AT-01..AT-07), with failures and mitigations.
- [ ] Production-build browser traces (PERF-01), if the ADR's approval gate requires them.
- [ ] Owners for the production table in #378 / #494.
- [ ] **PO approves Option A, B or C explicitly, with date and accepted residual risks.**
- [ ] ADR 0007 status changed from Proposed to Accepted in a separate change.
- [ ] #384 closed only after its Definition of Done is met.

**PO decision:** Pending. **Signer / date:** Not recorded. **Accepted residual risks:** Not recorded.
