# #494 S3 accessibility checklist: web and iOS checks

Date: 2026-11-17. `main` at `5a873e9c` (PRs #604, #605 and #615 merged) plus this branch. The
checklist is the #384 spike comment on #494 (2026-11-06) and section 4 of
[`review-handoff.md`](../../../architecture/adr/0007-evidence/review-handoff.md).

**Setup.** An own local stack (`docker compose -p tmw-pr604 --profile web up -d --build`, API on
8090, the #536 nginx production web image on 8092). Synthetic data only: `scripts/seed_demo.py`,
then `bulk_bookings.py` (1,250 bookings on "Demo · Paper Moon Current", a further 10,000 on
"Demo · Cloudberry Current") and `add_uncat.py` (a few uncategorised rows with random names, so
no learned rule categorises them). Google Chrome headless, locale en-GB. Credentials come from
`TMW_EMAIL` / `TMW_PASSWORD` (the demo partner, no MFA). Raw results: `a11y-results.json`
(35/35), `growth-results.json` (8/8).

## Checklist

| Item | Result | How |
|---|---|---|
| Selection follows the keyboard; closing S4 returns focus to the row | Pass | #605 (`../494-web-check/account-detail.mjs`, rerun on this build: arrows and Tab scroll the row into view, focus returns after a related transaction); AT-04 below |
| 200 % zoom and 320 px: no truncated date or amount, no sideways scroll | Pass | AT-07 below, with Chrome's own zoom level (not a device-pixel-ratio simulation) |
| Selected state announced | Pass | `aria-current="true"` on the row's open button (#605); axe 0 violations |
| Quiet live region | Pass | AT-06 below: one polite region, only "Showing {shown} of {total}" |
| Sort menu announces the active order | Pass | AT-03 below; the sort button names the active order, the menu's options are radios with `aria-checked` |
| Focus after a page-0 reload | **Fixed** in this branch, then Pass | See "Focus after the list changes" |
| Load more growth: decide and write it down | **Decided**, one fix | See "Load more growth" |
| Rerun AT-01..AT-07 on the #494 table | Pass, except the screen-reader parts | See "AT-01..AT-07" |
| NVDA + Chrome before the web launch | **Not run** | Needs a Windows machine and a human; open for the PO |
| Core Web Vitals: LCP ≤ 2.5 s, INP ≤ 200 ms, CLS ≤ 0.1 | Pass (lab) | See "Core Web Vitals" |

## AT-01..AT-07 on the production table (desktop 1280 px, 1,250+ bookings)

| Test | Result |
|---|---|
| AT-01 keyboard only | Pass. Tab from the top of the page reaches the first row at stop 11; every stop shows a focus ring; Shift+Tab goes back. |
| AT-02 table with a screen reader | Not run: needs VoiceOver or NVDA (human). axe finds no table or name problems. |
| AT-03 sort, both fields and directions | Pass. Each of the four orders by keyboard: focus returns to the sort control, which names the new order, and the first 20 rows are in that order (amounts by the value in the account currency). |
| AT-04 open S4 and return (keyboard part) | Pass. Enter on row 1,000 opens the panel with focus in it; closing returns focus to the same row. The screen-reader half needs a human. |
| AT-05 move to a row far down | Pass at 1,000 and at 10,024 loaded rows: ArrowDown reaches the last row, it is scrolled into view and keeps focus; the slowest arrow key took 64 ms. |
| AT-06 filter and count | Pass. After filtering, the only filled live region is polite and reads "Showing 8 of 8"; no diagnostic text. S3 has no CSV export, so that part does not apply. |
| AT-07 zoom, narrow width, forced colours | Pass. 320 px; 200 % browser zoom at 640 and 390 CSS px; forced colours (Windows high contrast) with a visible focus ring on 20 Tab stops. At 200 % the table wraps dates ("12 Nov / 2026") and amounts instead of cutting them. |

axe (WCAG 2.0/2.1/2.2 A and AA) has 0 violations on the Uncategorised filter (desktop) and at
320 px; #605's run adds the desktop table, phone light and dark.

## Focus after the list changes

Found by this check: after a category save under the Uncategorised filter the row leaves the
list, and focus fell back to the page body. Now, on web:

- **Category save removes the row:** focus moves to the next row, else the previous one, else the
  account heading (also when the filter falls back to All because the count reached 0). A row
  that stays (All filter) gets focus back from the dialog. The move waits for the dialog's
  `onDismiss`: until its fade-out ends, react-native-web's modal focus trap pulls focus back in.
- **Load more:** focus moves to the first new row. The button disappears on the last page, and
  reading continues where the new rows start; the live region announces the new count.

Browser results: next row, previous row and heading all pass; Load more by keyboard lands on row
51. Native is unchanged; screen-reader focus there is not a requirement (PO, 2026-11-10).

## Load more growth

**Decision:** no cap. Web keeps every loaded row in the semantic table (ADR 0007 Option B); the
native list stays a windowed `SectionList`. Grounds: with rows that re-render only when they
change, 10,000 loaded rows still respond within the INP limit.

Found and fixed: every appended page re-rendered and re-formatted every loaded row, so each page
cost more as the list grew. Rows are now memoised with stable handlers. Timed in the page, from
the click until the new rows are painted (`growth-results-before-memo.json` vs
`growth-results.json`; the earlier run clicked from page script, the later one with the mouse, and a
same-method run after the fix gave 33 / 50 / 96 ms):

| Rows loaded | Before | After | JS heap before | After |
|---|---|---|---|---|
| 1,000 | 69 ms | 46 ms | 70 MB | 30 MB |
| 5,000 | 385 ms | 41 ms | 337 MB | 103 MB |
| 10,024 | 899 ms | 67 ms | 455 MB | 174 MB |

With 10,024 rows the Load more click and a filter switch both stay at about 16 ms of
interaction latency; the filter switch drops the DOM back to one page. Measuring note:
Playwright's role locators compute accessible names over the whole DOM on the main thread (seconds
at 100k nodes), so the growth script clicks with the mouse at coordinates read by plain DOM
queries and times in the page.

## Core Web Vitals (lab, desktop Chrome, no throttling, local stack)

| Metric | Result | Limit |
|---|---|---|
| LCP (first load, the sign-in page) | 120 ms | 2.5 s |
| Account list visible after navigation | 787 ms | 2.5 s |
| CLS over the whole session | 0.017 | 0.1 |
| INP, slowest of about 1,000 key presses and clicks | 40 ms | 200 ms |

The web session lives in memory only, so a direct load of the account URL shows sign-in first; the
account screen is reached by in-app navigation, which LCP does not cover. These are lab numbers,
not field data.

## iOS Simulator (1,250+ bookings)

`ios/run-494.sh` with the Maestro flows in `ios/`: iPhone 17 (iOS 27.2), Release build signed ad
hoc, the same stack (1,287 bookings, 5 uncategorised). All steps pass:

| Step | Result | Screenshot |
|---|---|---|
| Sign in, open the account by deep link | Pass | `ios-494-01-account.png` |
| Scroll only (no button): rows 500–599 appear, so at least ten pages loaded by infinite scroll | Pass | `ios-494-02-after-scroll-paging.png` |
| Uncategorised filter ("Showing 5 of 5"), back to All, pull-to-refresh | Pass | `ios-494-03-uncategorised.png`, `ios-494-04-after-refresh.png` |
| Backend paused, pull-to-refresh: error banners with Retry | Pass | `ios-494-05-refresh-error.png` |
| …the first page stays ("Showing 50 of 1287") and "Load more" is disabled: no page 1 on a stale page 0 | Pass | `ios-494-06-rows-kept-paging-blocked.png` |
| Backend back, Retry: every error clears, the list is back | Pass | `ios-494-07-after-retry.png` |

Flow notes: on iOS a row is one button whose label joins title, type and amounts, so Maestro has to
match the whole label; a swipe moves several tall rows, so the paging step matches a range of rows.
Frame rates were not measured. Seen once during the runs: the
simulator briefly reported "You are offline" with the backend and the internet reachable; the app
showed the offline banner and disabled "Load more" as specified.

Observation (not changed here): one outage shows two "Something went wrong" banners, one for the
account and one for its value, each with its own Retry.

## Screenshots

| File | Shows |
|---|---|
| `desktop-after-focus-checks.png` | After the focus checks: the filter fell back to All, focus on the heading |
| `at07-320.png` | 320 px |
| `at07-zoom200-1280-top.png`, `-list.png` | 200 % zoom, 640 CSS px: header and table |
| `at07-zoom200-780-top.png`, `-list.png` | 200 % zoom, 390 CSS px: header and phone list |
| `at07-forced-colours.png` | Forced colours |

## Rerun

```bash
python3 scripts/seed_demo.py --base-url http://127.0.0.1:<api> --credentials-file <scratch>/demo.json
python3 -I bulk_bookings.py <scratch>/demo.json http://127.0.0.1:<api> <account-id> 1250 8
python3 -I bulk_bookings.py <scratch>/demo.json http://127.0.0.1:<api> <other-account-id> 10000 10000
TMW_EMAIL=… TMW_PASSWORD=… APP=http://localhost:<web> ACCOUNT=<account-id> TOTAL=<rows> OUT=<dir> SCRATCH=<dir> node a11y-494.mjs
TMW_EMAIL=… TMW_PASSWORD=… APP=http://localhost:<web> ACCOUNT=<other-account-id> TOTAL=<rows> OUT=<dir> node growth-494.mjs
```

`a11y-494.mjs` categorises the account's uncategorised rows; add new ones with `add_uncat.py`
before a rerun. Run the scripts from a scratch folder with `playwright-core` and
`@axe-core/playwright`.
