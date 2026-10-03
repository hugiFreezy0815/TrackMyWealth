# TrackMyWealth — Calculation Methodology

This document is the authoritative answer to "how is this actually computed" for every
calculation-adjacent rule in the platform (NFR-CALC-003, specification section 46) — never to be
reverse-engineered from the implementation. **US-27-02** is the story that completes it: all eleven
items in section 46 (net worth, TWR, MWR, Modified Dietz, savings rate, cost basis, realised gain,
FX conversion, position valuation, currency attribution, reconciliation difference), each with
inputs, edge-case treatment, and a worked example matching its golden-dataset fixture (EPIC 27).

This file is seeded ahead of that story by **US-06-03** with the one methodology EPIC 06 already
has the machinery to state precisely: the FX conversion date convention (FR-CUR-011). The other ten
rows are `docs/user-stories/EPIC-27-calculation-verification.md`'s to fill in, each with its
golden-dataset fixture. The transaction ledger (EPIC 07) and a first net-worth read exist now;
positions (EPIC 15) and performance (EPIC 16) do not yet, and their sections wait for that code.

## FX conversion date convention (US-06-03, FR-CUR-011)

Every cross-currency figure needs an FX rate, and every FX rate is dated (`fx_rate.rate_date`,
`FxRateService`, US-06-01/US-06-02). Mixing date conventions silently — using today's rate for a
transaction from three months ago, or a transaction's rate for today's position value — is a common
source of "why doesn't this number match what I calculated by hand," per the story's own framing.
The convention is not "use the newest rate" or "use one rate per report"; it is: **the rate date
follows the figure's class**, one of exactly three:

