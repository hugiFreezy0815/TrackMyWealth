# #494 paging restart (PR #604): signed-in web check and axe

Date: 2026-11-17. `main` at `a676f9c4` (PR #604 merged), plus the phone-width fix from this
branch.

**Setup.** An own local stack (`docker compose -p tmw-pr604 --profile web up -d --build`, API on
8090, the #536 nginx web image on 8092). Synthetic data from `scripts/seed_demo.py`, then
`add_bookings.py`, which adds 70 synthetic bookings to "Demo · Paper Moon Current" and gives all
but 12 of them a category: 72 rows, 14 uncategorised. Two of the rows have the same description as
two others, because an interrupted first seed run left them behind; they are separate bookings.
Script: `pagination-web.mjs` (demo partner, no MFA; credentials from `TMW_EMAIL` /
`TMW_PASSWORD`), Google Chrome headless, locale en-GB, desktop 1280 px and phone 390 px. Raw
output: `results.json`.

## Paging restart (spec S3 "Filters and sort", "Paging")

| Check | Desktop 1280 | Phone 390 |
|---|---|---|
| Load both pages, Uncategorised, back to All: "Showing 50 of 72", one request `page=0` | Pass | Pass |
| Load both pages, Oldest first, back to Newest first: "Showing 50 of 72", one request `page=0` | Pass | Pass |
| Page-0 refresh fails (request aborted): cached rows stay, "Try again" shows | Pass | Pass |
| …and "Load more" is disabled; pressing it and scrolling to the end sends no `page=1` | Pass | Pass |
| "Try again" succeeds → "Load more" enabled → "Showing 72 of 72" | Pass | Pass |
| No console error apart from the injected network failure | Pass | Pass |

The failed refresh shows four `page=0` attempts: TanStack Query's default retries.

## Found and fixed by this check

At phone width the web build renders the M25 list inside the page's scroll view. The list is never
height-bound there (its height equals its content), so every row counts as viewable and the
infinite-scroll trigger fired on mount: page 1 loaded without scrolling, and each loaded page could
trigger the next. The spec gives web the "Load more" button; infinite scroll is for the native
app. `transaction-results.web.tsx` no longer passes `onEndReached` to the phone list, and
`transaction-results.web.test.tsx` covers it (it fails without the fix, with a native control).
The cause predates PR #604 (#546).

## axe (WCAG 2.0/2.1/2.2 A and AA rules)

| Screen | Desktop 1280 | Phone 390 |
|---|---|---|
| Account Detail, All | 0 violations | 0 violations |
| Account Detail, Uncategorised | 0 violations | 0 violations |
| Account Detail, failed page-0 refresh | 0 violations | 0 violations |

## Screenshots

| Step | Desktop | Phone |
|---|---|---|
| Page 0 | `desktop-1-page0.png` | `phone-1-page0.png` |
| Failed page-0 refresh, cached rows kept | `desktop-2-failed-refresh.png` | `phone-2-failed-refresh.png` |
| After "Try again" and "Load more" | `desktop-3-after-retry.png` | `phone-3-after-retry.png` |

## Rerun

```bash
python3 scripts/seed_demo.py --base-url http://127.0.0.1:<api-port> --credentials-file <scratch>/demo.json
python3 -I docs/ux/evidence/494-pagination-web-check/add_bookings.py <scratch>/demo.json http://127.0.0.1:<api-port> <account-id>
TMW_EMAIL=… TMW_PASSWORD=… APP=http://localhost:<web-port> ACCOUNT=<account-id> OUT=<scratch> node pagination-web.mjs
```

Run `pagination-web.mjs` from a scratch folder with `playwright-core` and `@axe-core/playwright`
installed. Use `localhost` for the web origin: the backend's default CORS rule allows
`http://localhost:*`.
