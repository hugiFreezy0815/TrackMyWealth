# #384 accessibility and UX audit — 2026-11-06

**Scope:** disposable `spike/384-evidence-only` prototype; static source review, **not** a successful human NVDA/VoiceOver test.
**Architecture:** keep one Expo Router application, with web-specific semantic HTML tables for dense desktop data, native list presentation on mobile. This is a recommendation, not PO approval.

## Facts already measured

- Chromium 141, Linux Actions: 20/20 synthetic cases successful; RNW/DOM × 1,000/10,000 rows × five repeats.
- DOM table: 5 `columnheader` roles; RNW Pressable list: 0. This is a **structural** difference, not a screen-reader pass.
- The test focuses `div[aria-label^="Transaction list."]` directly before ArrowDown/Enter. It does **not** prove a user can reach and navigate virtualized rows using only Tab.
- Filtered CSV, bounded mounted rows and row-detail focus are verified by automation. A production server-paged implementation is **not** verified.

## Issues identified by static code inspection (not yet tested with assistive technology)

| Priority | Observation | Required production behavior |
| --- | --- | --- |
| P1 | The DOM `<th>` has `scope="col"`, but its nested sort `<button>` sets `aria-sort={undefined}`. Sorting direction appears as ↑/↓ text only. | Place `aria-sort="ascending"` / `"descending"` / `"none"` on the appropriate **`<th>`**, not the button; retain an explicit understandable button name and announce sort updates. |
| P1 | Arrow keys are captured by a focusable outer `<div>`, and selection changes the React `scrollY` window without synchronizing the actual scroller's `scrollTop` or moving focus to the selected row. | Pick/document a navigation pattern: native HTML table with row-level action buttons using normal Tab, or a fully implemented ARIA grid with roving focus. Ensure focused/selected row scrolls into view. Do not claim the prototype does this. |
| P1 | Virtualized `<tbody>` contains spacer rows marked `aria-hidden` and only ~25 actual data rows. | Test row count/position narration, navigation to far-off rows, sorting/refresh and focus preservation with NVDA + VoiceOver. Prefer paging with server `PageResponse` (`size=50`) in production; do not infer usability from total=10,000 alone. |
| P2 | The `aria-live="polite"` status text includes frequently changing scroll-event counters and render timings. | Keep diagnostic metrics out of the live region; announce result count only after relevant filter/page changes, avoiding chatter. |
| P2 | The prototype's 42px rows and fixed 504px panel are not evidence of adequate target sizes, 200% zoom, or 320px reflow. | Test responsive behavior against the approved 600px/1024px breakpoints; preserve keyboard focus visibility, category picker access and non-clipped amounts. |
| P2 | DOM `aria-selected` is applied to `<tr>` in a plain table and the selected state is also highlighted by background. | Confirm that the selected row is announced; use robust focus/label/status semantics in the chosen interaction pattern; never convey selection through color alone. |

**UX scope reminder:** Figma W06 is a visual reference, but `docs/ux/screens/account-detail.md` is the behavioral source of truth. The actual account-detail web table has **Date, Description, Category, Amount** (not the spike's five columns). Selecting a row opens S4 in a context rail; filtering/sorting are server driven. Its mobile counterpart uses date-grouped transaction rows, not a squeezed desktop table. Follow #376's Overview + Settings desktop shell, 248px sidebar, 360px nominal contextual rail, and zoom-adaptive layout. Use EN/DE localizations; German uses `du`.

## Human verification checklist (repeat at 1k and 10k; DOM first, RNW second)

Record **OS / OS version, browser / version, screen reader / version, viewport, zoom, renderer, rows**, and whether each assertion passed **and what was announced**:

1. Keyboard only: starting at the address bar, Tab through renderer, count, search, category, sorting and CSV; arrive at first real row action **without programmatically focusing it**. Shift+Tab reverses correctly.
2. Identify the table and all header labels through screen-reader table navigation (DOM). The account-detail production spec ultimately uses four columns.
3. Sort by Date and Amount in both directions; hear the column and sort direction, then verify keyboard focus remains meaningful.
4. Read a row, focus its action, activate detail, hear selected transaction and detail heading, and return to the initiating row.
5. Scroll/jump to a row outside the initial 25–27 rendered rows; ensure keyboard/screen-reader focus is not lost, rows are not falsely announced as all 10k present, and the selected row remains visibly scrolled into view.
6. Filter to Transport: count is announced once; CSV exports all filtered rows (not just the virtual viewport). Test an empty result and reset.
7. At 200% zoom and a 320px CSS viewport, verify no clipped controls/amounts, no inaccessible horizontal content, and visible focus. Retest with forced/high-contrast colors.
8. Repeat key interaction in **NVDA + Chrome/Firefox on Windows** or **VoiceOver + Safari on macOS**; where possible both platforms. Do not assert any of this passed until witnessed.

## Decision gate

Keep ADR 0007 **Proposed**, issue #384 **open**, and experimental branches **unmerged** until (a) human AT results and defects, (b) independently built production bundle-size evidence, (c) any necessary production trace, and (d) explicit dated PO sign-off are recorded. #378 must adopt the chosen table/list platform split and states. Cleanup PR #500 may be merged separately after repository merge-policy checks.