| Figure class | Rate date used | Examples |
|---|---|---|
| **Realised flow** | The transaction's own booking/value date | A dividend received, a realised gain from a sale, any cash-flow-reporting figure |
| **Current balance / holding** | The valuation date (today, or the report's as-of date) | An account balance, a position's current market value |
| **Period-end / closing figure** | The period's closing date | A month-end net-worth figure, any historical point-in-time snapshot |

The acquisition date of a position is never the right answer for the second or third row — a
USD position bought two years ago and still held today is valued at **today's** (or the report
date's) rate, not the rate on the day it was purchased. That distinction is the specific mistake
this convention exists to prevent.

### Mechanism

All three rows resolve to the same underlying call: `FxRateService.getRate` /
`FxRateService.getConversionRate` (US-06-01/US-06-02), given the currency pair and **the date the
figure's class says to use** — never a date chosen ad hoc by the calling code. That call already
guarantees, independently of which figure class is asking:

- **Direct pair preferred, chaining only as a documented fallback** (FR-CUR-010, US-06-02): the
  direct `baseCurrency`/`quoteCurrency` rate is used whenever one is stored; only when none exists
  at all does resolution fall back to chaining through USD, this codebase's documented common
  intermediate.
- **Carry-forward on gaps** (FR-CUR-012, US-06-01): if no rate is stored for the exact date asked
  for (a weekend, a holiday, a provider outage), the most recent prior rate is used and the result
  is marked `carriedForward` — visible on inspection, never silently presented as an exact rate for
  that date (PR-011).
- **Refusal, never a silent default to 1.0** (PR-011): if no rate exists at all, on or before the
  requested date, directly or via the chain, the caller gets a 404, not a fabricated figure.

This means the date convention below is purely about **which date to pass in** — every other
correctness guarantee (which pair, which fallback, how staleness is marked) already lives in
`FxRateService` and does not need restating per figure class.

### Rounding

Per NFR-CALC-007 (documented in full here once US-27-02 covers every calculation; stated here for
the one calculation this section owns): the converted money amount is rounded **HALF_UP to 4
decimal places** — matching this codebase's `NUMERIC(20,4)` money-storage convention — applied once,
at the point the figure is converted, never accumulated through intermediate steps. `FxRateService`
implements this in `convert()`; the rate itself is kept at full precision (`fx_rate.rate`'s
`NUMERIC(20,10)`) throughout resolution, including through a chained conversion's two legs.

### Worked examples

**Realised flow** — a CHF-reporting user receives a USD 500.00 dividend, booked 2026-03-14 (a
Saturday; no rate published that day, so `FxRateService` carries forward Friday 2026-03-13's rate
of 0.8910):

```
getConversionRate("USD", "CHF", 2026-03-14, source)
  -> rate = 0.8910000000, direct = true, carriedForward = true (rate dated 2026-03-13)
convert(500.00, "USD", "CHF", 2026-03-14, source)
  -> 500.00 * 0.8910000000 = 445.5000
```

The cash-flow report shows CHF 445.5000, marked as carried-forward — never re-derived later using
today's rate, since the flow is dated to when it was actually received, not to when it's later
displayed.

**Current holding** — the same USD position is still held on 2026-09-16 (the report's as-of date),
by which point USD/CHF has moved to 0.8850 (an exact rate for that date):

```
getConversionRate("USD", "CHF", 2026-09-16, source)
  -> rate = 0.8850000000, direct = true, carriedForward = false
```

The position's current value uses **this** rate — dated to today, not to 2026-03-14 (when the
dividend happened to be booked) and not to whatever date the position was originally acquired.

**Period-end / closing figure** — a net-worth figure for the close of August 2026 uses the rate
dated 2026-08-31 (the period's own closing date), consistently for every account balance rolled
into that figure, regardless of which date each account's data happens to have last been reconciled
or updated. Two accounts contributing to the same August closing figure must never end up converted
at two different dates' rates.

### What this section deliberately does not cover yet

`Transaction` (EPIC 07), `Position` (EPIC 15) and the net-worth/snapshot machinery (EPIC 11/25) do
not exist in code yet — this section documents the *rule* those future call sites must follow, not
an API surface for them, since designing that surface now would be guessing at shapes those stories
haven't defined. When each of those epics lands, its own service is expected to call
`FxRateService` with the date this convention specifies, not to reopen the question of which date
to use.

## Ledger sign convention and credit-card balance (US-09-01, FR-CC-001/003)

Seeded by **US-09-01**, the first story to compute a figure from `transaction`. The full net-worth
methodology remains **US-11-01**/**US-27-02**'s to write; this section states only what the ledger
itself already fixes, so the stories that follow don't each re-decide it.

**`transaction.amount` is cash-direction signed, and stored exactly as sent.** Money leaving an
account is negative, money entering it is positive. For a `CREDIT_CARD` account a purchase is
therefore negative (it increases what is owed) and a settlement or refund is positive. The API does
not flip a sign on the caller's behalf: `POST /api/v1/accounts/{id}/transactions` rejects a
non-negative `CREDIT_CARD_PURCHASE` (HTTP 422) rather than negating it.

**A card's balance is the negated sum of its ledger rows booked on or before the as-of date**,
expressed as a positive "amount owed" with `nature = LIABILITY`:

| Ledger rows on the card | `sum(amount)` | Balance (`value`) |
|---|---|---|
| purchase −85.00 | −85.00 | **85.00** owed |
| purchases −85.00, −15.50 | −100.50 | **100.50** owed |
| purchase −50.00, then +50.00 settlement | 0.00 | **0.00** (known zero) |
| no rows | — | **0.00** (known zero) |

Three rules are load-bearing and each has a test in `TransactionControllerTest`:

- **A void pair counts as zero, on every date.** A void leaves the original in the ledger and adds
  a reversing row of the opposite sign (FR-LIF-002, "both records remain"), dated to the void. A
  correction (US-07-06) or a restore (US-07-07) re-enters the transaction on the original booking
  date, so summing the pair would count it twice between the booking and the void. The balance
  therefore leaves the original and its reversal out together (restated history); dropping only
  `voided_at IS NOT NULL` rows would count the reversal alone. A voided row is still *shown* as
  voided (FR-LIF-003), and an account whose rows are all voided reads a measured zero (`LEDGER`).
- **An empty ledger is a known zero, flagged as assumed.** A card with no rows owes exactly 0 and
  does not make an aggregate incomplete - but its `valueBasis` is `LEDGER_EMPTY`, not `LEDGER`, so
  a client can tell an assumed zero from a measured one. A card that already carried debt when
  tracking began reads 0 until that debt is recorded as its opening balance (see *Opening
  balances* below).
- **Future-dated rows wait.** A row booked after the as-of date does not count until that date,
  matching the valuation-date convention above.

**"Today" is the business date, not the server's.** The as-of date for every date-filtered read is
`BusinessDateService.today()`: the current date in `app.business-zone` (default `Europe/Zurich`),
never the JVM's default zone. The container runs in UTC, so between local midnight and 01:00/02:00
the server's date is still yesterday's; a purchase a user books with their own local date would be
"future-dated" and missing from the balance until the server caught up. Code that needs today's
date for a valuation or ledger read uses `BusinessDateService`, not `LocalDate.now()`.

**Where a figure came from (`AccountValuation.valueBasis`).** A known value is not necessarily an
exact one:

| `valueBasis` | Source | Exact? |
|---|---|---|
| `LEDGER` | negated sum of the card's ledger rows | yes |
| `LEDGER_FROM_OPENING_BALANCE` | opening balance plus the ledger after the opening date (US-25-04) | yes |
| `MANUAL_VALUATION` | latest manual valuation on or before the as-of date (`CUSTOM_ASSET`) | yes, as recorded |
| `LATEST_SNAPSHOT` | newest snapshot balance on or before the as-of date, the opening balance included - an account without a ledger (`has_transactions = false`, vested benefits) | yes, as reported on that date |
| `LEDGER_EMPTY` | card with no rows yet - assumed 0 | **no** - approximation |
| `ORIGINAL_PRINCIPAL` | a loan's/mortgage's *original* principal, not its outstanding balance (no amortisation tracking until EPIC 10) | **no** - approximation |

`valueBasis` is `null` when `valueKnown` is `false`. `AccountValuation.valueSourceDate` is the date
of the observation a `LATEST_SNAPSHOT` or `MANUAL_VALUATION` figure rests on, so a client can show
how old it is; it is `null` for a figure derived from the ledger or the loan terms.
`NetWorthResponse.approximate` is `true` when any included account has an approximate basis. It
is independent of `complete`: an approximate account is still *known* (counted in the totals), but
the figure must not be presented as exact.

**Recording is idempotent on request.** `POST .../transactions` accepts an optional `externalId`
(a client-generated key, stored in `transaction.external_id` with source `MANUAL`, unique per
account by `uq_transaction_external_id`). A retry carrying the same key returns the originally
recorded row (same 201 and body) instead of appending a second one; the same key with a different
type, date, amount or currency is a 409. The ledger is append-only, so without this a client that
lost a response and retried would double the debt with no way to undo it until the void path
(US-07-02) exists. Two requests racing on one new key hit the unique index and one gets a 409 to
retry.

**Listing is paged.** `GET .../transactions` returns a Spring Data page (`content`,
`totalElements`, ...), default 50 per page and never more than 200. Its order is fixed - newest
booking first, `created_at` then `id` as tie-breakers - and a client-supplied `sort` is ignored, so
paging is stable and cannot order by an unindexed or non-existent column.

**The balance sums `amount` regardless of a row's `currency`.** That is sound only while every
row on a card is in the account's own currency, which the write path enforces. US-09-04 adds
foreign-currency rows (amount in the original currency); at that point the balance must sum the
account-currency figure instead, or it would silently mix currencies.

**Net worth (partial, until US-11-01)** is `Σ value(ASSET) − Σ value(LIABILITY)` over every active
account the caller may see at `BALANCE_ONLY` or above, in the caller's `reporting_currency` (each
account's foreign currency is converted at the business date's rate, resolved once per currency
pair per request). Each account's sign comes from its `nature` (the DB-generated column), never from application-side
`account_type` logic. Accounts with no resolvable value (types with no value source yet) are listed but excluded from the totals and
flagged (`complete = false`) rather than counted as zero.

## Opening balances (US-25-04, FR-REC-007)

An account whose transaction history starts later than the account itself has no known value
from its ledger alone. A member records a dated **opening balance** for it
(`POST/PUT/DELETE /api/v1/accounts/{id}/opening-balance`), stored as the account's one
`account_snapshot` with `is_opening_balance` (V58). It is the *"ledger from opening balance"* value
source of `AccountValuationService` (`valueBasis = LEDGER_FROM_OPENING_BALANCE`):

```
value(D) = opening balance + Σ amount of live ledger rows with  opening date < booking_date ≤ D
```

- **The opening date's own rows are contained in the balance.** The balance is the account's
  balance at the *end* of the opening date, as a statement prints it, so a row booked on that date
  is not added again. Only rows booked later are.
- **Before the opening date the value is unknown, not zero** (PR-011): `valueKnown = false` for any
  read with `asOf` earlier than the opening date (`GET .../balance?asOf=`).
- **A past balance needs `READ`.** Today's balance is visible at `BALANCE_ONLY`; a read with
  `asOf` before today needs `READ` on the account, because balances on consecutive days differ by
  that day's transactions, which a `BALANCE_ONLY` grant does not show (#241 review). "Today" is
  the server's business date (`app.business-zone`), so a client asks for the current balance by
  omitting `asOf`, never by sending its own local date.
- **Rows before the opening date are left out.** They predate the starting point. Recording an
  opening balance after existing live rows is refused (409 `OPENING_BALANCE_AFTER_FIRST_TRANSACTION`,
  with their count and earliest booking date) unless the member confirms
  `acknowledgeEarlierTransactions`. While such rows exist, the account, its valuation and the
  net-worth and institution-summary headlines carry `TRANSACTIONS_BEFORE_OPENING_BALANCE`
  (FR-CON-007) - never a silent double count. A `BALANCE_ONLY` grant sees the warning with the
  figure it qualifies, but not the rows' count or dates (those come with the 409, which needs
  `EDIT`). The acknowledgement is not stored: every replace is checked again.
- **Removed rows don't count**, as everywhere: a soft-deleted row is gone, and a void pair nets to
  zero on every date (see above), wherever its two rows fall relative to the opening date.
- **Sign.** The balance follows the snapshot convention: a liability's is the positive amount
  owed. The ledger is cash-direction signed, so for `nature = LIABILITY` the ledger sum is
  subtracted (a card purchase of −100.00 on top of 500.00 owed makes 600.00 owed). Read from
  `nature`, never from `account_type`.
- **Currency.** The account's own currency - a credit card's `billing_currency`, which its ledger
  is summed in. Any other currency is refused (422); a conversion is not an opening balance.

| Worked example (CHF cash account) | |
|---|---|
| Opening balance 2024-10-01 | 10,000.00 |
| Row on 2024-10-01 | −50.00 (contained, not added) |
| Rows after 2024-10-01 | −1,000.00, −300.00, +65.45 = −1,234.55 |
| Value today | **8,765.45** (`LEDGER_FROM_OPENING_BALANCE`) |
| Value as of 2024-09-30 | unknown |

Which accounts use it is decided by capability flags (DM-17), never by type:

| Account | With an opening balance |
|---|---|
| `has_amortisation` (loan, mortgage) or `manual_valuation` (custom asset) | refused, 422 `OPENING_BALANCE_NOT_APPLICABLE`: they have their own value source |
| `holds_positions` (depot, mandate, crypto, a pension holding funds) | stored, but the value stays unknown - the cash is only part of it until holdings are valued (EPIC 15) |
| `has_statement_cycle` (credit card) | replaces the card's ledger-only source (`LEDGER`/`LEDGER_EMPTY`) |
| `has_transactions = false` (vested benefits) | no ledger to add: the account is valued from its latest snapshot on or before the as-of date (`LATEST_SNAPSHOT`), the opening balance being one of them. A newer snapshot supersedes it rather than the opening balance standing forever, and an older regular snapshot still values the account before the opening date: without a ledger the opening balance is no cut-off (#241 review) |
| everything else (cash, savings, pension) | the source above; without ledger rows after the opening date the value is the opening balance itself |

A transaction recorded later with a booking date before the opening balance is left out of the
value like any earlier row. It carries `BOOKED_BEFORE_OPENING_BALANCE` in its own `warnings`
(`TransactionResponse`), so the member sees at once that it does not count, and the account carries
`TRANSACTIONS_BEFORE_OPENING_BALANCE`.

## Card settlement matching and spending (US-09-02, FR-CC-004/005/007, FR-CF-001/004/005)

The single most important correctness rule for cards: a purchase counts as spending **once**, when
it is made - never again when the statement is paid. The monthly payment out of the current account
is an internal transfer (DM-05), not a second expense.

**What is matched.** For a card with a `settlement_source_account_id`, a *payment* is a negative,
non-voided `WITHDRAWAL` or `SETTLEMENT` row on that account and a *card credit* is a positive,
non-voided `SETTLEMENT` row on the card. Both must be in the card's currency (cross-currency is
US-09-04). They pair when the amounts are exactly equal and the booking dates are at most 5 days
apart.

| Situation | Outcome |
|---|---|
| exactly one credit fits the payment, and the payment is that credit's only fit | applied automatically (`CONFIRMED`, decided by the system): both legs flagged `is_internal_transfer`, each pointing at the other's account |
| several payments/credits fit each other (identical amounts) | every pair only `PROPOSED` - never applied on a guess |
| payment with no card credit, equal to the card's balance on the payment date | `PROPOSED` as `BALANCE_EQUALS_PAYMENT` - only one leg is recorded yet (FR-CF-005); never auto-applied, since an ordinary debit can equal the balance by coincidence |
| payment equal to neither | ordinary spending |

**Cost.** A write costs a handful of queries however long the ledger is. Pairing looks only at the
card's few unmatched credits, each fetching just the payments of the same amount within the window;
the one-sided check is one query that compares balances inside the database, and for a write covers
only payments the new row can affect (booked within the window before it, or after it). Only an
on-demand run, a newly set settlement source or an undone match scans the full history. A test
counts the SQL statements of a write with 10 versus 410 historic withdrawals and requires them not
to grow.

A `PROPOSED` one-sided match that is later completed by its card credit becomes the pair
(`LEG_PAIR`) in place. Confirming a proposal rejects any competing proposal for the same payment or
credit. Matching never re-types a leg (`transaction_type` is frozen by the ledger trigger) - it only
sets the two mutable link columns, so undoing a match is clearing them.

**Decisions are sticky.** A `REJECTED` match is final: the same pair (or a payment whose one-sided
proposal was rejected) is never proposed again, and matching can be re-run any number of times
without adding anything. Rejecting a payment *as a settlement* (its one-sided candidate) also stops
the system from later applying it to a credit of its own accord: a credit of the same amount that
arrives afterwards is only *proposed* against it, for the member to decide. Rejecting a `CONFIRMED` match reverts both legs to ordinary transactions
and re-runs matching, since a freed credit may now fit a different payment. Deciding a match needs
`EDIT` on **both** accounts; a caller without it sees the match as nonexistent. The work queue
(`GET /settlement-matches`, newest first, at most 200) and `POST .../settlement-matches/run` return
only matches whose card *and* payment account the caller may edit - the filter is part of the query,
so another member's matches cannot crowd the caller's own out of the page, and a card's earlier
settlement source is never revealed through its current one. Ties on `created_at` (one run's
proposals share it) are ordered by `id`.

**Concurrency.** Everything that applies or decides a match for a card first takes that card's
account row lock (`SettlementDetectionService#lockCard`), so two writes racing on a card, or two
members confirming competing proposals, queue instead of proposing the same pair twice or
deadlocking. Taking it *after* inserting a ledger row is safe because Hibernate emits `FOR NO KEY
UPDATE` for a pessimistic write on PostgreSQL, which - unlike `FOR UPDATE` - does not conflict with
the `FOR KEY SHARE` the insert takes on the card row through its foreign key; a test races writers on
one card and its source account to guard that.

**Monthly spending (partial, until EPIC 10)** - `GET /api/v1/cash-flow?month=yyyy-MM`:

- **Spending** is `CREDIT_CARD_PURCHASE` and `WITHDRAWAL` rows, summed per currency by **booking
  date** (FR-CC-009) - so August purchases stay in August whenever the statement is paid. It leaves
  out `SETTLEMENT` rows (never spending, matched or not), any row flagged an internal transfer, and
  any payment awaiting a decision.
- **`pendingReview`** reports that awaiting-a-decision amount (a `PROPOSED` payment, or an
  unmatched `SETTLEMENT`-typed debit) - neither counted as spending nor silently dropped - and
  `complete` is `false` while it is non-empty, because `spending` may then be missing a payment that
  turns out to be a real expense (PR-011, the story's data-quality rule).
- Like the balance, it sums signed amounts including voided rows, so a void's reversing row nets
  against its original. A voided payment is therefore never "awaiting a decision" - a proposal on a
  row voided since is moot, and a voided payment stays in the sum where its reversing row cancels it
  (excluding the original while counting the reversal would understate spending by the payment).
  Confirming a proposal on a voided payment or credit is a 409. Only accounts the caller may `READ` contribute (transaction-level detail is
  not shown at `BALANCE_ONLY`). Currencies are not converted: a realised-flow FX conversion belongs
  to EPIC 10.

**Worked example (golden case V-13).** Purchases of CHF 700 (10 Aug) and CHF 500 (28 Aug) on the
card; on 3 Sep CHF 1,200 leaves the current account and a CHF 1,200 credit is booked on the card.

| | Aug spending | Sep spending | Card balance |
|---|---|---|---|
| CHF 1,200 payment matched | 1,200 | **0** | 0 |
| no settlement source (control) | 1,200 | 1,200 (double count) | 0 |
