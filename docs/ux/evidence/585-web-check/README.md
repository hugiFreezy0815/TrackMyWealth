# #585 account opening date (PR #596): signed-in web check and axe

Date: 2026-11-16. PR head `c2714235` (merged with `main` at `8547b507`) plus the step 2 fix in
this commit.

**Setup.** An own local stack (`docker compose -p tmw-pr596 --profile web up -d --build`, API on
8090, web export served by the #536 nginx image on 8091). Synthetic data from
`scripts/seed_demo.py`; every run creates its own portfolio. Signed in as the demo **partner**
(no MFA). Google Chrome 157 headless, locale en-GB, desktop 1280 px and phone 390 px.
Credentials come from the environment, never from the scripts.

## S6 Add account and S3 Account detail (`opening-date-web.mjs`, `results.txt`)

| Check | Result |
|---|---|
| S6: "Opened on (optional)" ends at the phone's today (`max`) and shows the hint about earlier bookings | Pass |
| S6: a future date shows "Choose today or an earlier date." and disables **Add account**; today is accepted | Pass |
| S6: `POST /accounts` carries `openedAt` | Pass |
| S3: the header shows "Opened on 1 Jan 2025"; the editor opens with the saved date; Save is disabled while unchanged | Pass |
| S3: `PUT` carries `If-Match` and keeps name, type and currency; the server stores the date as picked | Pass |
| S3: after the save, Overview and Portfolio View read their history again | Pass |
| Conflict: a rename from another client while the editor is open shows the conflict dialog; **Review and save again** keeps the draft date and saves with the new version and the new name | Pass |
| Clear: emptying the date and saving removes it | Pass |
| Archived account: **Edit opening date** is disabled ("Restore the account to change it.") | Pass |
| API: the phone's today in the earliest zone (UTC+14) is accepted and stored as sent | Pass |
| API: a date that is not today in any zone yet gets 400 `VALIDATION_FAILED` on `openedAt` | Pass |
| API: the message is localized; a member with language DE gets "muss in der Vergangenheit oder Gegenwart liegen" (`de-message.mjs`) | Pass |
| Phone 390: no horizontal scroll on S6 | Pass |
| No console error (the deliberate 412 of the conflict step is not counted) | Pass |

## S6 step 2 starting date (`starting-date-web.mjs`)

Found during this check: with an opening date of 1 Jun 2025 in step 1, step 2 let the member pick
a starting date of 1 Jan 2025. The server rejects that (422 "date cannot be before the account
was opened."), and the app showed only "This is not possible with these entries." Fixed in this
commit: the Starting date now starts at the opening date (`min=2025-06-01`), an earlier date shows
"Choose the opening date or a later date." and disables **Save starting value**; 1 Jun 2025 saves
(201).

## AC1: the 1Y chip (`ac1-web.mjs`, `ac1-results.txt`)

Own CHF portfolio: account A holds CHF 2,000 since 400 days ago; account B's first evidence is an
opening balance of CHF 1,000 30 days ago. B's opening date is then set in the app (S3).

| B's opening date | `warningCodes` (1Y) | API `percent` | App chip | Screenshot |
|---|---|---|---|---|
| none | `INCEPTION_UNCERTAIN` | 50.00 | neutral, no %, "This change may be off" | `desktop-10-ac1-without-opening-date.png` |
| 400 days ago, before the period (AC1's example) | `VALUE_UNKNOWN` | 50.00 | **neutral, no %**, "This change may be off" | `desktop-10-ac1-opened-before-period.png` |
| the day of the first evidence | none | 50.00 | **+50.0 %** | `desktop-10-ac1-opened-on-first-evidence.png` |

With an opening date before the first evidence, the server swaps `INCEPTION_UNCERTAIN` for
`VALUE_UNKNOWN`: B existed but its value between the opening date and the first evidence is
unknown. The app keeps the chip neutral for `VALUE_UNKNOWN` too (overview.md, item 8), so AC1's
example ("the chip shows the percentage") is **not met**. The date takes the warning away only
when it is on or after the first evidence. An API probe gave the same for an opening date inside
the period (`VALUE_UNKNOWN`, `CAPITAL_MOVEMENTS_UNAVAILABLE`) and after the first evidence (no
warning). Open PO point in PR #596.

## axe (WCAG 2.0/2.1 A and AA rules, `axe-results.json`)

| Screen | Desktop 1280 | Phone 390 |
|---|---|---|
| S6 Add account with the date field | 0 violations | 0 violations |
| S3 Edit opening date dialog | 0 violations | 0 violations |

## Screenshots

| Step | File |
|---|---|
| S6 with a future date | `desktop-1-add-account-future.png` |
| S6 with 1 Jan 2025 | `desktop-2-add-account.png`, `phone-1-add-account.png` |
| S3 header "Opened on 1 Jan 2025" | `desktop-3-account-detail.png`, `phone-2-account-detail.png` |
| S3 editor | `desktop-4-edit-dialog.png`, `phone-3-edit-dialog.png` |
| Conflict dialog | `desktop-5-conflict.png` |
| After clearing | `desktop-6-cleared.png` |
| Archived: actions menu | `desktop-7-archived-actions.png` |
| S6 step 2 with a starting date before the opening date | `desktop-11-step2-before-opening-date.png` |
| AC1 chip | `desktop-10-ac1-*.png` |

## Rerun

```bash
python3 scripts/seed_demo.py --base-url http://127.0.0.1:<api-port> --credentials-file <scratch>/demo.json
TMW_EMAIL=… TMW_PASSWORD=… APP=http://localhost:<web-port> API=http://127.0.0.1:<api-port> OUT=<scratch> node opening-date-web.mjs
TMW_EMAIL=… TMW_PASSWORD=… APP=… API=… OUT=<scratch> node ac1-web.mjs
```

Run the scripts from a scratch folder with `playwright-core` and `@axe-core/playwright`
installed. Use `localhost` for the web origin: the backend's default CORS rule allows
`http://localhost:*`. `seed_demo.py` verifies that exactly its own accounts exist, so rerun it
only on a stack without the accounts these checks create.
