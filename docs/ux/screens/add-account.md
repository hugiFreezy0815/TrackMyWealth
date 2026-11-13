# S6 Add account (and its starting value)

The shipped step titles use `addAccount.title` and `addAccount.start.title`.

**Figma:** M17 Add account (90:2658) · M16 Enter statement balance (90:2596) for the
starting-value step. Web: the same form in a dialog (no W frame; it uses the W11 dialog
layout).
**Requirements:** US-05-02 (account type and currency are immutable), US-05-04 (capability flags
drive the UI, never the type), US-05-05 (custom asset with manual valuations), FR-NAV-13/14,
US-25 (opening balance), US-07-10 (account identifier for import matching), NFR-UX-005.
**Rules for every screen:** see [README](README.md#rules-for-every-screen).

## Purpose

Adds an account to a portfolio, then records where its value starts, so the overview shows a
correct figure before or without an import.

## Entry points

- Step 2 of Add portfolio (S5), for the portfolio just created.
- The Portfolio View empty state "Add account" (S2), or ⋯ → "Add account" on a portfolio tab.
- Phone: a pushed screen. Web: a dialog.

## Step 1: the account (M17)

```
‹ Add account
Step 1 of 2
Add an account to DKB
Account type
ASSETS
( Current account )( Savings account )( Depot )
( Pillar 3a / pension )( Vested benefits )( Managed mandate )( Crypto )( Property & other )
LIABILITIES
( Credit card )( Mortgage )( Loan )
Account name      [ Tagesgeld          ]
Account currency  [ EUR · Euro       ⌄ ]
IBAN (optional)   [ DE89 3704 0044 0532 0130 00 ]
⚠ The account type can't be changed later. To change it, close this account and add a new one.
            [ Add account ]
```

### Type chips → request

The chip sets `accountType` and, where needed, one extra field. Type-specific fields appear under
the name when the chip is selected.

| Chip (label key `accountType.chip.*`) | `accountType` | Extra fields (shown only for this chip) |
|---|---|---|
| Current account / Privatkonto | `CASH` | IBAN (optional) |
| Savings account / Sparkonto | `SAVINGS` | IBAN (optional); "Counts as saving" switch → `countsAsSaving` (default on) |
| Depot | `SECURITIES` | IBAN/contract (optional) |
| Managed mandate / Vermögensverwaltung | `MANAGED_MANDATE` | – |
| Pillar 3a / pension / Säule 3a / Vorsorge | `PENSION` | **Scheme** (required) → `pensionScheme`: `CH_PILLAR_3A` (default for a CH portfolio), `DE_RIESTER`, `DE_RUERUP`, `DE_BAV`, `OTHER` |
| Vested benefits / Freizügigkeit | `VESTED_BENEFITS` | – |
| Crypto / Krypto | `CRYPTO` | – |
| Property & other / Immobilie & Weiteres | `CUSTOM_ASSET` | **Kind** (required) → `customAssetType`: `REAL_ESTATE`, `VEHICLE`, `PRECIOUS_METAL`, `COLLECTIBLE`, `OTHER` |
| Credit card / Kreditkarte | `CREDIT_CARD` | Masked card number (optional, e.g. "4242 42•• •••• 4242"); billing currency → `billingCurrency` (optional, defaults to the account currency) |
| Mortgage / Hypothek | `MORTGAGE` | **Original principal** (required) → `originalPrincipal`; interest rate % → `interestRatePercent` |
| Loan / Kredit | `LOAN` | **Original principal** (required); interest rate % |

The app never decides behaviour from `accountType` afterwards. The next step reads the
capability flags of the created account (`AccountSummaryResponse`).

### Fields and requests

| Field | Rule | Request |
|---|---|---|
| Name | Required. A default from the chip ("Savings account"), editable | `name` |
| Currency | Required. Defaults to the portfolio currency. **Immutable** after creation: the help text says so | `nativeCurrency` |
| IBAN / card number | Optional. Formatted while typing; validated by the server | after the account exists: `PUT /api/v1/accounts/{id}/identifier` `{ identifier }` with `If-Match` from the create response's ETag |
| Create | | `POST /api/v1/accounts` `{ portfolioId, name, accountType, nativeCurrency, …extra }` → 201 `AccountSummaryResponse` |

If the identifier call fails (`ACCOUNT_IDENTIFIER_INVALID` 422, `ACCOUNT_IDENTIFIER_IN_USE`
409), the account stays created. The app shows the error on the IBAN field with "Save IBAN" and
"Skip". It never deletes the new account to "roll back".

## Step 2: where the value starts (M16 style)

Which step 2 shows depends on the capability flags of the created account, not on the type:

| Capabilities | Step 2 | Request |
|---|---|---|
| `hasTransactions` and not `manualValuation`, not `hasAmortisation` (current, savings, card, depot cash…) | **Opening balance**: date (default: today) and balance in the account currency. Help: "The balance on this date. Transactions after it are added to it." Secondary: "Import a statement instead" → S7 | `POST /api/v1/accounts/{id}/opening-balance` `{ date, balance, currency }` |
| `manualValuation` (property, vehicle, …) | **Current value**: value and date. Help: "Estimate of today's value. You can update it any time." | `POST /api/v1/accounts/{id}/valuations` `{ valuationDate, value }` |
| `hasAmortisation` (mortgage, loan) | No step 2. The value comes from the principal and repayments or a loan statement | – |
| `holdsPositions` (depot) | Opening balance for the cash part as above. A note says holdings come from importing a holdings statement (#367, later) | as above |

Every step 2 has **"Skip for now"**. The account then shows `UNKNOWN` or "No opening
balance" (S3 reconciliation line `NO_OPENING_BALANCE`), never 0.

Errors:

- `OPENING_BALANCE_NOT_APPLICABLE` (422): skip the step silently. It's a safety net; the
  capability rule above already avoids it.
- `OPENING_BALANCE_AFTER_FIRST_TRANSACTION` (409): not possible on a new account; it's handled
  as in the catalogue if it ever comes.

## After step 2

- Close the flow on the portfolio's tab (S2), which reloads `/portfolios/{id}/view`, `/portfolios`
  and `/net-worth`.
- Show a toast "Account added".
- Show a prominent "Import a statement" action on the new row's Account detail (S3) when
  `hasTransactions`.

## States and errors

| Case | Treatment |
|---|---|
| `VALIDATION_FAILED` | Mark `errors[].field` (`name`, `nativeCurrency`, `pensionScheme`, `customAssetType`, `originalPrincipal`) |
| `NOT_FOUND` on the portfolio | The portfolio was removed meanwhile: back to S1 with a toast |
| Offline | Add is disabled with the reason; the inputs stay |
| Double submit | The button is disabled during the request |

## Accessibility

- The chips form a radio group with two labelled groups ("Assets", "Liabilities").
- The immutability warning is linked to the type group (`aria-describedby`).
- Amount fields use a decimal keyboard and accept the locale's separator. The value is sent as
  a `DecimalString` with "." as the separator.

## Differences from Figma

| Figma | Spec | Why |
|---|---|---|
| "IBAN or account number" sent with the account | sent in a second request after create | the identifier is its own resource (US-07-10) |
| Checkbox "Enter an opening balance next" | always offer step 2, with "Skip for now" | fewer accounts without a starting value (NFR-UX-005) |

## Acceptance checks for the UI story

- Given "Pillar 3a / pension" in a CH portfolio, then `pensionScheme = CH_PILLAR_3A` is sent,
  and the account appears in the portfolio's "Pillar 3a" section.
- Given "Property & other" + "Real estate", then step 2 asks for the current value and posts a
  valuation, not an opening balance.
- Given "Mortgage", then there is no step 2, and principal is required before "Add account" is
  enabled.
- Given an invalid IBAN, then the account exists, and the IBAN error shows with "Skip".
