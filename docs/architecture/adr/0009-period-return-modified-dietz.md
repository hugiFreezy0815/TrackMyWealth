# ADR 0009: Period return with Modified Dietz until the time-weighted return

## Status
Accepted (PO, 2026-11-16, in #607). The tracking start of cards and loans without a recorded
balance was decided on 2026-11-20 (#607), as was what counts as return (decision 6). To be
superseded internally by US-16-02.

## Context

The change chip on Overview and Portfolio View showed `change.percent`, the wealth change
`amount / |value at from|` (#477). That counts net saving, and accounts that start being tracked,
as growth. The web check of #585 found +50 % on a year in which nothing grew: an account entered
the tracked history with its first balance. The `INCEPTION_UNCERTAIN` and `VALUE_UNKNOWN` warnings
then hid the whole chip, so YTD, 1Y and Max showed no percentage for almost every member.

EPIC 16 will deliver a time-weighted return (US-16-02) from daily valuations, with "since data
available" periods (US-16-04). Those daily valuations do not exist yet.

## Decision

1. **Method.** The history responses add `change.returnPercent`, a Modified Dietz return:
   `(V_end − V_start − ΣCF) / (V_start + Σ w_i · CF_i)`, with `w_i = (T − t_i) / T`. It needs
   only the two end values and dated flows, all of which the backend already has. The rules are in
   `calculation-methodology.md`, "Period return".
2. **Tracked history.** Each account takes part from its tracking start, the first day its value
   rests on a recorded balance. An account starting inside the period is a flow, never gain. No
   balance is imputed. This follows US-16-04's "since data available".
3. **Return start.** `change.returnFrom` is the period start, or the scope's earliest tracking
   start when that is later. The app then labels the chip "since {date}".
4. **API (ADR 0006, additive).** `returnPercent` and `returnFrom` are new nullable fields on
   `ValueHistoryResponse.Change`, for the whole scope and for each section. `change.percent` stays
   as the wealth change and is not deprecated.
5. **Replacement.** When US-16-02 provides daily valuations, the time-weighted return replaces
   Modified Dietz behind the same fields. Clients do not change.
6. **What is return** (PO, 2026-11-20). Interest, dividends and fees, and tax on an account that
   holds positions, are what the money earned or cost: they are return, not flows. Income,
   spending, a tax bill from a cash account and a deposit of unknown origin are external flows.
   The breakdown line (#477) is unchanged.

## Consequences

- A return is shown for every period with a tracked history, including periods that start
  before the first recorded balance.
- Modified Dietz assumes flows are spread evenly within a day, and it weights a large flow by its
  date. With large flows during volatile markets it differs from a time-weighted return. That is
  acceptable for a period chip and goes away with US-16-02.
- With debt in the scope the net-worth return is a return on equity, leveraged by the debt
  (methodology, "Debt in the scope").
- An ordinary ledger account without an opening balance has no known value and is left out of
  the return. Recording an opening balance (US-25-04) brings it in.
- The Overview portfolio rows still show the wealth change from the portfolio list's monthly
  change. A follow-up story moves them to the return.
