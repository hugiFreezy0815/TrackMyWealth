# ADR 0007 — Web client architecture and transaction-table spike

**Status:** Proposed — manual measurements, accessibility audit and PO approval outstanding.  
**Issue:** [#384](https://github.com/hugiFreezy0815/TrackMyWealth/issues/384).  
**Review handoff:** [open acceptance gates, test protocol and review record](0007-evidence/review-handoff.md). It is a checklist, **not** evidence of human accessibility testing or PO approval.  
**Design:** [Figma W06 Transactions & categorisation](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=32-1072) and [M10 Transactions](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=25-797).

## Context

The project already runs **Expo Router 58** for mobile and static web export, with React 19,
React Native Web, generated API types, a shared auth/session client and planned TanStack Query
and FormatJS (ADR 0008). Figma **W06** has a 248px sidebar, filters, a dense sortable
transaction table and contextual detail panel. The project must support 1,000+ rows and
show that 10,000 rows can be used without excessive scrolling, rendering or accessibility
problems.

A decision to introduce a separate React application must have measured benefits that
outweigh duplicating authentication, navigation, data fetching, errors and deployment.
The spike uses wholly invented data; **production uses server-side `PageResponse<T>` paging
and never fetches 10,000 genuine ledger rows merely to sort them in JavaScript**.

## Alternatives

| Approach | Benefits | Risks |
|---|---|---|
| A. RNW rows in Expo web | Shared native/web widgets and toolchain | Difficult desktop table semantics and screen-reader grid navigation; virtualised rows not all present in accessibility tree |
| **B. Semantic React DOM table inside Expo web** | Browser table headers, focus semantics and keyboard behaviour; retains one app, router, API and domain code | Web-specific list presentation and testing; native fallback needed |
| C. Independent React web application | Maximum web independence | Second application, duplicated auth/route/state code and release burden |

**Conditional architecture recommendation: B, but no accepted decision yet.**
Prefer the single Expo app and use a web-specific semantic React DOM grid for dense tables.
Keep React Native views for native phone/tablet, and share typed API, queries, formatting and
mutations. Option A remains valid if its keyboard/accessibility behaviour is equivalent.
Option C requires demonstrated irreducible limitations of A and B, and explicit PO approval.

If B is accepted, place web-only DOM widgets in `*.web.tsx` and native alternatives
in `*.native.tsx` or equivalent platform adapters. Never create a second sign-in
flow, calculate totals on the client, or ship experiment code into the product.
The Figma mockups provide the visual reference; do not ship screenshots as UI assets.

## Reproducible comparison

The disposable comparison is preserved in the historical
[spike commit `42d22d9`](https://github.com/hugiFreezy0815/TrackMyWealth/commit/42d22d9868a808917c8e01494daed5aa9b1909b0)
and [PR #499](https://github.com/hugiFreezy0815/TrackMyWealth/pull/499).
Although that PR was explicitly marked **do not merge**, it was merged on
2026-11-05. The subsequent cleanup removes its experiment route and fixtures
from `main`; it does **not** discard the Git history or the reproducible test
protocol. To run the benchmarks, check out the historical spike commit on a
separate throwaway worktree, not a production application build. The historical
commit contains a web-only route (`/spike-web-transactions`) plus a deterministic
fixture and tests. Both renderers have the **same** 1,000/10,000 synthetic rows, filters,
sorting with stable id tie-break, CSV export, selected-row detail and virtualised
windowing. RNW uses `ScrollView` / `Pressable` / `Text` / `View`;
DOM uses a native `<table>`. The RNW list's button-like semantics are *not*
equivalent to table semantics; testing this is a main purpose of the spike.

See [`mobile/src/spikes/README.md` at the historical spike commit](https://github.com/hugiFreezy0815/TrackMyWealth/blob/42d22d9868a808917c8e01494daed5aa9b1909b0/mobile/src/spikes/README.md)
for repeatable manual steps.
For each of four combinations (RNW/DOM × 1,000/10,000), repeat five cold/warm
runs on fixed hardware/browser/CPU: first paint and long-task/fps traces,
filter/sort interaction latency, DOM memory footprint, keyboard Tab/Arrow/Enter,
NVDA + VoiceOver header/row/selection narration, CSV output and independent
bundle size. Share trace artifacts with reviewers. The spike's on-screen
requestAnimationFrame timing and scroll-event gaps are *diagnostics*, not
reliable Core Web Vitals or a claim about accessibility.

### Automated browser evidence (2026-11-05)

**Reproducible run:** [GitHub Actions #42111099580](https://github.com/hugiFreezy0815/TrackMyWealth/actions/runs/42111099580) — successful. The result JSON is archived in [`0007-evidence/`](0007-evidence/README.md); the run's own artifact expires after 14 days. Code and procedure live at the experiment commit [`fa37fdf8`](https://github.com/hugiFreezy0815/TrackMyWealth/tree/fa37fdf8e0c18dbc4c67983ae4ddbbe0bb209994) (tag `spike-384-evidence`), whose route bypasses authentication **only for the synthetic fixture**. That commit must never be merged into production.

**Runner:** headless Chromium **141.0.7390.37**, Ubuntu Linux x64, 4 reported CPUs, 1440×900 viewport. **Five repetitions per case** (20 completed tests; no browser errors). Both modes used the same deterministic client-side generated dataset, filter/sort/CSV logic, fixed-height windowing and a single Expo **development** server. Results in milliseconds:

| Renderer | Rows | First visible median / p95 | Filter update median / p95 | Mounted after scroll | Accessible column headers / rows |
|---|---:|---:|---:|---:|---:|
| React Native Web | 1,000 | 872 / **7,279*** | 49.1 / 51.6 | 25 | **0 / 0** |
| React Native Web | 10,000 | 846 / 996 | 45.0 / 50.2 | 25 | **0 / 0** |
| React DOM table | 1,000 | 841 / 923 | 37.2 / 42.4 | 27 | **5 / 26** |
| React DOM table | 10,000 | 843 / 910 | 38.0 / 54.5 | 27 | **5 / 26** |

*The first RNW 1,000-row run took 7,279ms and inflated that p95. It included cold development-server compilation/caching; **do not** interpret it as a production renderer regression or a valid cold-load comparison. All other first-visible medians were approximately 0.8–0.9 seconds under the same runner. First-visible measurement is a harness wall-clock metric, **not** Core Web Vitals LCP.*

**Functional assertions:** all 20 cases successfully selected a row with ArrowDown and focused the detail panel with Enter. Filtered CSV exports passed for the four first runs (200 or 2,000 records according to dataset size); all cases kept at most 27 visible/spacer rows mounted. Median recorded long tasks were two in each case, totaling approximately 181–194ms per run; this is not a defensible FPS or perceived-smoothness score. Chromium's accessibility tree exposes five native table column headers and 26 row nodes in DOM mode, but none in the RNW button-row mode. The RNW design would therefore require substantially more semantic work to meet desktop table navigation expectations. The HTML table's sort direction announcement and virtualised offscreen-row usability still need a real AT audit.

**Initial combined bundle evidence (2026-11-05):** the exported development benchmark app contained **8 JS assets totaling 2,126,658 uncompressed bytes**. Both implementations were compiled into that single test build, so this is not an attributable renderer comparison.

**Independent Expo production web exports (2026-11-06):** [successful run #42179686520](https://github.com/hugiFreezy0815/TrackMyWealth/actions/runs/42179686520) builds the *same Expo application* twice using a disposable [renderer-isolation script](https://github.com/hugiFreezy0815/TrackMyWealth/blob/fa37fdf8e0c18dbc4c67983ae4ddbbe0bb209994/mobile/scripts/spike-384-bundle-isolation.mjs): one variant retains the React Native Web transaction list, the other the semantic React DOM table. The script removes the opposite renderer function and JSX branch from each export, checks for the selected renderer marker, uses the same lockfile/shared application code, and restores the original source afterwards.

| Export variant | JS assets | Uncompressed bytes | gzip bytes | Brotli bytes |
|---|---:|---:|---:|---:|
| RNW-only | 8 | 2,124,017 | 533,788 | 428,290 |
| Semantic DOM-only | 8 | 2,124,504 | 534,087 | 428,770 |
| **DOM minus RNW** | 0 | **+487** | **+299** | **+480** |

These are **whole-app export sizes**, not the standalone transaction table's bundle weight or an independently scaffolded React app. Dependencies and shared Expo Router/auth/UI modules remain common; the RNW import in the spike module is not rewritten. Consequently the measured 299-byte gzip difference is *specific to the two isolated branch builds*, not a portable estimate of a future component's size. No material bundle penalty favoring a separate React application is demonstrated. The raw report is archived in [`0007-evidence/`](0007-evidence/README.md).

**Static accessibility/UX review (2026-11-06):** the spike's semantic DOM table exposes column headers, but the nested sort button sets `aria-sort={undefined}` instead of putting a direction on the sorted `<th>`. Automated keyboard tests focus an outer container programmatically; they do not prove keyboard-only Tab traversal into rows. Selection updates the React virtual window without ensuring that native scrollTop and accessible focus follow the selected row. The live status includes frequent scroll/paint diagnostics. These are **review observations** requiring production corrections and human assistive-technology verification, not claims that a real screen-reader test has passed or failed. See the [manual NVDA/VoiceOver acceptance protocol](https://github.com/hugiFreezy0815/TrackMyWealth/blob/fa37fdf8e0c18dbc4c67983ae4ddbbe0bb209994/mobile/src/spikes/ACCESSIBILITY-AUDIT.md).

**Production UX boundary:** the accepted [S3 account-detail spec](https://github.com/hugiFreezy0815/TrackMyWealth/blob/main/docs/ux/screens/account-detail.md) expects a **four-column** web table (Date, Description, Category, Amount) and S4 details in a contextual panel, versus five columns in the intentionally synthetic spike. Apply the shared API, localization, visual tokens, server paging and permission logic in both platform-specific presentations; align the component inventory in #378.

### Production-build traces (PERF-01, 2026-11-06)

The spike at tag `spike-384-evidence` was built with `expo export -p web`, served from `127.0.0.1` and measured in Google Chrome 156 (headless) on an Apple M5 MacBook (10 cores, 24 GB, macOS 26.7.1), 1440×900, with no throttling and with 4× CPU throttling as a stand-in for a slower laptop. Five cold (empty cache) and five warm loads per case; one Chrome trace per case. Medians in milliseconds ([results](0007-evidence/perf-01-results.json), [traces](0007-evidence/perf-01-traces)):

| Renderer | Rows | First visible (cold) | First visible, 4× CPU | Filter | Filter, 4× CPU | Scroll frames > 50 ms (median) |
|---|---:|---:|---:|---:|---:|---:|
| React Native Web | 1,000 | 78 | 265 | 57 | 67 | 0 |
| React Native Web | 10,000 | 107 | 383 | 58 | 58 | 0 |
| React DOM table | 1,000 | 78 | 259 | 52 | 57 | 0 |
| React DOM table | 10,000 | 96 | 368 | 45 | 56 | 0 |

In the production build both renderers are close to each other. The development-server table above ran on different hardware (4-CPU CI runner, Chromium 141), so the two tables cannot be compared to isolate the effect of the production build. While scrolling, the median run has no frame over 50 ms; 5 such frames occurred in 3 of 80 runs, all unthrottled cold loads (RNW 1k: 3, RNW 10k: 1, DOM 1k: 1). Every load logs React error #418 (hydration mismatch) because the spike reads `?renderer`/`?rows` only in the browser; this affects both renderers equally. Not covered: low-end Windows/Android hardware, a real network, server paging. Automated keyboard and zoom checks on the same build are recorded in the [review handoff](0007-evidence/review-handoff.md#review-record); they confirmed that the spike does not scroll the selected row into view and truncates amounts at 200 % zoom, in both renderers.

**Interpretation:** Both implementations handle the synthetic 10,000-row test with bounded DOM. First-visible times cannot rank the renderers: a repeat of the development-server test in run [#42179686520](https://github.com/hugiFreezy0815/TrackMyWealth/actions/runs/42179686520) measured roughly double the times above, with DOM the slower one (1,735–1,808 ms vs 1,503–1,629 ms), and the production build shows them within about 10 %. React DOM offers browser-native table semantics and modestly lower median filter-update time at 10,000 rows (38 vs 45ms); differences of these magnitudes in a development-server benchmark are **not** proof of production speed superiority. The evidence strengthens **conditional option B (semantic DOM table inside the existing Expo web app)**, not a second standalone React application.

**Screen readers:** VoiceOver (macOS 26.7.1, Safari 27.0) passed AT-01..AT-04 on 2026-11-06 in both renderers, except AT-02 in RNW mode, which was not run because the RNW list exposes no table; see the [review record](0007-evidence/review-handoff.md#review-record). Keyboard-only selection scrolling (AT-05) and 200 % zoom (AT-07) still fail in the spike and are production requirements for #494.

**Not yet tested:** NVDA announcements, performance on low-end Windows/Android hardware, and actual server-paged transaction queries. Production-build traces and automated keyboard/zoom checks were added on 2026-11-06 (see above). **Independent renderer-isolated Expo production web exports have been measured;** full production integration is not implied. The prototype is intentionally not a pixel-perfect Figma W06 implementation and must not enter production. ADR status remains **Proposed** pending the explicit PO decision and those acceptance checks.

Do not infer missing measurements from the Figma render, synthetic row count or green CI.

## Approval gate

- Full functionality parity for filtering, server-side pageable/sortable real data,
  keyboard navigation, detail selection and CSV of **filtered** rows.
- Real NVDA/VoiceOver checks, with defects and mitigations recorded, not merely
  an automated accessibility pass.
- Browser performance traces with hardware, browser version and repeat count.
- Explicit decision A/B/C and why rejected alternatives were rejected;
  update #378's component/library inventory.
- **PO approval** and date; only then change this ADR's status to Accepted and close #384.

**Do not reintroduce** the throwaway experiment into `main`. Its historical
commit is retained so the benchmark can still be run; production keeps only
this **Proposed** ADR until measurements, accessibility audit and PO review are
complete. Green CI is necessary but cannot replace human performance and
assistive-technology verification.
