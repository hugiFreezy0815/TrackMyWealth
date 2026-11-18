# #620 one retry for an Account Detail outage (PR #622): web and iOS checks

Date: 2026-11-18. PR head `d4149c89` merged with `main` `a5bddc2c`. Own local stack
(`docker compose -p tmw-pr622 --profile web up -d --build`, API 8090, #536 nginx web image 8092),
synthetic data from `scripts/seed_demo.py` plus 120 bookings on "Demo · Paper Moon Current"
(`../494-a11y-checklist/bulk_bookings.py … 120 120`).

## Web (`outage-web.mjs`, Chrome headless, en-GB, desktop 1280 and phone 390): 20/20

Every API read is refused (connection refused) and the tab becomes visible again, so the app
refetches its stale reads (the app's `staleTime` is 30 s, so the script waits 31 s first).

| Check | Desktop | Phone |
|---|---|---|
| Outage: one "Something went wrong" banner, one "Try again" | Pass | Pass |
| Loaded rows stay ("Showing 100 of 120"), "Load more" disabled | Pass | Pass |
| axe (WCAG 2.0/2.1/2.2 A and AA) in the outage state | 0 violations | 0 violations |
| API back, one "Try again": no banner left, "Load more" enabled, paging to 120 | Pass | Pass |
| No page errors | Pass | Pass |

The same outage with the backend container paused (requests hang, then time out after the app's
four attempts, about 67 s) also showed one banner and recovered with one tap.

## iOS Simulator (iPhone 17, iOS 27.2, Release build of this PR)

Flows `../494-a11y-checklist/ios/01-sign-in.yaml`, `04-refresh-error.yaml`, `05-retry.yaml`, run
by `../494-a11y-checklist/ios/run-494.sh` (backend stopped with `docker stop`, then started):

| Step | Result | Screenshot |
|---|---|---|
| Pull-to-refresh with the backend stopped: one banner, one "Try again" (two before #620) | Pass | `ios-1-outage.png` |
| The first page stays ("Showing 50 of 120"), "Load more" disabled | Pass | `ios-2-rows-kept.png` |
| Backend back, one "Try again": no "Something went wrong" left, list back | Pass | `ios-3-after-one-retry.png` |

Not a check of this PR: with `docker pause` instead of stop, the iOS app could not recover even
after the backend answered again (two taps, more than two minutes), while web and curl recovered
at once. The paused container leaves frozen keep-alive connections that the iOS networking stack
keeps reusing. A real outage refuses or resets connections, so the runner now stops the backend.

## Rerun

```bash
TMW_EMAIL=… TMW_PASSWORD=… APP=http://localhost:<web> ACCOUNT=<account-id> TOTAL=120 OUT=<dir> node outage-web.mjs
```

Run it from a scratch folder with `playwright-core` and `@axe-core/playwright`.
