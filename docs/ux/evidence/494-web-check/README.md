# #494 Account detail (S3): web check

Initial evidence: 2026-11-09, generated before the S4 integration and native SectionList changes. Branch `feat/issue-494-account-detail` (PR #546).

**Evidence freshness:** The screenshots and findings below describe the original S3 browser-check
run, not a fresh run against the latest third-review commits. They must be regenerated with a
running synthetic-data backend before approval; the CI web export is not a browser screenshot or
an axe audit.

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
| `account-zoom-200.png` | 640 × 450, device scale factor 2: a responsive viewport/DPR simulation, **not** an actual 200% browser zoom check |
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
- Original axe run: `document-title` was the only finding then (also recorded in
  489-web-check); root HTML now sets a title. The browser check must be rerun to verify the
  current app and all assertions rather than assuming the previous result still applies.

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

**Not yet covered by reproducible current evidence:** native authenticated S3/S4 interactions
(account load, 1,000+ transaction scrolling, pagination, category picker, archive/restore and error
recovery) and true 200% browser zoom. Mobile Native CI now
builds both release targets on source changes and saves signed-out launch screenshots, but it does
not log in or exercise the account screens. Any waiver of these checks must be explicitly approved
by the release owner. The only waiver so far: the PO decided on 2026-11-10 that a VoiceOver/TalkBack
check is not required; Jest accessibility tests and the web axe scan cover the labels.

**Re-run checklist:** start the synthetic local backend and web app; run `seed.mjs`, then
`account-detail.mjs` with scratch credentials; save the JSON result and all screenshots. Check
the S4 side panel opening, closing and focus return on desktop, and a real Chrome/Firefox browser
zoom control at 200% (not DPR simulation). On iOS or Android, use a synthetic account with at
least 1,250 bookings to check smooth SectionList scrolling, refresh and errors. Attach dated
evidence and record the tested commit hash before approval.
