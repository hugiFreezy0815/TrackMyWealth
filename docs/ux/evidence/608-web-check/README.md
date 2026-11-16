# #608 Import date in the S4 change history: web check

Evidence: 2026-11-16, branch `fix/608-localize-import-history-date` (PR #609) at `11bc39ec`.

**Setup.** Own Compose stack from this branch with the web image
(`docker compose -p tmw-pr609 --profile web up -d --build`, fresh volume, backend on 8190, web
on 8191), seeded with `scripts/seed_demo.py`. All data is synthetic. `seed_camt_import.py` imports
one synthetic camt.053 card payment into "Demo · Paper Moon Current", which gives the transaction
an `IMPORTED` history entry. A second member with language DE was created via
`POST /api/v1/admin/users`, because the app language follows the member's profile.

`history-date-web.mjs` signs in through the web app (Chrome via Playwright, headless), opens the
imported transaction by client-side navigation and compares the history line with the calendar day
of `importBatch.uploadedAt` (here `2026-11-16T20:50Z`), computed in the script for the browser's
time zone.

Result: 11/11 checks passed. axe found no serious or critical violations. The moderate ones
(`landmark-one-main`, `page-has-heading-one`, `region`) are app-shell findings that every screen has.

| Screenshot | What it shows |
|---|---|
| `01-history-en-phone.png` | 390 × 844, en-GB, Europe/Zurich: "Imported from CAMT statement on 16 Nov 2026" |
| `02-history-de-desktop-dark.png` | 1280 × 900, dark, DE member: "Aus CAMT-Auszug vom 16.11.2026 importiert" |
| `03-history-en-utc14.png` | Device time zone Pacific/Kiritimati (UTC+14): the same upload is 17 Nov there, and the line says "17 Nov 2026" |

Also checked: no `YYYY-MM-DD` date anywhere on the page, and no console errors. The midnight case,
unreadable timestamps and an entry without an import batch are covered by the Jest tests; the
iOS and Android builds are checked by Mobile Native CI on the PR.
