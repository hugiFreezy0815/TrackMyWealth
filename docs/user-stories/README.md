# User Stories — Backlog Index

87 development-ready stories across 24 epics (`EPIC-01` through `EPIC-28`, minus 20–24 — see
below), each following the story template at the bottom of this document. `docs/architecture/`
explains *why* the schema is shaped the way it is; this backlog is *what to build* against it.

## How this backlog is organised

- **`EPIC-01` … `EPIC-28`** (this directory, minus 20–24): fully decomposed, development-ready
  stories — Given/When/Then acceptance criteria, explicit Definition of Done, requirement-ID
  traceability back to the specification.
- **`BACKLOG-remaining-epics.md`**: EPIC 20–24 and 29–32. Real MVP-or-near-MVP scope, but not yet
  broken into individual stories — each has an anchor, a sizing note, and starter story titles.
  Decompose an entry into the full template below during the sprint that picks it up.

## Suggested build sequence

This is the dependency order the stories themselves encode (see each story's **Dependencies**
line), not an arbitrary priority call:

1. **Foundation** — `EPIC-01` (bootstrap, migrations, initial admin/workspace, reference-data
   baseline).
2. **Tenancy & auth** — `EPIC-02` (login, sessions, MFA) and `EPIC-28` (the
   `app.current_workspace_id` request-scoping mechanism the row-level-security policies in every
   later epic assume exists). Nothing past this point can be honestly implemented without it —
   `US-28-01` is a hard dependency of every other workspace-scoped story in the backlog.
3. **Workspace / institution / account** — `EPIC-03`, `EPIC-04`, `EPIC-05`, `EPIC-06` (FX, needed
   as soon as more than one currency is in play), `EPIC-09` (credit cards, an account extension).
4. **Transaction ledger, imports, categorization, reconciliation** — `EPIC-07`, `EPIC-08`,
   `EPIC-25`.
5. **Investment core** — `EPIC-12` (security master), `EPIC-13` (listings), `EPIC-14` (prices &
   corporate actions), `EPIC-15` (holdings/tax lots), `EPIC-16` (performance/TWR/MWR), `EPIC-17`
   (asset allocation), `EPIC-26` (pension/retirement accounts).
6. **Budgeting, net worth, consolidated reporting** — `EPIC-10`, `EPIC-11`, `EPIC-18`, `EPIC-19`.
7. **Cross-cutting, throughout** — `EPIC-27` (golden-dataset/calculation verification) grows
   alongside every numeric epic above it, not after them; treat its fixtures as living tests, not
   a final QA pass.
8. **Backlog** (`BACKLOG-remaining-epics.md`) — pull `EPIC-30`'s `FR-JOB-*` price-refresh and
   daily-valuation jobs forward alongside `EPIC-14`/`EPIC-16` rather than deferring all of
   `EPIC-30`; same for any `EPIC-22` (privacy/security) item a security review flags as blocking
   an earlier epic. `EPIC-24` (external bank/broker connectors) is explicitly Post-MVP — do not
   schedule before `EPIC-07`'s import framework is solid, since every connector feeds the same
   `import_batch`/`transaction` pipeline.

## Epic index

| Epic | Title | Migration(s) | Stories | Priorities |
|---|---|---|---|---|
| 01 | Application Foundation & Configuration | V19, V20, ADR-0001 | 4 | 3 MUST, 1 SHOULD |
| 02 | User Administration & Authorization | V2, V16 | 5 | 5 MUST |
| 03 | Workspace & Ownership | V2, V6 | 4 | 4 MUST |
| 04 | Financial Institutions | V3 | 4 | 3 MUST, 1 SHOULD |
| 05 | Account Management | V4, V5 | 5 | 5 MUST |
| 06 | Currency & FX Management | V8 | 3 | 3 MUST |
| 07 | Transaction Ledger & Imports | V10, V15 | 5 | 5 MUST |
| 08 | Transaction Categorization | V13 | 4 | 4 MUST |
| 09 | Credit Cards | V5 | 4 | 2 MUST, 2 SHOULD |
| 10 | Budgeting & Cash Flow | V14 | 5 | 2 MUST, 3 SHOULD |
| 11 | Assets, Liabilities & Net Worth | V4, V5, V11 | 4 | 2 MUST, 2 SHOULD |
| 12 | Security Master & Issuers | V7 | 4 | 4 MUST |
| 13 | Security Listings & Exchanges | V8 | 3 | 3 MUST |
| 14 | Market Prices & Corporate Actions | V8, V9 | 4 | 4 MUST |
| 15 | Investment Portfolios & Holdings | V11 | 4 | 4 MUST |
| 16 | Performance / TWR / MWR | V11 | 4 | 4 MUST |
| 17 | Asset Allocation / GICS / SNB | V7, V18 | 4 | 3 MUST, 1 COULD |
| 18 | Institution Summary | V3 | 1 | 1 SHOULD |
| 19 | Consolidated Reporting | — (aggregates all prior epics) | 3 | 1 MUST, 2 SHOULD |
| 25 | Snapshots & Reconciliation | V11 | 4 | 4 MUST |
| 26 | Pension & Retirement Accounts | V5, V14 | 3 | 1 MUST, 2 SHOULD |
| 27 | Calculation Verification & Golden Dataset | — (test methodology, no new tables) | 2 | 2 MUST |
| 28 | Tenancy, Authentication & Authorization | V20 | 4 | 4 MUST |
| 20–24, 29–32 | See `BACKLOG-remaining-epics.md` | V14, V18 (partial) | not yet decomposed | — |

## Story template

Every story in `EPIC-01`–`EPIC-28` follows this shape. Use it when decomposing an entry from
`BACKLOG-remaining-epics.md`:

```
## US-<epic>-<seq> — <title>

**Actor:** who initiates this
**Objective:** the requirement ID(s) this satisfies, and why
**Story:** As a <actor>, I want <capability>, so that <benefit>.
**Preconditions:** state required before this story applies
**Acceptance criteria:** Given/When/Then, one per bullet, covering the happy path and the
  behaviourally significant edge cases (not exhaustive input validation)
**Applicable business rules:** FR-/RULE-/DM- IDs
**Data requirements:** required fields, formats, uniqueness constraints
**Error/edge cases:** what happens when preconditions aren't met, concurrency, partial failure
**Authorization/privacy:** who may call this, tenancy/RLS implications
**Dependencies:** other stories/epics this requires first
**Priority:** MUST / SHOULD / COULD
**Definition of Done:** the specific test(s) that must exist and pass
**Data-quality behaviour:** how estimated/stale/missing data is surfaced (PR-011) — N/A if the
  story has no numeric/financial output
```
