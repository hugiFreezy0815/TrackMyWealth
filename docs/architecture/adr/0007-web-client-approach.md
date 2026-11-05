# ADR 0007 — Web client architecture and transaction-table spike

**Status:** Proposed — manual measurements, accessibility audit and PO approval outstanding.  
**Issue:** [#384](https://github.com/hugiFreezy0815/TrackMyWealth/issues/384).  
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

[Throwaway branch `feature/384-web-client-spike`](https://github.com/hugiFreezy0815/TrackMyWealth/tree/feature/384-web-client-spike)
and [draft, **do-not-merge PR #499**](https://github.com/hugiFreezy0815/TrackMyWealth/pull/499)
contain a web-only route (`/spike-web-transactions`) plus a deterministic fixture
and tests. Both renderers have the **same** 1,000/10,000 synthetic rows, filters,
sorting with stable id tie-break, CSV export, selected-row detail and virtualised
windowing. RNW uses `ScrollView` / `Pressable` / `Text` / `View`;
DOM uses a native `<table>`. The RNW list's button-like semantics are *not*
equivalent to table semantics; testing this is a main purpose of the spike.

See `mobile/src/spikes/README.md` on that branch for repeatable manual steps.
For each of four combinations (RNW/DOM × 1,000/10,000), repeat five cold/warm
runs on fixed hardware/browser/CPU: first paint and long-task/fps traces,
filter/sort interaction latency, DOM memory footprint, keyboard Tab/Arrow/Enter,
NVDA + VoiceOver header/row/selection narration, CSV output and independent
bundle size. Share trace artifacts with reviewers. The spike's on-screen
requestAnimationFrame timing and scroll-event gaps are *diagnostics*, not
reliable Core Web Vitals or a claim about accessibility.

### Results — not yet collected

| Renderer | Rows | Initial median/p95 (ms) | Long tasks, scrolling | Keyboard + screen reader | JS bundle |
|---|---:|---|---|---|---|
| RNW | 1,000 | **unmeasured** | unmeasured | untested | unmeasured |
| RNW | 10,000 | **unmeasured** | unmeasured | untested | unmeasured |
| React DOM | 1,000 | **unmeasured** | unmeasured | untested | unmeasured |
| React DOM | 10,000 | **unmeasured** | unmeasured | untested | unmeasured |

Do not infer numbers from the Figma render, test fixture size, or green CI.
Also do not compare a virtualised renderer with a non-virtualised renderer:
that would measure a different variable.

## Approval gate

- Full functionality parity for filtering, server-side pageable/sortable real data,
  keyboard navigation, detail selection and CSV of **filtered** rows.
- Real NVDA/VoiceOver checks, with defects and mitigations recorded, not merely
  an automated accessibility pass.
- Browser performance traces with hardware, browser version and repeat count.
- Explicit decision A/B/C and why rejected alternatives were rejected;
  update #378's component/library inventory.
- **PO approval** and date; only then change this ADR's status to Accepted and close #384.

**Do not merge** the throwaway spike branch. Only the approved ADR should
land on `main`. Green CI is necessary but cannot replace human performance
and assistive-technology verification.
