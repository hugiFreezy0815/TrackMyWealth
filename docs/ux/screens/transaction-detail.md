# S4 Transaction detail with category change

**Figma:** M21 Transaction detail & history (91:2759) · W06 Transactions & categorisation
(32:1072), right-hand "Categorise" panel.
**Requirements:** FR-CAT-013 (uncategorised default), US-08-02 (a member's override always wins
and is never silently replaced; "reset to automatic"), FR-LIF-003 / US-07-02 (imported rows are
voided, manual rows soft-deleted), US-07-07 (restore), US-25-03 (reconciliation adjustments are
locked), FR-CNC-002 (version conflict), NFR-UX-001/003.
**Rules for every screen:** see [README](README.md#rules-for-every-screen).

## Purpose

Everything about one transaction: what it was, where it came from, how it is categorised and
how it changed. Here the member changes the category, gives the category back to automatic,
or removes the transaction.

## Entry points

- A row in Account detail (S3). Phone: a pushed screen. Web: the right panel of S3.
- Deep link `/accounts/{accountId}/transactions/{transactionId}` (single-record read
  delivered by #479). The screen loads the transaction directly and uses its current ETag.

## Layout

### Phone (M21)

```
‹ Transaction
┌──────────────────────────────────────────────┐
│ −CHF 84.35                                   │
│ Migros · 20 Oct 2026 · Card payment          │
│ [Imported]  [Estimated rate]                 │
└──────────────────────────────────────────────┘
┌──────────────────────────────────────────────┐
│ Account            PostFinance · Private acc.│
│ Category      Food › Groceries  ⌄  (by you)  │
│ Booking date                     20 Oct 2026 │
│ Counterparty                       Migros AG │
│ Notes                                    –   │
│ ▸ Details                                    │  ← [advanced] block
│   Bank code              PMNT-CCRD-POSD      │  ← delivered by #479
│   Source            CAMT.053 · import #14    │  ← delivered by #479
│   MCC                                  5411  │
│   Bank reference         2026102012345       │
└──────────────────────────────────────────────┘
Change history                                    ← delivered by #480
┌──────────────────────────────────────────────┐
│ 🕓 Category: Uncategorised → Groceries        │
│    Anna Meier · 21 Oct, 09:12                │
│ 🕓 Imported from CAMT.053 statement, import #14│
│    21 Oct, 09:10                             │
└──────────────────────────────────────────────┘
ⓘ Changing the amount, date or account records the old entry as voided and adds the
  corrected one, so the change stays visible.
[ Reset category ]                 [ Delete ]
Deleting an imported transaction voids it: it leaves all totals and stays in history,
marked as voided.
```

### Web

- The right panel (400 px) of S3, with the same blocks in the same order.
- The category row is an inline `CategoryPicker` (W06 "Categorise" panel) with a **Save**
  button.
- "Always categorise …" and "Apply to n earlier transactions" are **not shown** in sprint 7.

## Requests

1. The `TransactionResponse` from S3's loaded page (instant render). Then
   `GET /api/v1/accounts/{accountId}/transactions/{transactionId}` (**delivered by #479**) for the
   fresh version and the extra fields. The direct read supplies the current `ETag` and all transaction-detail fields.
2. `GET /api/v1/categories` (cached from S3).
3. `GET /api/v1/accounts/{accountId}/transactions/{transactionId}/history`
   (**delivered by #480**).
4. Account context (portfolio name) from S3's account.

## Fields

| # | Element | Label key (EN / DE draft) | Format | Source: `TransactionResponse` → field |
|---|---|---|---|---|
| 1 | Amount | | `Amount`, large, signed; voided ⇒ struck through | `amount`, `currency` |
| 2 | Converted amount `[advanced]` | "≈ {amount} at {rate}" | | **delivered by #479** `amountInAccountCurrency`; `fxRateToAccountCurrency`; shown only when `currency ≠` the account currency |
| 3 | Summary line | "{title} · {date} · {type}" | | title as in S3; `bookingDate`; `transactionType.{code}` |
| 4 | Source badge | `txn.source.{MANUAL,CSV,DOCUMENT,API,AGGREGATOR}` "Entered by hand", "Imported", "Imported", "Bank connection", "Bank connection" | neutral | `source` |
| 5 | Lifecycle badge | "Voided on {date}: {voidReason}" / "Reversal" / "Corrected" / "Restored" / "Statement adjustment" | warning/neutral | `voidedAt`, `voidReason`, `replacesTransactionId`, `correctsTransactionId`, `restoresTransactionId`, `reconciliationAdjustment` |
| 6 | Estimated rate badge | `txn.fxEstimated` "Estimated rate" | | `fxRateEstimated` |
| 7 | Account | `txn.account` "Account" / "Konto" | "{portfolio} · {account}" | **delivered by #479** `portfolioName`; `accountName` |
| 8 | Category | `txn.category` "Category" / "Kategorie" | "{parent} › {name}" + "(by you)" / "(automatic)" | `categoryId` → `/categories`; `categoryAssignedBy = USER` ⇒ "by you" (`txn.category.member`), any other value ⇒ "automatic" (`txn.category.automatic`). Row hidden when `categoryId = null` (type not categorised). `internalTransfer` ⇒ "Internal transfer: not counted as income or spending", no picker |
| 9 | Booking date | `txn.bookingDate` | long date | `bookingDate` |
| 10 | Counterparty | `txn.counterparty` "Counterparty" / "Gegenpartei" | | `counterpartyName`; hidden if `null` |
| 11 | Transfer to/from | `txn.transferAccount` "Other account" | link to S3 | **delivered by #479** `counterpartyAccountName`; `counterpartyAccountId`. Hidden if `null` |
| 12 | Notes | `txn.notes` | multi-line | `notes`; "–" if `null` |
| 13 | Trade fields (BUY/SELL/DIVIDEND) | `txn.security`, `txn.quantity`, `txn.unitPrice`, `txn.fee`, `txn.tradeDate`, `txn.settlementDate`, `txn.gross`, `txn.taxWithheld`, `txn.net` | | `securityId` (name via `GET /api/v1/securities/{id}`), `quantity`, `unitPrice`, `feeAmount`, `tradeDate`, `settlementDate`, `grossAmount`, `taxWithheldAmount`, `netAmount`; each row hidden when `null` |
| 14 | Bank code `[advanced]` | `txn.bankCode` "Bank code" / "Bankcode" | monospace | **delivered by #479** `bankTransactionCode` |
| 15 | Source detail `[advanced]` | Label `txn.sourceDetail`, value `txn.sourceDetail.value` (`format`, `date`) | link to import history (M19, not in sprint 7: plain text) | **delivered by #479** `importBatch` (`id`, `fileFormat`, `uploadedAt`). Pass `fileFormat` as `format` and `uploadedAt` formatted for the active locale as `date`. Figma's "import #14" becomes a date, because batches have no sequence number |
| 16 | MCC `[advanced]` | `txn.mcc` "Merchant category" | | `mcc` |
| 17 | Bank reference `[advanced]` | `txn.externalId` "Bank reference" | | `externalId` |
| 18 | Warnings | | ⚠ list | `warnings[]`, as in S3 |
| 19 | Change history | `txn.history.title` "Change history" / "Änderungsverlauf" | `HistoryTimeline`, newest first | **delivered by #480** `entries[]`: `occurredAt`, `kind`, `actorDisplayName`, `from`, `to`. Rendering per `kind` below |
| 20 | Info box | `txn.correctionInfo` | info style | static text; shown only when the transaction can be corrected (`removal != null`) |

Change history rendering uses the catalogue keys and ICU arguments below.

| `kind` | Catalogue key and arguments |
|---|---|
| `IMPORTED` | `txn.history.imported` (`format`, `date`) or `txn.history.imported.unknown` |
| `CREATED` | `txn.history.created` (`actor`) |
| `CATEGORY_ASSIGNED` | `txn.history.assigned` (`to`) |
| `CATEGORY_CHANGED` | `txn.history.changed` (`from`, `to`, `actor`) |
| `CATEGORY_RESET` | `txn.history.reset` (`to`, `actor`) |
| `CORRECTED` | `txn.history.corrected` (`actor`) with `txn.history.openCorrected` when a previous row is available |
| `VOIDED` | `txn.history.voided` (`actor`, `reason`) or `txn.history.voided.noReason` (`actor`) |
| `DELETED` | `txn.history.deleted` (`actor`) |
| `RESTORED` | `txn.history.restored` (`actor`) |
| unknown | `txn.history.other` (`actor`) |

`actorDisplayName` is `null` for system actions. The corresponding `.system` messages omit `actor`; a void without a reason uses `txn.history.voided.system.noReason`.

## Actions

| Action | Shown when | Request | On success |
|---|---|---|---|
| Change category | `categoryId != null`, not `internalTransfer`, not voided, account not archived | `CategoryPicker` → `PUT /api/v1/accounts/{accountId}/transactions/{transactionId}/category` `{ "categoryId": "…" }`, `If-Match: "{version}"` | The returned `TransactionResponse` replaces the screen's copy. Toast "Category saved". S3 reloads from page 0 and its uncategorised count. History (3) reloads |
| Reset category | `categoryAssignedBy = USER` | `DELETE /api/v1/accounts/{accountId}/transactions/{transactionId}/category`, `If-Match` | as above; toast "Category set automatically" |
| Delete | `removal != null` and `reconciliationAdjustment = null` | Confirmation. For `removal = VOID`: "Void this imported transaction? It leaves all totals and stays in history, marked as voided." with an optional reason field (max 200 characters). For `SOFT_DELETE`: "Delete this transaction? You can restore it from Removed transactions." Then `DELETE /api/v1/accounts/{accountId}/transactions/{transactionId}?reason=…`, `If-Match` | The returned `TransactionRemovalResponse`; the screen closes; S3 reloads |
| Edit (correction) | **Hidden in sprint 7** | `PUT …/transactions/{id}` needs the M18 form; filed for a later sprint through #387 | |
| Restore | **Hidden in sprint 7** | needs "Removed transactions" (`listRemovedTransactions`) | |

`CategoryPicker`:

- Phone: a bottom sheet with search and the two-level tree from `/categories` (active only,
  in the user's language). The current category is marked; "Uncategorised" is not offered as a
  choice.
- Web: a select with type-ahead, plus up to two "Suggested" chips. The chips are hidden in
  sprint 7: there's no suggestion endpoint.
- Accessibility: the picker is a dialog with a labelled search field, and focus returns to the
  category row on close.

Errors:

| `code` | Reaction |
|---|---|
| `VERSION_CONFLICT` (412) | Conflict dialog "This transaction was changed meanwhile". Reload, show the current category, and keep the picked one selected for "Save again" |
| `VERSION_REQUIRED` (428) | Reload, retry once |
| `RECONCILIATION_ADJUSTMENT_LOCKED` (409) | Catalogue message; hide Delete |
| `ACCOUNT_ARCHIVED` (409) | "This account is archived; restore it first." |
| `UNPROCESSABLE` / `VALIDATION_FAILED` on `categoryId` | "This category can't be used. Pick another one." Reload `/categories` (the category may have been deactivated) |
| `NOT_FOUND` | "This transaction no longer exists"; back to S3, which reloads |

## States

| State | Rule |
|---|---|
| Voided | Amount struck through. Lifecycle badge with the reason. No actions. History visible. |
| Reconciliation adjustment | Title `transactionType.VALUATION_ADJUSTMENT` "Statement adjustment"; `notes` holds the member's reason. Delete hidden; the info line reads "Change this through the reconciliation." |
| Archived account | Every action disabled with "Restore the account to change it". |
| History loading or failed | The rest of the screen is unaffected. The history block shows a skeleton, or Retry. |
| Offline | Actions disabled; the last loaded data stays. |

## Differences from Figma

| Figma | Spec | Why |
|---|---|---|
| "import #14" | "import from {date}" | Import batches have no number; the date is what members recognise |
| "Edit" button | Hidden in sprint 7 | The correction form (M18-based) is its own story |
| "Delete" for every row | Only when `removal != null` and not a reconciliation adjustment | Server rules (US-07-02, US-25-03) |
| W06 rule checkboxes and suggestions | Hidden | Not in sprint 7 |

## Acceptance checks for the UI story

- Given an imported transaction categorised automatically, the category row reads
  "Groceries (automatic)". After the member picks "Dining out", it reads "Dining out (by you)",
  "Reset category" appears, and the change history gets a "Category: Groceries → Dining out" line.
- Given another member changed the transaction meanwhile, Save gets 412: the conflict dialog
  shows, and no change is lost silently.
- Given `removal = VOID`, the Delete confirmation explains voiding. Given `removal = null`, there's
  no Delete.
- Given a dividend, the trade rows (security, gross, tax withheld, net) show and the category
  row is hidden.
