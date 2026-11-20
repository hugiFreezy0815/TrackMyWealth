# #607 period return: signed-in web check and axe

Date: 2026-11-20, branch `feature/607-period-return`.

**Setup.** An own local stack (`docker compose -p tmw-pr607 --profile web up -d --build`, API on
8094, web export on 8095, `WEB_API_ORIGINS=http://localhost:8094`). It is an empty workspace with
an administrator and two members (EN and DE) and only the synthetic data below, which
`return-web.mjs` enters as the member through the API. Google Chrome headless, locale en-GB,
desktop 1280 px and phone 390 px. Credentials come from the environment, never from the script.

The data, entered step by step (business date 20 Nov 2026):

1. #585's AC1 scenario in the portfolio "Bank". "Established" has held CHF 2,000 since 400 days
   ago. "Recent"'s first balance is CHF 1,000, 30 days ago. Its opening date is then set to
   400 days ago, then to the day of its first balance.
2. AC2: a gold custom asset worth CHF 2,000 since 400 days ago, CHF 2,100 today.
3. AC3: the portfolio "Neo", whose only account has been tracked since 17 Oct 2026 (CHF 5,000).

## Results (`results.txt`)

| Step | API `change` (1Y) | App chip | Screenshot |
|---|---|---|---|
| AC1, no opening date | `percent` 50.00, **`returnPercent` 0.00**, `INCEPTION_UNCERTAIN` | "→ +CHF 1'000.00 · Return 0.0%", past year, note "This change in value may be off … the return … is not affected" | `desktop-1-ac1-without-opening-date.png` |
| AC1, opening date before the period | `percent` 50.00, **`returnPercent` 0.00**, `VALUE_UNKNOWN` | the same | `desktop-2-ac1-opened-before-period.png` |
| AC1, opening date on the first balance | `percent` 50.00, **`returnPercent` 0.00**, no warning | "→ +CHF 1'000.00 · Return 0.0%"; split: capital movements +CHF 1'000.00 | `desktop-3-ac1-opened-on-first-balance.png` |
| AC2, gold grows by 100 | `returnPercent` 2.45 = 100 / (4,000 + 30/365 × 1,000) | "↗ +CHF 1'100.00 · Return +2.5%" in gain colour | `desktop-4-ac2-gold-grows.png` |
| AC3, portfolio tracked since 17 Oct 2026 | `returnFrom` 2026-10-17, `from` 2025-11-20, `returnPercent` 0.00 | "→ +CHF 5'000.00 · Return 0.0%", **since 17 Oct 2026** | `desktop-5-ac3-portfolio-since.png` |
| DE (member language DE) | the same | "Rendite 0.0%", **seit 17.10.2026**, "Vor dem Erfassungsbeginn eines Kontos …" | `desktop-6-de-portfolio-seit.png` |
| Phone 390, Overview 1Y | | chip wraps inside the card, no horizontal scroll (0 px) | `phone-1-overview-1y.png` |
| Console errors (desktop, DE, phone) | | none | |

Before #607, the AC1 rows showed a neutral chip without a percentage (first two rows) or
**+50.0 %** (third row): see `docs/ux/evidence/585-web-check/`.

## axe (WCAG 2.0/2.1 A and AA rules, `axe-results.json`)

On the Overview with the return chip: 0 violations.

## Rerun

Start the stack as above with your own `-p` name and ports. Create an administrator and two
members (language EN and DE) on an empty deployment. Then, from a scratch folder with
`playwright-core` and `@axe-core/playwright`, run:

```
TMW_EMAIL=… TMW_PASSWORD=… TMW_DE_EMAIL=… TMW_DE_PASSWORD=… \
  APP=http://localhost:8095 API=http://127.0.0.1:8094 OUT=<this folder> node return-web.mjs
```

Each run adds its data again, so reset the stack (`down -v`) before a clean rerun.
