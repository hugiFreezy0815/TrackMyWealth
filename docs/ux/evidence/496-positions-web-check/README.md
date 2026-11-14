# #496 holdings navigation (PR #573): signed-in web check and axe

Date: 2026-11-14. PR head `e0375d1b` merged with `main` at `d5ce9295`.

**Setup.** An own local stack (`docker compose -p tmw-pr573 --profile web up -d --build`), which
also serves the production web export through the #536 nginx image. Synthetic data from
`scripts/seed_demo.py`, plus `add-holdings.py`, which gives the demo depot a later snapshot with
six holdings so that the depot card shows **Show all 6 positions** (five holdings are shown
inline). Script: `positions-web.mjs` (credentials from `TMW_EMAIL` / `TMW_PASSWORD`; the demo
partner, who has no MFA), Google Chrome 157 headless, locale en-GB, desktop 1280 px and phone
390 px. Raw output: `results.txt`, `axe-results.json`.

## Navigation

| Check | Desktop 1280 | Phone 390 |
|---|---|---|
| Portfolio View shows **Show all 6 positions** on the depot card | Pass | Pass |
| Tapping a holding opens the depot (`?tab=positions`) with **Positions** selected and all 6 holdings | Pass | Pass |
| **Show all 6 positions** opens the same depot with **Positions** selected and all 6 holdings | Pass | Pass |
| The depot's account row opens it on **Activity** (no holdings listed) | Pass | Pass |
| **Positions** then **Activity** switches back to the activity list | Pass | Pass |
| No console error | Pass | Pass |

Wide screens show the holdings as a table of links and phones as a list of buttons; both open the
Positions tab. The demo depot has no bookings, so Activity shows its empty state ("No
transactions yet").

Control: the same script against a web image built from `main` stops at the first holding tap,
because the URL has no `?tab=positions` and the depot opens on Activity.

## axe (WCAG 2.0/2.1 A and AA rules)

| Screen | Desktop 1280 | Phone 390 |
|---|---|---|
| Portfolio View | 0 violations | 0 violations |
| Account Detail, Positions | 0 violations | 0 violations |
| Account Detail, Activity | 0 violations | 0 violations |

## Screenshots

| Step | Desktop | Phone |
|---|---|---|
| Portfolio View, depot card | `desktop-1-portfolio.png` | `phone-1-portfolio.png` |
| After tapping a holding | `desktop-2-holding-positions.png` | `phone-2-holding-positions.png` |
| After **Show all 6 positions** | `desktop-3-show-all-positions.png` | `phone-3-show-all-positions.png` |
| After tapping the account row | `desktop-4-row-activity.png` | `phone-4-row-activity.png` |
| Positions, then back to Activity | `desktop-5-back-to-activity.png` | `phone-5-back-to-activity.png` |

## Rerun

```bash
python3 scripts/seed_demo.py --base-url http://127.0.0.1:<api-port> --credentials-file <scratch>/demo.json
python3 docs/ux/evidence/496-positions-web-check/add-holdings.py <scratch>/demo.json http://127.0.0.1:<api-port>
TMW_EMAIL=… TMW_PASSWORD=… APP=http://localhost:<web-port> OUT=<scratch> node positions-web.mjs
```

Run `positions-web.mjs` from a scratch folder with `playwright-core` and `@axe-core/playwright`
installed. Use `localhost` for the web origin: the backend's default CORS rule allows
`http://localhost:*`.
