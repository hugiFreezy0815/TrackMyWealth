# Sprint 3 - Ledger foundation

Sprints are the GitHub label `sprint-3` on the story issues. Story text lives in
`docs/user-stories/EPIC-XX-*.md`; each issue copies it and adds the sprint-3 scope note.

**Goal:** replace the thin ledger slice built for the credit-card stories (US-09-01..04) with the
real transaction ledger, security master, categorization and transfer handling, which nearly every
later epic (08, 10, 12, 15, 25) depends on.

**Starting point (2026-09-23):** sprints 1 and 2 are fully closed, no open issues. `TransactionService`
only accepts what the card stories needed; `GET /cash-flow` and `GET /net-worth` are partial stand-ins.
Latest migration is V31; next free is V32.

## Committed (10 stories)

| Issue | Story | Pri | Size | Blocked by |
|---|---|---|---|---|
| #140 | US-07-01 Record a transaction of any type | MUST | L | - |
| #141 | US-12-01 Lazy security-master creation | MUST | M | - |
| #142 | US-25-01 Manual snapshot entry | MUST | S | - |
| #143 | US-07-02 Void and compensating entry (T1, T2) | MUST | L | #140 |
| #144 | US-08-04 Custom hierarchical categories | MUST | M | - |
| #145 | US-08-01 Automatic categorization | MUST | M-L | #140, #144 |
| #146 | US-08-02 User override always wins | MUST | S | #145 |
| #147 | US-10-01 Internal transfers excluded from income/expense | MUST | M | #140 |
| #148 | US-01-04 Reference-data baseline endpoint (+ US-02-05 check) | SHOULD | S | - |
| #149 | EPIC-29 API conventions (money as strings, error envelope, no entities) | MUST | M | - |

Suggested order: week 1 - #140 (cash types), #141, #142, #144; week 2 - #140 (investment types),
#145, #146, #143; week 3 - #147, #148, #149.

## Stretch (priority order)

1. US-08-03 retroactive rule application with preview.
2. EPIC 31: reject writes to a `DELETED` account in one shared place (`AccountLookupService`);
   today only `reassignInstitution` does.
3. US-12-02 identifier resolution (ISIN / Valor / WKN / ticker).
4. Start US-27-01 golden dataset.

## Decisions taken (2026-09-23)

- **US-07-02 T3** (void reopens reconciliation) is deferred into US-25-02; sprint 3 does T1 + T2.
- **US-10-01** extends `settlement_match` (V30) instead of adding a second matching table;
  ambiguous or one-sided matches stay proposed-only, rejections sticky.
- **US-08-01** ships a small MCC / ISO 20022 seed (~30-40 codes) via a new migration.

## Not in sprint 3

- Import framework (US-07-03/04/05): natural start of sprint 4.
- EPIC 14 prices and corporate actions: blocked on OPEN-005.
- US-17-04 SNB classification: blocked on OPEN-007.

## Open specification decisions and when they start to block

| Item | Blocks | Needed by |
|---|---|---|
| OPEN-008 cost-basis method | US-15-02 | before sprint 4/5 |
| OPEN-017 store vs recompute daily valuation series | US-16-01, performance targets | before sprint 5 |
| OPEN-005 market-data / FX providers | EPIC 14 | before sprint 5 |
| OPEN-006 / 007 / 019 GICS licence, SNB taxonomy, fund look-through | EPIC 17 | before EPIC 17 |
| OPEN-012 Personal Assets container | schema assumption (rule C2) | confirm closed |

## Sprint 4 preview

Import framework (US-07-03/04/05), reconciliation (US-25-02/03/04, incl. the deferred T3 criterion),
positions US-15-01/02 (if OPEN-008 is decided), US-11-01 net worth, Quartz price and FX jobs.
