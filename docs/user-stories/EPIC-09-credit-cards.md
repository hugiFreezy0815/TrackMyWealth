# EPIC 09 — Credit Cards

Covers `account_credit_card` (V5) plus `transaction` rows of type `CREDIT_CARD_PURCHASE`/
`SETTLEMENT` (V10). Section 14, FR-CC-*.

---

## US-09-01 — Credit card purchases are individually tracked and roll up as a liability

**Actor:** Household member
**Objective:** FR-CC-001/003, DM-11.
**Story:** As a household member, I want each credit-card purchase to be recorded individually
and the outstanding balance to count as a liability in net worth, so that "money already spent
but not yet paid" is visible before the statement is settled.
**Preconditions:** A `CREDIT_CARD` account exists (`account_credit_card` extension row present).
**Acceptance criteria:**
- Given a card purchase of CHF 85.00 at a grocery store, when it is recorded (manually or via
  import), then a `transaction` row with `transaction_type = 'CREDIT_CARD_PURCHASE'` is created
  under the credit-card account, and the account's current balance (liability) reflects it
  immediately.
- Given the card's outstanding balance, when household net worth is computed, then it is
  subtracted (its `nature` is `LIABILITY` via the generated column) — never added.
- Given the MCC of the purchase is present, when the transaction is categorized (EPIC 08), then
  the MCC is retained separately from the reporting category (FR-CC-002/RULE-011), never
  overwritten by it.
**Applicable business rules:** FR-CC-001/002/003/006, DM-11, RULE-011.
**Data requirements:** `transaction.merchant_description`, MCC in `raw_source_data`.
**Error/edge cases:** A purchase entered against an account with no `account_credit_card`
extension row — rejected by `trg_extension_type_guard`'s consistency check indirectly (the
account must actually be `CREDIT_CARD` type to have that extension).
**Authorization/privacy:** Household-scoped write.
**Dependencies:** EPIC 05, EPIC 08.
**Priority:** MUST.
**Definition of Done:** Integration test records a purchase and asserts both the card balance and
household net worth reflect it correctly.
**Data-quality behaviour:** N/A.

---

## US-09-02 — Settlement is detected and matched, never double-counted

**Actor:** System (settlement-matching service) / Household member confirming a match
**Objective:** FR-CC-004/005/007, FR-CF-005, DM-05 — the single most important correctness rule
for cards.
**Story:** As the system, I want to detect the monthly payment from a current account to a card
issuer and match it to the corresponding card balance as a settlement (internal transfer), so
that the household's spending total counts each purchase exactly once — never again when the
statement is paid.
**Preconditions:** A `CREDIT_CARD` account with `settlement_source_account_id` pointing at the
paying current account (or the matching logic infers it from repeated payment patterns).
**Acceptance criteria:**
- Given a CHF 1,200 outgoing payment from the current account on a date/amount matching an open
  card statement, when the matching job runs, then both legs (the current-account debit and the
  card-account credit) are flagged `is_internal_transfer = true` and linked via
  `counterparty_account_id`, with `transaction_type = 'SETTLEMENT'`.
- Given the settlement is correctly matched, when household cash-flow/spending totals are
  computed for that month, then the CHF 1,200 settlement contributes **zero** to expenses — only
  the underlying individual card purchases (already counted when made, per US-09-01) do.
- Given the automatic match is wrong (e.g. coincidental amount match), when the household member
  reviews it, then they can correct/reject the match (FR-CF-004), and the transaction reverts to
  being classified normally.
**Applicable business rules:** FR-CC-004/005/007, FR-CF-001/004/005, DM-05, RULE-007/009.
**Data requirements:** None beyond the two transactions being matched.
**Error/edge cases:** Only one leg of the settlement has been imported so far (the card statement
not yet available) — the system must handle a one-sided match gracefully, not error (FR-CF-005:
"shall handle the case where only one leg has been imported").
**Authorization/privacy:** Household-scoped.
**Dependencies:** US-09-01, EPIC 07 (two-sided transfer matching infrastructure), EPIC 10.
**Priority:** MUST.
**Definition of Done:** This is exactly V-13 in the golden verification dataset (EPIC 27):
"credit-card purchase in month 1 settled from the current account in month 2 — single expense
recognition; transaction-date attribution." Implement it as an automated regression test.
**Data-quality behaviour:** An unmatched settlement-looking payment sitting unresolved must be
surfaced to the user as an item needing review, not silently counted as a regular expense.

---

## US-09-03 — Statement cycle and transaction-date attribution

**Actor:** Household member
**Objective:** FR-CC-008/009 — spending attributed to transaction date, not settlement date.
**Story:** As a household member, I want card spending to be attributed to the month the purchase
actually happened, and to see the statement cycle (period, closing balance, due date, paid
status), so that month-on-month budget comparison is meaningful and I know when payment is due.
**Preconditions:** A `CREDIT_CARD` account with `statement_day` configured.
**Acceptance criteria:**
- Given a purchase made on the 28th of month 1, settled in a statement closing on the 5th of
  month 2, when monthly spending reports are viewed, then the purchase appears in month 1's
  total (FR-CC-009), not month 2's.
- Given `statement_day` and `due_date_offset_days` are configured, when a `CardStatement` view is
  requested, then the current period's closing balance and due date are shown correctly.
**Applicable business rules:** FR-CC-008/009.
**Data requirements:** `account_credit_card.statement_day`, `due_date_offset_days`.
**Error/edge cases:** A purchase whose booking date falls exactly on the statement boundary — the
attribution rule must be deterministic and documented, not ambiguous.
**Authorization/privacy:** Household-scoped read/write.
**Dependencies:** US-09-01.
**Priority:** SHOULD.
**Definition of Done:** Unit test asserts month-1 attribution for the boundary-crossing scenario
above (this is also exercised by V-13 in EPIC 27's golden dataset).
**Data-quality behaviour:** N/A.

---

## US-09-04 — Foreign-currency card transactions retain original amount and issuer rate

**Actor:** Household member
**Objective:** FR-CC-010.
**Story:** As a household member who travels or shops internationally, I want a foreign-currency
card purchase to retain its original amount/currency and the rate the issuer actually applied, so
that I can see the true cost including any FX fee.
**Preconditions:** A card purchase in a currency other than the card's `billing_currency`.
**Acceptance criteria:**
- Given a EUR 50.00 purchase on a CHF-billed card, when imported with both the original EUR
  amount and the CHF-billed amount available in the source data, then both are stored
  (`transaction.currency` = EUR, `transaction.amount` = 50.00; `fx_rate_to_account_currency` set
  to the issuer's applied rate; the CHF-billed amount derivable from that rate).
- Given the issuer discloses a foreign-transaction fee separately, when parsed, then it is
  recorded as a distinct `FEE` transaction/component, not folded silently into the purchase
  amount (FR-CC-010).
**Applicable business rules:** FR-CC-010, DM-06.
**Data requirements:** Original currency/amount, issuer's applied rate, disclosed fee if present.
**Error/edge cases:** Source data does not disclose the applied rate separately from the
CHF-billed amount — the service layer must derive/record it rather than leave
`fx_rate_to_account_currency` null with no indication why.
**Authorization/privacy:** Household-scoped.
**Dependencies:** US-09-01, EPIC 06.
**Priority:** SHOULD.
**Definition of Done:** Integration test with a synthetic foreign-currency statement line.
**Data-quality behaviour:** If the applied rate cannot be determined, the transaction must be
flagged as having an estimated FX component (PR-011), not silently converted at a generic daily
rate presented as exact.
