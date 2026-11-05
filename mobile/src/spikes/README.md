# Web transaction list comparison — issue #384

This is **throwaway experimental code**. It belongs to the `feature/384-web-client-spike` branch
and **must not be merged into production**. It uses invented rows only and no API credentials.

## Source design

[Figma TrackMyWealth](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth)
**W06 Transactions & categorisation** (node `32:1072`), 1440px desktop with 248px
sidebar, filtering controls, a dense transaction table, and contextual right-hand detail panel;
**M10** node `25:797` informs smaller-screen interaction. The spike intentionally compares
list semantics, smoothness and bundle cost, not a pixel-perfect visual implementation.

## Start / compare

```bash
cd mobile
npm ci
npx expo start --web
# Visit http://localhost:8081/spike-web-transactions
```

The port printed by Expo is authoritative when 8081 is occupied. This route exists only
on the throwaway branch. Use the **same** browser, zoom, screen resolution, CPU throttle,
dataset size and filters for both renderer modes. The built-in controls expose the same:
deterministic 1,000/10,000 transactions; text/category filtering; column sorting (all modes);
selected-row details; CSV of the filtered set; keyboard up/down, Enter, Tab; and only ~25
rows mounted at once via fixed-height virtualisation. Native Web uses React Native
`View`, `Pressable`, `ScrollView`, `Text`; DOM mode uses a semantic HTML
`<table>` and native `<button>`.

**Important limitation:** the react-native-web benchmark uses a button-like row rather than a
semantic ARIA grid, while the DOM benchmark has native table semantics. That is **part
of the accessibility comparison**, not a claim of parity. Both share a DOM keyboard handler
for selection; native pointer/row-focus and real VoiceOver/NVDA behaviour still need human
testing. The scroll-event gap count is not an FPS metric: browser Performance tooling is
the definitive trace.

## Protocol — record actual results, do not invent measurements

For each renderer × row count (four combinations), use a fresh page reload,
repeat 5 times with a fixed browser build/hardware, and record median and p95:

1. **Initial render / LCP:** Performance panel trace, then the spike's paint-callback
   metric for context (not a Web Vital).
2. **Scrolling:** scroll through 10 seconds, inspect long tasks, frame drops,
   memory/DOM node count; repeat with DevTools CPU throttling at 4×.
3. **Search and sort:** change category/text/sort in the same order; time
   interaction to next paint and confirm all results and stable tie-breakers.
4. **Keyboard:** Tab from filter to list, arrows select a row, Enter focuses the
   detail pane, Escape closes overlays if any. Test with keyboard only.
5. **Screen readers:** NVDA + Firefox/Chrome (Windows); VoiceOver + Safari
   (macOS). Verify table headers, selected row, row counts, virtualised
   scroll position and detail-panel announcement; record failures, not a
   generic 'passes a11y' statement.
6. **Bundle:** `npx expo export -p web` and record the emitted JS sizes;
   the two renderers are in one experiment so it is **not a valid per-renderer
   bundle-size comparison**. For that, build one renderer at a time or analyse
   imports via source maps, keeping identical shared code.
7. **CSV:** export after filtering; check that the row count matches the
   filtered dataset (not just the virtual viewport); open in a spreadsheet.

Sample results table for human completion:

| Renderer | Rows | Initial median/p95 ms | Scroll long tasks / frame drops | Filtering ms | A11y observations | Bundle bytes |
|---|---:|---|---|---|---|---|
| React Native Web | 1,000 | **not measured** | not measured | not measured | not tested | not measured |
| React Native Web | 10,000 | **not measured** | not measured | not measured | not tested | not measured |
| React DOM | 1,000 | **not measured** | not measured | not measured | not tested | not measured |
| React DOM | 10,000 | **not measured** | not measured | not measured | not tested | not measured |

## Decision criteria for ADR 0007

Prefer **Expo web** unless the measured, accessible implementation fails one
of the must-haves (smooth 10k virtualised rows, reliable keyboard/screen-reader table
semantics, desktop sorting/filtering/detail, maintainable code). When Expo web is
adequate but RNW data-grid semantics are not, **use DOM table on web within the
same Expo app**: it preserves Expo Router, auth, types, session handling and
shared domain logic without starting a second web client.

A separate React web application should require documented irreducible limitations
and an explicit PO approval. See ADR draft in the architecture PR.

Do not enter performance numbers or claim PO acceptance until measured.
