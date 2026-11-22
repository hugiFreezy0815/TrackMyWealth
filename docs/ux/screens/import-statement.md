# S7 Import statement (transactions)

**Figma:** M08 Import, file & format (24:848) · M09 Import, review & duplicates (24:942) ·
W05 Import, review & duplicates (32:674).
**Requirements:** FR-IMP (upload → preview → commit; nothing is saved before the member
confirms), PO standing rule "CSV, PDF and CAMT stay importable; the duplicate check always
runs" (US-07-17), FR-IMP-022 (template detection by header fingerprint), FR-IMP-023 (the batch
keeps its template version), US-07-04 (batch), US-07-05 (rollback, not in this flow), US-07-10
(account identification), US-07-12 (statement balance), #308 (re-check before commit),
NFR-UX-005.
**Rules for every screen:** see [README](README.md#rules-for-every-screen).

> **Version 2 is planned.** The sprint-7 flow below is what is built. The section
> [Version 2](#version-2-design-round-2026-11-22) at the end describes the next design: a global
> entry, several files at once, a column-mapping step, editable rows and a securities table.
> Each part replaces its sprint-7 counterpart when its story ships.

## Scope

This covers transaction statements in CSV, PDF, spreadsheet and CAMT (camt.053 / camt.052)
into **one account the member chose**. It does not cover:

- holdings statements (a batch with `holdings != null`): #367;
- moving a batch to another account: #364;
- the single "Import statement" entry point that finds the account by itself: #365;
- import history and rollback: M19, later;
- template editing.

## Entry points

- Account detail (S3): "Import statement" (header ⋯ and the empty state). The account is fixed.
- Portfolio View (S2) data-source footer "Import statement": opens the account picker (the
  portfolio's accounts with `hasTransactions`) first.
- Web: the same flow as a full page with the 3-step header (W05).

## Flow

```
1 File ──upload──▶ (PARSED) ────────────────▶ 2 Review ──commit──▶ 3 Done
          └─▶ (UPLOADED + templateCandidates) ─▶ choose format ─parse─▶ 2 Review
Cancel anywhere after upload ─▶ discard
```

### Step 1: file and account (M08)

| Element | Rule | Request |
|---|---|---|
| "Import into" | The account card: logo, "{portfolio} · {account}", "{type} · {currency}". Changeable only when the flow started from S2 | – |
| File picker | Accepts `.csv`, `.txt`, `.pdf`, `.xlsx`, `.xls`, `.xml`, `.zip`. Phone: the system document picker; web: drop zone + button. Files over **5 MB** are refused on the device with the `IMPORT_FILE_TOO_LARGE` text, without uploading | – |
| Upload | Starts as soon as a file is chosen. Shows a progress bar and Cancel | `POST /api/v1/accounts/{accountId}/imports` (multipart `file`) → 201 `ImportBatchResponse` |
| Detected format | When `status = PARSED`: one line "Read as {templateName}" (or "CAMT bank statement"), with "Change" | – |
| Choose format | When `status = UPLOADED` and `templateCandidates` is not empty: a radio list of the candidates (`template.name`, `match` as a hint), best first; then **Read file** | `POST …/imports/{batchId}/parse` `{ templateId }` + `If-Match: "{version}"` |
| No template fits | `UPLOADED` with no candidates: "We can't read this file yet. Which bank is it from?" with a link to report it (FR-NAV-11, later) and Discard | `POST …/discard` |
| Info line | `statement.nothingSaved` "Nothing is saved until you confirm on the review step." / "Nichts wird gespeichert, bevor du im nächsten Schritt bestätigst." | |

Upload errors are shown on the file card with the catalogue message for `code` (all `SHOW`):

- `IMPORT_FILE_EMPTY`, `IMPORT_FILE_NO_DATA_ROWS`, `IMPORT_FILE_MALFORMED`
- `IMPORT_FILE_TOO_LARGE`, `IMPORT_FILE_TOO_MANY_ROWS`, `IMPORT_FILE_TOO_MANY_PAGES`
- `IMPORT_TEMPLATE_MISMATCH` (with `missingColumns`), `IMPORT_ENCODING_SUSPECT`
- `IMPORT_CAMT_UNSUPPORTED`, `IMPORT_CAMT_LIMIT_EXCEEDED`, `IMPORT_CAMT_MULTIPLE_ACCOUNTS`
- `IMPORT_PDF_NO_TEXT`, `IMPORT_PDF_LIMIT_EXCEEDED`, `IMPORT_SPREADSHEET_LIMIT_EXCEEDED`
- `IMPORT_OCR_FAILED`

`RETRY_LATER` codes (`IMPORT_PDF_BUSY`, `IMPORT_SPREADSHEET_BUSY`, `IMPORT_OCR_UNAVAILABLE`)
show "Busy, try again in a moment" with Retry, honouring `Retry-After`.

`IMPORT_TOO_MANY_OPEN_BATCHES` (409) says "You have unfinished imports for this account."
and lists them from `GET …/imports?page=0&size=50&sort=uploadedAt,desc`: the batches with
`status` `UPLOADED` or `PARSED`. An account holds at most 20 open batches
(`max-open-batches-per-account`), so they are all on that first page. Each has "Continue"
(opens step 1's format choice or step 2) and "Discard".

A holdings statement (`holdings != null` in the response) shows "This is a holdings
statement. Importing holdings in the app is coming soon." with Discard (#367).

### Step 2: review (M09 / W05)

**Header:** "{portfolio} · {account} · {format}", and the file name with its date range.

**Warnings on top**, each only when it applies:

| Condition (`ImportBatchResponse`) | Text (EN draft) | Action |
|---|---|---|
| `sameFileImportedIn` set | "This exact file was imported on {committedAt}. Its rows show as duplicates." | – |
| `accountIdentification.confirmationRequired && !confirmed` | `outcome` `NO_MATCH` / `NOT_FOUND`: "The file doesn't name this account's IBAN. Import it here anyway?"; `OTHER_ACCOUNT`: "This file belongs to another of your accounts." (moving it is #364) | "Yes, it's this account" → `POST …/confirm-account` + `If-Match`. Commit stays disabled until confirmed |
| `statementBalance.outcome = FOUND` | "Statement closing balance {balance} on {date}. It will be saved as the statement balance and checked against your transactions." For `snapshot = ALREADY_RECORDED`: "…already recorded." For `CONFLICTING`: "…differs from the one recorded from {existingSnapshot.importFileName}." | – |
| `statementBalance.outcome` `UNREADABLE` / `AMBIGUOUS` / `OUT_OF_RANGE` | `statementBalance.explanation` (server text) | – |
| any row with `recheck` set | "Your transactions changed since this preview. {n} rows will be treated differently." The rows show it as a badge | – |

**Counts** (tiles; tap = filter), all from `counts`:

| Tile | Field | Style |
|---|---|---|
| New | `newRows` | success |
| Possible duplicates | `duplicates` | info |
| Errors · not imported | `errors` | error |
| Skipped | `skipped` | neutral (hidden when 0) |
| With warnings | `warnings` | warning (hidden when 0) |

**Filter chips:** All {total} · New · Duplicates · Errors · Skipped. They map to
`GET …/imports/{batchId}/rows?status={PARSED|DUPLICATE|ERROR|SKIPPED}&page&size=50&sort=rowNumber,asc`.
All sends no `status`. A new row has the row status `PARSED` (the API's name); the UI calls it "New". The rows are paged; a filter change reloads from page 0.

**Row** (`ImportRowResponse`):

| Element | Source |
|---|---|
| Description | `canonical.description`, else `canonical.counterpartyName` |
| Date | `canonical.bookingDate` |
| Amount | `canonical.amount`, `canonical.currency`; original amount and rate `[advanced]` |
| Type | `transactionType.{canonical.transactionType}` |
| Duplicate hint | `duplicateOf`: "Matches the booking of {bookingDate}: {merchantDescription} {amount}" |
| Include switch | `included`. Shown for PARSED, i.e. new (default on) and DUPLICATE (default **off**). Label "Import" / "Skip" |
| Error | ERROR/SKIPPED rows: `error.message` (already in the member's language; `code` keys the icon). No switch |
| Warnings | `warnings[].message` as a ⚠ line |
| Re-check | `recheck`: `NEW` "Will now be imported", `DUPLICATE` "Now a duplicate", `REPOINTED` "Now matches another booking" |

Toggling a row sends `PATCH …/imports/{batchId}/rows/{rowNumber}` `{ included }` +
`If-Match: "{batch.version}"`. The returned `ImportBatchResponse` updates the counts and the
version. While the request runs the switch is busy, and requests are serialised: one at a time,
each with the newest version.

**Errors list:** "Download error rows" → `GET …/imports/{batchId}/errors.csv`. Phone: the share
sheet; web: a file download. Shown when `errors > 0`.

**Primary button:** `statement.commit` "{count, plural, one {Import # transaction} other {Import # transactions}}". The caller passes `counts.included` as `count`. Disabled when `included = 0` or the account still needs confirming.
Under it: "Imported as one batch. You can roll it back any time."

Commit: `POST …/imports/{batchId}/commit` + `If-Match`. Errors:

- `IMPORT_BATCH_STATE` (409, `RELOAD`): already committed or discarded. Reload the batch; if it
  is `COMMITTED`, go to step 3 instead of failing.
- `IMPORT_ACCOUNT_UNCONFIRMED`: scroll to the confirmation.
- `IMPORT_SNAPSHOT_CONFLICT` / `IMPORT_SNAPSHOT_ALREADY_RECORDED` /
  `IMPORT_SNAPSHOT_RECONCILIATION_DECIDED`: catalogue message; the member may discard.
- `VERSION_CONFLICT`: reload the batch and its rows, then ask again.

### Step 3: done

"{imported} transactions imported into {account}." Then the statement balance outcome (from
`statementBalance.snapshot`: `RECORDED` "Statement balance saved", `COMPLETED`…). Buttons:
"Show account" (S3, reloaded from page 0) and "Import another file".

S1 and S2 reload their figures. Uncategorised transactions show up through S3's banner.

### Cancel

Before commit, "Cancel" asks "Discard this import? Nothing has been saved." →
`POST …/discard` + `If-Match`. If the app is closed mid-flow, the batch stays open. The next
import into that account offers "Continue" for it (see `IMPORT_TOO_MANY_OPEN_BATCHES`).

## States

| State | Rule |
|---|---|
| Uploading / parsing | Progress with "Reading {fileName}…". A PDF with OCR may take long: after 10 s add "Scanned statements take longer". |
| Offline | Upload and commit disabled. A review already loaded stays readable. |
| Large file | Rows are paged (50). The counts come from the server; nothing is counted on the device. |
| Unknown `status` or row `status` | Treat as not committable; show "Update the app to finish this import". |

## Accessibility

- The step header announces "Step 2 of 3, Review".
- Each row switch is labelled "Import {description}, {amount}".
- The count tiles are buttons with their filter as the label.
- Error rows are not conveyed by colour alone: they have an icon and text.

## Differences from Figma

| Figma | Spec | Why |
|---|---|---|
| M08 "Detected format" radio list (CSV / PDF / CAMT) | one "Read as …" line; a candidate list only when the server can't pick | the server detects the format (FR-IMP-022, CAMT always) |
| "Remember template for this account" | dropped | detection by header fingerprint already re-uses the template |
| W05 summary "Net amount", "Balance after import", "Closing balance matches the file" | statement balance as the file states it, no net amount or "balance after" | the batch response has no such figures; FR-API-010. The reconciliation shows on S3 after commit |
| "Matches booking of 14 Oct · import #12" | "Matches the booking of 14 Oct: {description}" | batches have no number |
| "How duplicates are found: …same description…" | "Same account, booking date, amount and currency" | the rule ignores the description (US-07-17) |

## Acceptance checks for the UI story

- Given a DKB CSV whose header matches one template, then the upload answers `PARSED` and the
  app goes straight to the review.
- Given a CSV that two templates can read, then the app shows both candidates, and after the
  choice sends `parse` with that `templateId`.
- Given a file with 41 new rows, 5 duplicates and 2 errors, then the button reads "Import 41
  transactions". Switching one duplicate on makes it "Import 42 transactions" (from the
  response's `counts.included`).
- Given the same file imported twice, then the second review shows the "already imported"
  warning, and every row shows as a duplicate, off by default (PO rule: nothing is imported
  twice).
- Given Cancel after upload, then `discard` is sent and nothing appears in S3.

## Version 2 (design round 2026-11-22)

**Figma:** M32 Import, files (phone) · M33 Import, choose format / map columns · M34 Import,
review v2 · W17 Import v2 (web). To be drawn; DE mirrors.
**Inspiration:** getquin's file import (screenshots of 2026-11-22): several files at once,
per-file status, editable rows, separate trade and cash tables. **PO decision 2026-11-22:** no
LLM ("AI") fallback. A file no template reads goes to a format choice or a column mapping, and
the data never leaves our server.

### What changes

| Area | Sprint 7 | Version 2 |
|---|---|---|
| Entry | from one account (S3) or a portfolio (S2) | also a global **Import** button: web header, phone "+" menu on Overview and Investments. The file finds its account (#365) |
| Files | one file | several files in one go, each its own batch, shown as `FileCard`s |
| Unknown file | "We can't read this file yet" | **Choose format**, and for CSV/spreadsheet a **column mapping** that saves a workspace template |
| Review | include/skip per row | include/skip **and** correct a row (type, date, amount, description), marked "Edited" |
| Securities | holdings statement refused | holdings preview (#367) and broker trades table (#274) |
| Done | one summary | a summary per file + any new data checks ([S15](data-checks.md)) |

### Step 1 v2: files (M32)

```
Import statements                                    [×]
┌ ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄ ┐
┆  [⇪] Drop files or choose                           ┆
┆  CSV, Excel, PDF, CAMT (.xml, .zip), eCH-0196 tax   ┆
┆  statement PDF · up to 5 MB each                    ┆
└ ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄ ┘
How it works
 1  Choose one or more statement files
 2  We recognise the bank and the account
 3  You check every row, nothing is saved before you confirm
┌ 📄 2026-10_privatkonto.csv                            ┐
│ PostFinance · Privatkonto  ✓ Recognised   [Review ›] │
│ 📄 depot_31-10.pdf                                    │
│ Swissquote · Depot  ✓ Holdings statement  [Review ›] │
│ 📄 export(3).csv                                      │
│ ⚠ Format not recognised            [Choose format ›] │
│ 📄 scan.pdf                                           │
│ ✕ No readable text (scanned)              [Remove]   │
└───────────────────────────────────────────────────────┘
🔒 Your files are read on the TrackMyWealth server only.
```

| Element | Rule | Request |
|---|---|---|
| Drop zone | several files; same types and 5 MB limit as sprint 7 | – |
| How it works | three static steps, `import.howItWorks.{1,2,3}`. Step 3 states the PO rule | – |
| `FileCard` status | `Uploading` (progress) · `Reading` · `Recognised` (institution · account) · `Holdings statement` · `Needs format` · `Needs account` · `Error` (catalogue message) | per file, see below |
| Account resolution | known account → card shows it with "Change"; a new account → "New account: {institutionName} · {maskedIdentifier}" with "Create" ([S6](add-account.md) prefilled) | **Gap G7-1 (#365)**: upload without an account. Today: `POST /api/v1/import-templates/detect` (file) → candidate; `POST /api/v1/import-templates/{templateId}/account-proposal` (file) → `ImportAccountProposalResponse`; matching an **existing** account by identifier needs #365 |
| Upload into the account | one batch per file, files processed **one at a time** (keeps `IMPORT_TOO_MANY_OPEN_BATCHES` and server load low) | `POST /api/v1/accounts/{accountId}/imports` as sprint 7 |
| Privacy line | `import.privacy` "Your files are read on the TrackMyWealth server only." | – |
| Order of review | each card has its own "Review"; there is no merged review across files (each batch commits alone, so a failure in one file never blocks the others) | – |

No "AI fallback" toggle, no token counter.

### Step 1b: choose format / map columns (M33)

Opened from "Choose format" on a `Needs format` card.

1. **Choose format.** The candidates (`templateCandidates`, best first), plus "None of these".
   Same as sprint 7.
2. **Map columns** (CSV / spreadsheet only, after "None of these"):

```
Map the columns of export(3).csv
Header row  [ 1 ⌄ ]   Separator [ ; ⌄ ]   Decimal [ . ⌄ ]   Date format [ dd.MM.yyyy ⌄ ]
┌ Column in file        Means                     Example        ┐
│ Buchungstag           [ Booking date ⌄ ]        03.10.2026     │
│ Betrag                [ Amount (signed) ⌄ ]     -49.00         │
│ Verwendungszweck      [ Description ⌄ ]         Monatsabrech…  │
│ Empfänger             [ Counterparty ⌄ ]        Example GmbH   │
│ Währung               [ Currency ⌄ ]            EUR            │
│ Saldo                 [ Ignore ⌄ ]              1'204.55       │
└────────────────────────────────────────────────────────────────┘
Preview: 41 rows read · 0 errors                     [Test again]
Save as format for: [ PostFinance ⌄ ]  Name [ PostFinance CSV ]
                                          [ Save and read file ]
```

| Element | Request |
|---|---|
| Header columns and examples | `POST /api/v1/import-templates/test` (file + draft `ImportTemplateRequest`) → `headerColumns`, `rows[]` |
| "Means" choices | the `ImportColumnMapping` fields in member words: Booking date*, Value date, Amount (signed)*, or Debit + Credit, Currency, Description, Counterparty, Reference, Type, Notes |
| Preview counts | `parsedRowCount`, `errorRowCount`, `skippedRowCount` from the test response (server counts) |
| Save and read | `POST /api/v1/import-templates` (workspace template) → `POST …/imports/{batchId}/parse` `{ templateId }` + `If-Match` |

- Required mappings are marked with * and named in an error ("Map a booking date").
- PDF files cannot be mapped by members. An unknown PDF shows "We can't read this PDF yet",
  with "Report this format" (FR-NAV-11) and Remove.
- **Gap G7-2 (#698):** confirm that members (not only administrators) may create workspace templates,
  and name the error codes of `/import-templates/test`.

### Step 2 v2: review (M34)

Everything from sprint-7 step 2 stays: warnings, count tiles, filter chips, include switch,
duplicate hint, commit button. Changes:

**Two tables in one batch.** When the batch has securities rows, the review shows
**Trades** first (Type · Security · Units · Price · Currency · Total · Date), then **Cash
bookings** (Type · Description · Amount · Currency · Date). Phone: two sections with
`EditableImportRow`s. Web: two tables.
- Trades come from #274 (broker export). **Gap G7-3:** `CanonicalImportRow` has no security
  fields (ISIN, quantity, price) yet.
- A holdings statement shows the #367 preview (`ImportHoldingsResponse`: positions with
  `resolution`, cash balances, unreadable lines, conflicts). An `UNRESOLVED` security has a
  "Find security" action (`POST /api/v1/securities` by ISIN).

**Correcting a row.** A row's ⋯ → "Correct" opens a sheet with Type, Booking date, Amount,
Description (and for trades Units, Price). Saving marks the row `Edited` (badge + "Show
original" with the raw value). **The duplicate check runs again** on the corrected row: the
server answers with the new status, and a row that became a duplicate switches off with the
hint (PO rule).
- **Gap G7-4 (#698):** `PATCH …/rows/{rowNumber}` accepts only `included`. Row correction needs a
  server endpoint that stores the override and re-runs the duplicate check.

**Undo for the member.** "Edited" rows can be reset ("Use the file's value").

### Step 3 v2: done

Per file: "{imported} transactions imported into {account}" (or "{n} positions recorded").
Under the summaries, one line per new data check ([S15](data-checks.md)), for example "2
positions have no cost basis" → "Fix". The units check card appears on [S11](investments.md)
after a holdings import.

### Design decisions (vs. the getquin example)

| getquin | TrackMyWealth | Why |
|---|---|---|
| "Use getquin AI as fallback" (LLM reads the file) | Choose format → column mapping, server-side only | PO 2026-11-22; privacy; deterministic and testable |
| Monthly token counter | none | no LLM |
| Editable rows without duplicate marks | count tiles, duplicate hint, include switch, re-check after edits | PO rule: the duplicate check always runs |
| A file list with "Analyzing…" and no account shown | each file names its institution and account before review | the member must see where the rows go |
| Long raw file names as titles | file name + recognised institution · account | readable |

### Acceptance checks (version 2)

- Given three files (one CSV that is recognised, one unknown CSV, one scanned PDF), the cards
  show Recognised, Needs format and Error. The recognised file can be reviewed and committed
  while the others are pending.
- Given an unknown CSV, the member maps the booking date, amount and description, the
  preview shows the server's counts, and "Save and read file" leads to the review.
- Given a corrected row that now equals a booked transaction, it turns into a duplicate,
  switched off.
- No screen offers an AI or LLM option.
