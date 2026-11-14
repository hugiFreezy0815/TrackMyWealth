# #496 Value history (S1 #8–#11, #16, #22; S2 #9): web check

Evidence: 2026-11-13, branch `feat/issue-496-value-history` (PR #562) after the review fixes
(M03 order, warning codes, uncertain change), merged with `main` at `fc134f1c`.

**Setup.** Local stack from this branch (`docker compose -p tmw-pr562 up -d --build`, fresh volume,
backend on 8090) and a web export against it (`EXPO_PUBLIC_API_URL=http://localhost:8090 npx expo
export -p web`, served with `npx serve`). Chrome via Playwright, headless (`history-web.mjs`),
`locale: en-GB`; axe via `@axe-core/playwright` (WCAG 2.0/2.1/2.2 A and AA). All data is synthetic:
`seed.mjs` creates an administrator and one member (EN, EUR workspace), a EUR "Synthetic Bank"
portfolio (Girokonto with monthly salary, rent and groceries Jan–Nov 2026 plus weekly bookings,
Tagesgeld with a transfer) and a CHF "Synthetic CH Bank" portfolio with one account.

Two stages:

1. **uncertain**: right after seeding. The stack has only the last week of ECB rates, so the CHF
   account's value on 13 Oct is unknown and left out of that point's sum. The server sends
   `change.percent = 15.66` and `warningCodes = [ATTRIBUTION_FX_UNAVAILABLE, VALUE_UNKNOWN]`.
2. **settled**: after inserting synthetic EUR/CHF rates (0.93) for 1 Nov 2025 – 5 Nov 2026 into
   the stack's `fx_rate` table. The 1M history is then complete: +EUR 1'885.31, +5.26%, no codes.

Result: uncertain 11/11 and settled 24/24 checks passed; axe found no violations on Overview
(phone, uncertain and settled; desktop dark) and Portfolio View.

| Screenshot | What it shows |
|---|---|
| `uncertain-01-overview-1M-phone.png` | 390 × 844: the possibly distorted change is a neutral chip without a percentage, "past month" beside it, "This change may be off: …", the catalogue warnings (no account names), the chart, then the period chips as the card's last row; tile "+EUR 5'111.12 past month" neutral |
| `settled-01-overview-1M-phone.png` | The complete 1M history: green chip "+EUR 1'885.31 +5.3%" with "past month", the split on one line ("Net saving … · Capital movements … · Market & FX …"), chart, chips; tile change green with its period; portfolio rows +3.1% / +26.7% (the list API's 1M) |
| `settled-02-portfolio-1W-phone.png` | Portfolio View with the period chosen on the Overview (1W), from `GET /portfolios/{id}/history?period=1W` |
| `settled-03-overview-hidden-phone.png` | Hide amounts: chip, split, tile change and chart labels masked; the chart keeps its shape (overview.md "Hidden amounts") |
| `settled-04-overview-320.png` | 320 px wide: no horizontal scroll |
| `settled-05-overview-desktop-dark.png` | 1280 × 900, dark: the W01 layout with the same card order (the white area at the bottom left is an artefact of unrolling the scroll view for a full-page shot) |

Also checked (no screenshot): default 1M; 1W/YTD/1Y/Max each load and render a chart; the 1W
choice is in `localStorage` and restored at the next sign-in; the portfolio row percentage is
hidden for periods other than 1M; offline, a cached period still shows its chart; no page errors.
The iOS and Android builds are checked by Mobile Native CI on the PR.
