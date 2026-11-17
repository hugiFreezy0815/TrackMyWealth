# #496 Transaction conversion and bank code (S4 #2, #14): web check

Evidence: 2026-11-16, branch `sprint-7/496-transaction-conversion` (PR #597) at `521dc6b0`.

**Setup.** Own Compose stack from this branch with the web image
(`docker compose -p tmw-pr597 --profile web up -d --build`, fresh volume, backend on 8190, web
on 8191, `WEB_API_URL=http://localhost:8190`), seeded with `scripts/seed_demo.py`. All data is
synthetic. `seed_conversion.py` then adds, as the demo partner:

- a manual EXPENSE of −EUR 84.35 on the CHF account "Demo · Cloudberry Current" with
  `fxRateToAccountCurrency = 0.951234`; the server returns `amountInAccountCurrency = −80.2366`;
- a synthetic camt.053.001.08 import into the EUR account "Demo · Paper Moon Current" with one
  card payment, bank code `PMNT / CCRD / POSD`.

`conversion-web.mjs` signs in through the web app (Chrome via Playwright, headless,
`locale: en-GB`), opens each transaction by client-side navigation and runs axe
(`@axe-core/playwright`, WCAG A/AA).

Result: 13/13 checks passed. axe found no serious or critical violations. The moderate ones
(`landmark-one-main`, `page-has-heading-one`, `region`) are app-shell findings that every screen has.

| Screenshot | What it shows |
|---|---|
| `01-conversion-phone.png` | 390 × 844, light: Details open, "Converted amount ≈−CHF 80.24 at 0.951234" as one row; no separate FX rate row |
| `02-conversion-hidden-phone.png` | Hide amounts on: the label stays, the amount shows as "••••" and the rate is left out |
| `03-bank-code-desktop-dark.png` | 1280 × 900, dark: the imported EUR booking on a EUR account. The bank code is in monospace, and no conversion row is shown for a same-currency booking |

Also checked (no screenshot):
- the conversion row is not rendered before Details is opened;
- its `aria-label` is "approximately minus 80.24 Swiss francs at 0.951234";
- with amounts hidden, neither the amount nor the rate appears in any text or `aria-label`;
- the computed font of the bank code is the monospace stack, different from the label's;
- no horizontal scroll at 390 px, and no console errors.

German copy is covered by the Jest tests ("… zum Kurs …"); the demo members use English. The iOS
and Android builds are checked by Mobile Native CI on the PR.
