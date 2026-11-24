# S10 Liability detail: loan, mortgage and credit card

**Figma** (page "🧭 Design round S9–S15 (#680–#683)"): M27 loan EN [191:278](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=191-278) / DE [191:481](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=191-481) · M27b credit card EN [191:387](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=191-387) / DE [191:588](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=191-588). Web uses the W06 account layout with these panels in the right column.
**Field 7 "Interest paid in {year}" (#712):** shown in M27 above; state boards at 320 px EN [220:4167](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=220-4167) / DE [221:4192](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=221-4192) · dark EN [221:4425](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=221-4425) / DE [221:4658](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=221-4658); W06 loan right column (340 px) EN [220:4344](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=220-4344) / DE [221:4369](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=221-4369) · dark EN [221:4602](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=221-4602) / DE [221:4835](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=221-4835). Dark M27: EN [202:2963](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=202-2963) / DE [206:4627](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=206-4627).
**Requirements:** FR-LIAB (loans, amortisation), FR-CARD (statement cycle, settlement),
FR-REC, NFR-UX-003/004, FR-API-010.
**Rules for every screen:** see [README](README.md#rules-for-every-screen).
**Builds on:** [S3 Account detail](account-detail.md). Everything S3 specifies (header, value
status, reconciliation line, transaction list, archive) applies. This spec only adds the
liability panels.

## Purpose

For a liability, a balance and a list of bank bookings do not answer the member's questions:
"How much do I still owe, how much of my payment was interest, when is it paid off, when is
the card bill due?" In the getquin example a loan payment is one booking, and the
split is only inside its purpose text. Our API already stores the split
(`LoanRepaymentResponse.interestAmount` / `principalAmount`) and the amortisation schedule, so
S10 shows them as figures.

## When S10 is used instead of plain S3

| Account (`AccountSummaryResponse`) | Screen |
|---|---|
| `hasAmortisation = true` (loan, mortgage) | S3 + **loan panels** |
| `hasStatementCycle = true` (credit card) | S3 + **card panel** |
| other liabilities (`nature = LIABILITY` without either flag) | plain S3 |

The route stays `/accounts/{accountId}`. S10 is a variant of S3, not a new route.

## Layout: loan (M27)

```
‹ Example Bank · Loan · EUR                             [⋯]
  Home loan
┌ OWED                                                 ┐
│ EUR 52'400.00                                        │
│ As of 30 Oct 2026 · from the loan statement          │
│ ✓ Reconciled · matches the statement of 30 Oct 2026  │
└──────────────────────────────────────────────────────┘
┌ NEXT PAYMENT                                         ┐
│ 30 Nov 2026                           EUR 650.00     │   ← entry amount
│ Principal EUR 520.00 · Interest EUR 130.00           │   ← schedule entry, as sent
│ Owed afterwards EUR 51'880.00                        │
│ ▁▂▃▄▅▆▇ remaining balance until 2034 (chart)         │   ← schedule remainingBalance points
│ Paid off on 30 Sep 2034 (last schedule entry)        │
└──────────────────────────────────────────────────────┘
REPAYMENTS                                       See all
┌──────────────────────────────────────────────────────┐
│ Interest paid in 2026                                │   ← field 7, current year's entry
│ EUR 1'352.90                                         │   ← interestPaid, as sent
│ From 10 repayments  [Estimated split]                │   ← paymentCount; badge if splitEstimated
├──────────────────────────────────────────────────────┤
│ 30 Oct  EUR 650.00   Principal 518.70 · Interest 131.30│
│ 30 Sep  EUR 650.00   Principal 517.40 · Interest 132.60│
│         [Estimated split]                             │
└──────────────────────────────────────────────────────┘
Activity  (S3 transaction list)
```

## Layout: credit card (M27b)

```
┌ CURRENT STATEMENT                                    ┐
│ 20 Oct – 19 Nov 2026         EUR 486.20              │
│ Due 5 Dec 2026                       [Not paid yet]  │
└──────────────────────────────────────────────────────┘
```

- "Not paid yet" / "Paid" is a badge with text (`paid`). It is never a colour alone.
- When `closingBalance` is null: "Statement amount not known yet".

## Requests

1. As S3 (requests 1–4).
2. Loan: `GET /api/v1/accounts/{accountId}/amortisation-schedule?sort=dueDate,asc&size=200`
   → `AmortisationScheduleEntryResponse[]`. The **next payment** is the first entry with
   `dueDate ≥ today`. Picking an entry by date is a lookup, not a computation. The chart uses
   `remainingBalance` per `dueDate`. "Paid off on" is the `dueDate` of the last entry.
   With more than 200 entries, page on, up to 3 pages (a 30-year monthly loan has 360).
3. Loan: `GET /api/v1/accounts/{accountId}/repayments?size=3&sort=bookingDate,desc` →
   `LoanRepaymentResponse[]` (`amount`, `principalAmount`, `interestAmount`, `splitEstimated`,
   `splitSource`).
4. Card: `GET /api/v1/accounts/{cardAccountId}/statement` → `CardStatementResponse`.
5. Loan: `GET /api/v1/accounts/{accountId}/repayments/yearly-totals` →
   `LoanRepaymentYearlyTotalsResponse` (`currency`, `years[]` newest first, each `year`,
   `amountPaid`, `principalPaid`, `interestPaid`, `unknownSplitAmount`, `paymentCount`,
   `splitEstimated`). "This year" is
   the entry whose `year` is the current calendar year; no entry ⇒ nothing paid this year.

## Fields

| # | Element | Label key (EN / DE) | Source | Rule |
|---|---|---|---|---|
| 1 | Value label | `account.owed` "Owed" / "Offen" | `nature = LIABILITY` | replaces "Balance"; magnitude shown as sent |
| 2 | Next payment date + amount | `loan.nextPayment` "Next payment" / "Nächste Rate" | (2) entry `dueDate`, `amount`, `principalComponent`, `interestComponent` | the total is the entry's `amount` (G10-1, #693), never added in the app; below it the line "Principal … · Interest …" |
| 3 | Owed afterwards | `loan.remainingAfter` "Owed afterwards" / "Danach offen" | entry `remainingBalance` | |
| 4 | Balance chart | text alternative `loan.chartSummary` "From {first} to {last}: {start} down to {end}" | (2) points | `ValueChart` in liability colour, no fill |
| 5 | Paid off on | `loan.paidOffOn` "Paid off on {date}" / "Abbezahlt am {date}" | last entry `dueDate` | hidden when the last `remainingBalance ≠ 0`; then `loan.scheduleEnds` "Schedule ends {date}, {remaining} still owed" |
| 6 | Repayment row | `loan.repayment.split` "Principal {p} · Interest {i}" / "Tilgung {p} · Zinsen {i}" | (3) | `splitEstimated` ⇒ `Estimated split` badge with the explanation "The split was estimated from the schedule ({splitSource})" |
| 7 | Interest paid this year | `loan.interestYtd` "Interest paid in {year}" / "Bezahlte Zinsen {year}"; below it `loan.interestYtdCount` "From {count, plural, one {# repayment} other {# repayments}}" / "Aus {count, plural, one {# Rückzahlung} other {# Rückzahlungen}}" | (5) entry of the current year: `year`, `interestPaid`, `paymentCount`, `splitEstimated` (G10-2, #693) | **Place:** first block of the Repayments card, above the repayment rows, divided by a line. It sums *past* payments, so it sits with them, not in "Next payment" (the future) or the "Owed" card (the balance). `{year}` is the sent `year`. The block is one accessibility element ("Interest paid in 2026: EUR 1'352.90, from 10 repayments, estimated split"). **States** (all drawn on the #712 boards): (a) `interestPaid` as sent, in `Number/L`; (b) `splitEstimated` ⇒ `Estimated split` badge after the count, same tap explanation sheet as on a repayment row, text `loan.interestYtdEstimated` "Some of this year's splits were estimated." / "Ein Teil der Aufteilungen dieses Jahres ist geschätzt."; (c) `interestPaid = null` ⇒ `loan.interestUnknown` "Not known" / "Unbekannt" in text/secondary, never 0, with `loan.splitUnknown` below instead of the count; (d) the request fails (network, 403, 404, 5xx) ⇒ "–" in place of the amount, no count line (PO 2026-11-23); screen readers hear `loan.interestYtdUnavailable` "Interest paid in {year}: not available" / "Bezahlte Zinsen {year}: nicht verfügbar" instead of the dash; (e) no entry for the current year ⇒ the block and its divider are absent, the card starts with the first repayment; (f) `interestPaid` sent and `unknownSplitAmount > 0` (a payment with an unknown split is in `amountPaid` and `unknownSplitAmount`, not in `interestPaid`) ⇒ below the count the caption `loan.interestYtdPartial` "Not included: {amount} without a known split" / "Nicht enthalten: {amount} ohne bekannte Aufteilung", `{amount}` = `unknownSplitAmount` as sent (PO 2026-11-24). With `interestPaid = null` state (c) applies and no caption is shown |
| 8 | Card statement period | `card.statement.period` "{from} – {to}" | (4) `periodStart`, `periodEnd` | |
| 9 | Card statement amount | | (4) `closingBalance`, `currency` | null ⇒ `card.statement.amountUnknown` |
| 10 | Card due | `card.statement.due` "Due {date}" / "Fällig am {date}" | (4) `dueDate`, `paid` | badge `card.paid` / `card.notPaid` |

## Actions

| Action | Result | Request |
|---|---|---|
| "See all" repayments | Full repayment list (paged, `size=50`) | (3) with paging |
| Tap a repayment | Its transactions (`transactions[]`) in [S4](transaction-detail.md) | loaded |
| ⋯ → "Record a repayment" | **Hidden** until a UI story covers `POST …/repayments` | |
| ⋯ → "Edit schedule" | **Hidden** (schedule editing is admin-like; later story) | |
| Tap the chart | Explanation sheet: source of the schedule, dates, currency | loaded |

## States

| State | Rule |
|---|---|
| Loan without schedule entries | The "Next payment" card shows `loan.noSchedule` "No repayment schedule yet. It is read from your loan statement." No chart |
| Schedule in the past only (loan paid off) | The card shows `loan.paidOff` "Paid off on {date}" |
| No repayments | The Repayments card is hidden; the S3 activity list still shows bookings |
| Yearly totals fail, repayments load | Field 7 shows "–"; the repayment rows show as usual. Field 7 never blocks the card |
| Card statement 404 / not configured | The card panel is hidden. With `hasStatementCycle=true` but no config: `card.noStatement` "Statement day not set" |

## Design decisions (vs. the getquin example)

| getquin | TrackMyWealth |
|---|---|
| A loan is a list of bank bookings; split only inside the booking text | Split as figures, from the server, with an estimated badge where it applies |
| No schedule, no payoff date | Schedule chart + payoff date |
| Card shown like any account | Statement period, amount, due date, paid state |

## PO decisions

| Date | Decision |
|---|---|
| 2026-11-23 | Field 7 shows "–" when the yearly totals cannot be loaded, also on an access error |
| 2026-11-24 (#712) | Field 7 sits at the top of the Repayments card. The label names the year, "Interest paid in {year}" / "Bezahlte Zinsen {year}", and the amount goes on its own line. This replaces the provisional "Interest this year: {amount}" from #708. The count line "From {count} repayments" stays. A part of the year without a split gets the "Not included" caption (state f) |
| 2026-11-24 (#712) | The Next payment card shows the entry `amount` right of the date in Figma (M27 and the W06 loan column) |

## API gaps

| Gap | Needed for | Owner story |
|---|---|---|
| G10-1 payment total on `AmortisationScheduleEntryResponse` (`amount`) | field 2 total | #693 (done) |
| G10-2 interest and principal paid per year (`/repayments/yearly-totals`) | field 7 | #693 (done) |

## Acceptance checks for the UI story

- Given a loan with a schedule, the next payment is the first entry due today or later, and
  every amount shown is a field from the API.
- Given a repayment with `splitEstimated = true`, the row shows the "Estimated split" badge and
  its explanation.
- Given a card statement with `paid = false`, the panel shows "Not paid yet" as text.
- Given an account with neither flag, the screen is plain S3.
