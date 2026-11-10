# #494 Account detail (S3): web check

Date: 2026-11-09. Branch `feat/issue-494-account-detail` (PR #546) with its review fixes.

**Setup.** Local stack (`docker compose -p tmw-pr546 up -d --build`, fresh volume, backend published
on 8090 so another stack can keep 8080) and `expo start --web --port 8082` against it. Chrome via
Playwright, headless (`account-detail.mjs`); axe via `@axe-core/playwright` (WCAG 2.0/2.1/2.2 A and
AA). All data is synthetic: `seed.mjs` creates an administrator, one standard member (EN, EUR
workspace), a "DKB" portfolio with a EUR "Girokonto" (CASH) and a "Tagesgeld" (SAVINGS) account,
an opening balance, 60 hand-entered bookings (four left uncategorised), an internal transfer to
Tagesgeld, an import of the committed synthetic fixture
`backend/src/test/resources/import/dkb-girokonto-synthetic.csv` with one imported booking then
voided (it gets a reversal row), and a statement snapshot that does not match the ledger. The
generated passwords go to a scratch env file. Result: 64 transactions, reconciliation
`OPEN_DIFFERENCE`.

| Screenshot | What it shows |
|---|---|
| `account-phone-light.png` | 390 × 844: header ("DKB · Current account · EUR"), balance, "Differs from statement" badge, "⚠ Differs from the statement of 31 Oct 2026 by EUR 724.56", chips "All" / "Uncategorised 4", the banner, date-grouped M25 list, "Showing 50 of 64 · Load more" |
| `account-phone-dark.png` | The same in dark mode |
| `account-320.png` | 320 px wide: no horizontal scroll |
| `account-zoom-200.png` | 640 × 450 at device scale 2 (1280 px at 200 % zoom): no horizontal scroll |
| `account-desktop.png` | 1280 × 900: the W06 table (Date, Description, Category, Amount) with the voided row struck through, "Reversal of 28 Jan 2031", "Internal transfer · excluded", uncategorised chips in warning style |
| `account-desktop-after-flows.png` | After load more, a category change, the amount sort, archive and restore |

**Results**

- Every visible text comes from the catalogue: no API codes (`EXPENSE`, `CASH`, …) on screen.
- Paging: 50 rows, then "Load more" → "Showing 64 of 64".
- Uncategorised: the banner's "Review" turns the filter on ("Showing 4 of 4"); a category change
  sends `PUT …/category` with `If-Match: "1"`, the list reloads page 0 with
  `uncategorized=true`, and the chip reads "Uncategorised 3" from the reloaded server count.
- Sort "Largest amount" requests `page=0 … sort=amount,desc`.
- Archive sends `POST …/archive` with `If-Match: "0"`; the banner reads "Archived on 9 Nov 2026 …"
  with "Restore the account to change it.", category chips are no longer buttons and "Import
  statement" is disabled. Restore sends `POST …/restore` with `If-Match: "1"`.
- Keyboard: Enter on "Account actions" opens the dialog with focus on its first button; Tab stays
  inside (Import statement → Archive account → Close); Escape closes it and focus returns to
  "Account actions".
- No horizontal scroll at 390, 320, 200 % zoom and 1280 px. No page errors, no console errors.
- axe (phone light, phone dark, desktop): only `document-title`, which is app-wide (see
  489-web-check), not part of #494.

**Found and fixed by this check**

- Escape did not close a dialog on web (react-native-web's `Modal` ignores it): the shared
  `Dialog` now closes on Escape (`useEscapeKey`).
- The web table used the browser's default black serif text, unreadable in dark mode; it now takes
  the theme's font and colours. At phone width the web build shows the M25 list instead of
  squeezing the four-column table.
- The row chip showed the server's category name "Uncategorized"; it now reads the app's
  "Uncategorised" / "Nicht kategorisiert" as the spec says.
- The category picker (about 30 categories) grew taller than the window without scrolling, so its
  title and Close were off screen; its list now scrolls inside the dialog.

**Not covered here.** Native iOS/Android: see the Mobile Native CI run on the PR. Screen reader:
optional for the UI DoD (PO, 2026-11-09).
