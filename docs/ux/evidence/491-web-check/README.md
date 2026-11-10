# #491 Add portfolio (S5) and Add account (S6): web check

Date: 2026-11-09. Branch `feat/issue-491-portfolio-account-first-run` (PR #542) with its review
fixes.

**Setup.** Local stack (`docker compose -p tmw-pr542 up -d --build`, plus an override that sets
`IMPORT_ACCOUNT_IDENTIFIER_KEY`, see "Found on the way") and `expo start --web` against it.
Chrome via Playwright, headless (`add-flow.mjs`, credentials from `TMW_EMAIL` /
`TMW_PASSWORD`). The data is synthetic: a fresh EUR workspace and one standard member with only
the automatic Personal assets portfolio. The catalogue is the shipped seed (V19).

| Screenshot | What it shows |
|---|---|
| `s5-dkb-selected-phone.png` | 390 × 844: search "DKB", the entry picked, the new-tab preview, name and currency |
| `s5-dkb-selected-dark-desktop.png` | 1280 × 900, dark: country groups, type and import formats per entry |
| `s5-currency-search-phone.png` | The ISO currency sheet searching "kron" |
| `s6-step1-phone.png` | S6 step 1: type chips in two groups, the type warning, currency, IBAN |
| `s6-step2-opening-balance-phone.png` | Step 2 by capability: opening balance, date today |
| `overview-after-first-account-phone.png` | Back on the overview: net worth EUR 1'000.00 and the "Account added" toast |
| `s6-credit-card-masked-phone.png` | A typed card number shown masked on blur: `4242 42•• •••• 4242` |
| `s6-invalid-iban-phone.png` | `ACCOUNT_IDENTIFIER_INVALID`: the account exists; the field says why; Save IBAN and Skip |

The tab bar in the middle of some full-page screenshots is an artifact of unrolling the
react-native-web scroll view for the capture.

**Results**

- Acceptance (US-04-11): from "Add your first portfolio", DKB with the kept name, a current
  account with IBAN and an opening balance of EUR 1'000 dated today lead back to the overview
  with net worth "EUR 1'000.00" (as the server sends it). Scripted, the flow takes about 4 s;
  the 3-minute check by a person (NFR-UX-005) is still to be done on a device.
- Requests, as recorded in the browser: `POST /portfolios` `{catalogueInstitutionId, name:
  "DKB", portfolioCurrency: "EUR"}`; `POST /accounts` with only the type's fields; `PUT
  …/identifier` with `If-Match: "0"` and the IBAN compact and upper case; `POST
  …/opening-balance` `{date, balance: "1000", currency: "EUR"}`. A credit card in SEK sends no
  `billingCurrency` (equal to the account currency) and the identifier `424242••••••4242`:
  the full card number appears in no request.
- An own portfolio takes any ISO country (Sweden) and currency (SEK) from the searchable
  sheets; a closed sheet leaves the DOM.
- When an entry is picked, focus moves to the "New tab" heading of the panel.
- No horizontal scroll at 390 px or 1280 px. No page errors; the only console error is the
  browser's log line for the expected 422.
- axe (WCAG 2.0/2.1/2.2 A and AA) on S5, S6 step 1 and the identifier step: only
  `document-title` (app-wide, see 489-web-check).

**Found on the way (not part of #491)**

- `docker-compose.yml` does not pass `IMPORT_ACCOUNT_IDENTIFIER_KEY` to the backend, so on a
  compose deployment every identifier save answers 503 `ACCOUNT_IDENTIFICATION_UNAVAILABLE`.
  The app handles it (banner with correlation id, account kept, Skip), but no IBAN can be
  registered until the variable is wired through.

**Not covered here**

- iOS/Android (no simulator on this machine): the native CI build and a device check.
- A screen reader pass (not required for UI stories, PO 2026-11-10).
- The 3-minute first-run timing by a person.
