# #493 converted balance in portfolio account rows (PR #606): signed-in web check

Date: 2026-11-17. PR head `7ebd7a14`.

**Setup.** Own local stack (`docker compose -p tmw-pr606 --profile web up -d --build`, ports
moved with a scratch override), which serves the production web export through the #536 nginx
image. Synthetic data: `scripts/seed_demo.py`, then `seed_606.py`, which adds two CHF accounts to
the EUR portfolio "Demo · Paper Moon Bank": "Glacier Franc Current" with an opening balance of
CHF −100.00 (the server converts it to EUR −108.31) and "Glacier Franc Savings" without any
balance (`valueKnown=false`, `UNKNOWN`). A German-language member was added through
`/admin/users`. Script: `converted-balance-web.mjs` (credentials from `TMW_EMAIL` /
`TMW_PASSWORD`), Google Chrome headless. Raw output: `results.txt`, `axe-results.json`.

## Results

| Check | EN phone 390 | EN desktop 1280 | DE phone (de-DE) | DE phone (de-CH) |
|---|---|---|---|---|
| Foreign row's accessible name includes the converted balance | Pass | Pass | Pass | Pass |
| Foreign row shows "≈ −EUR 108.31" | Pass | Pass | Pass | Pass |
| Same-currency row has no conversion in its name | Pass | Pass | Pass | Pass |
| Unknown row: "Value unknown", no conversion, exactly one "–", not in loss colour | Pass | Pass | Pass | Pass |
| Hide amounts: row name says "hidden" once, no figure in page text or any `aria-label` | Pass | Pass | Pass | Pass |
| axe (WCAG 2.0/2.1/2.2 A + AA), Portfolio View | 0 violations | 0 violations | 0 violations | 0 violations |
| No console error | Pass | Pass | React #418 (pre-existing) | React #418 (pre-existing) |

Accessible names as read:

- EN: "Demo · Glacier Franc Current, minus 100.00 Swiss francs, approximately minus 108.31
  euros, Current account"
- de-DE: "…, minus 100,00 Schweizer Franken, ungefähr minus 108,31 Euro, Girokonto"
- de-CH: "…, minus 100.00 Schweizer Franken, ungefähr minus 108.31 Euro, Privatkonto"
- Hidden: "Demo · Glacier Franc Current, hidden, Current account"
- Unknown: "Demo · Glacier Franc Savings, Value unknown, Savings"

**Control.** The same German flow against a web image built from `main` (`d0dc2bc6`) on the same
backend: the foreign row reads "…, minus 100,00 Schweizer Franken, Girokonto" (no converted
balance), so the check fails without this PR. The React #418 hydration error (text mismatch)
appears there too: it is not caused by this PR. It occurs only for German members after sign-in
(the sign-in page alone is clean in en-GB and de-DE).

## Screenshots

| File | What |
|---|---|
| `en-phone-1-rows.png`, `en-desktop-1-rows.png` | Converted "≈" line, single "–" for the unknown row |
| `en-phone-2-hidden.png`, `en-desktop-2-hidden.png` | Hide amounts on |
| `de-DE-phone-1-rows.png`, `de-DE-phone-2-hidden.png`, `de-CH-phone-1-rows.png` | German member |

**Not covered.** iOS/Android: the CI builds of the PR are green; no device run.
