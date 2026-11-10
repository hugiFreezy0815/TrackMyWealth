# #492 Import a bank statement (S7): web check

Date: 2026-11-09. Branch `feat/issue-492-statement-import-flow` (PR #543) with its review fixes.

**Setup.** Local stack (`docker compose -p tmw-pr543 up -d --build`, fresh volume) and
`expo start --web` against it. Chrome via Playwright, headless (`statement-import.mjs`). The data
is synthetic: `seed.mjs` creates an administrator, one standard member (EN) with a EUR workspace,
a "DKB" portfolio and a EUR "Giro" cash account, and writes the generated passwords to a scratch
env file. The statement is the committed synthetic fixture
`backend/src/test/resources/import/dkb-girokonto-synthetic.csv` (never a real sample). The compose
stack sets no `IMPORT_ACCOUNT_IDENTIFIER_KEY`, so every upload asks for account confirmation
(`UNAVAILABLE`, `confirmationRequired`).

| Screenshot | What it shows |
|---|---|
| `review-first-desktop.png` | 1280 × 900: first upload, read as "DKB Girokonto CSV" (`PARSED`, no format choice), 2 new rows, 1 skipped row, the future-date warnings, "Import 2 transactions" |
| `done-desktop.png` | Step 3: "2 transactions imported into Giro." |
| `review-second-desktop.png` | The same file again: "This exact file was imported on …", both rows "Possible duplicates" with their switch off, "Import 0 transactions" disabled |
| `review-second-phone-light.png`, `review-second-phone-dark.png` | 390 × 844: the same review, light and dark |

**Results**

- Web overview header "Import statement" → account picker ("DKB · Giro") → file chooser → the
  upload answers `PARSED` and the app goes straight to the review.
- Commit stays disabled until "Yes, this is my account"; then the writes carry the newest
  version: upload, `confirm-account` with `If-Match: "3"`, `commit` with `If-Match: "4"`.
- Second upload of the same file: every row a duplicate and off, commit disabled; Cancel →
  Discard sends `discard` with `If-Match: "4"`. Afterwards the account ledger holds 2
  transactions (once), the batches are `COMMITTED` (2 imported) and `DISCARDED` (0).
- No horizontal scroll at 390 px. No page errors and no console errors.
- axe (WCAG 2.0/2.1/2.2 A and AA) on both reviews: only `document-title`, which is app-wide (see
  489-web-check), not part of #492.

**Found and fixed by this check**

- The rows API names a new row `PARSED` (row statuses `PARSED`, `DUPLICATE`, `ERROR`,
  `SKIPPED`; `status=NEW` is a 400). The app expected `NEW`, so every real review showed "Update
  the app" and blocked the commit; the screen spec said `NEW` too and is corrected.
- `aria-checked` / `aria-disabled` on the row `Switch` landed on react-native-web's role-less
  wrapper (axe `aria-allowed-attr`, critical); the switch's own checkbox input carries the state.
- The page heading "Import statement" showed twice (shell and screen).

**Not covered here**

- iOS / Android: no simulator on this machine; the native CI build covers the app start.
- The error CSV download: the synthetic fixture has no error rows (its unreadable row is
  `SKIPPED`). Covered by unit tests on both platforms (`device-files*.test.ts`).
- Screen reader: optional for the UI Definition of Done (PO, 2026-11-09).
