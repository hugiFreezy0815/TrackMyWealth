# Sprint 5 - CSV import and opening balances

Sprints are the GitHub label `sprint-5` plus Iteration 5 (2026-10-13 to 2026-10-26) on Project #1.
The issues hold the full story text, design and Definition of Done; this file is the overview.

**Goal:** get real data in and make it count. A member can import a bank's CSV export
(template, preview with duplicate detection, commit, rollback), and a cash account gets a known
balance from a dated opening balance. Workspace totals use one agreed workspace currency, and the
mobile app can sign in.

**Starting point (2026-10-03):** sprint 4 is done (#187, the merge gate, closed 2026-10-03). EPIC 01-09, most of 28/29 and US-06-04 (ECB FX import) are on `main`. Latest
migration is V57. The import tables (V15) and `account_snapshot.is_opening_balance` (V11) exist
but no code uses them. Every cash, savings and depot account is still `valueKnown = false`.

## Committed (6 stories + 1 analysis spike + 1 build fix)

| Issue | Story | Pri | Size | Blocked by |
|---|---|---|---|---|
| #237 | BUILD: pin CI runners to `ubuntu-24.04`; **day 1, merged by 2026-10-16** (`ubuntu-latest` moves to Ubuntu 26 on 2026-10-19) | SHOULD | S | - |
| #238 | SPIKE: which sample documents create the container details, which import transactions/positions (2-day time box) | MUST | S | - |
| #229 | US-07-03 CSV import templates: model, versioning, fingerprint, parser engine | MUST | M | - |
| #230 | US-07-04 Upload, preview with duplicate detection, commit | MUST | L | #229 (parser interface) |
| #231 | US-07-05 Roll back an import batch (delete while unmodified, void once modified) | MUST | M | #230 |
| #232 | US-25-04 Opening balance (cash part) makes cash accounts valued | MUST | M | - |
| #224 | US-06-05 Workspace currency, container default, ad hoc `currency` | MUST | M | - |
| #183 | US-02-06 Mobile sign-in: MFA, secure token storage, refresh, sign-out | MUST | L | - |

Suggested order and parallel tracks:

- **Build:** #237 on day 1, before anything else merges against the moving runner image.
- **Import track:** days 1-2, the spike #238 analyses the local samples (structure only) and confirms or amends #229/#230's template scope. In parallel, agree the parser interface between #229 and #230. #230 then builds
  upload/preview against a stub parser while #229 finishes. Commit/dedup-under-lock is #230's
  second PR. #231 follows in week 2.
- **Valuation track:** #232 and #224 from day 1, independent of each other and of the import.
- **Mobile track:** #183 from day 1.

## Stretch (priority order)

1. #234 US-25-02 reconciliation engine, cash-balance scope (needs #232).
2. #235 US-25-03 guided resolution: accept, dismiss, reopen (needs #234).
3. #184 US-01-05 reference-data admin screen in the app (needs #183).
4. #226 US-06-06 FX provider selection with its own hub currency.
5. #227 US-06-07 FX import interval set by an administrator at runtime.

Stretch issues get the `sprint-5` label and Iteration 5 only when pulled in.

## Decisions taken (2026-10-02, product owner)

- **Sample CSV files are real bank data and stay local** (amended 2026-10-03). The product
  owner keeps exports under `docs/import-samples/<institution>/` on their machine only. The
  folder is in `.gitignore`. Never commit, paste, log or quote their contents, including in issues,
  PRs, test names or commit messages, and file names too (some contain an IBAN). Use them only to
  learn each format: delimiter, encoding, header, preamble and trailer rows, number and date
  formats. Build committed fixtures in `backend/src/test/resources/import/` as **synthetic files**
  with the same structure and invented values (fake IBANs such as `DE00 0000 …`, invented names and
  amounts). Do not copy real rows and then edit them. An optional local-only test may parse the
  real samples when the folder exists (skipped otherwise, so CI never needs them) and report only
  counts per status. The framework is tested against one Swiss and one German synthetic fixture
  (golden cases V-18..V-21). Shipped per-institution templates (samples exist for DKB, Sparkasse
  Freiburg incl. its CAMT.052 CSV variant, PostFinance; TrueWealth and Yuh still without samples) are separate stories, filed by the spike #238.
- **Original file is stored in the database** (`import_file`, `bytea`, 5 MB / 20,000 rows),
  not only its rows, so a batch can be re-parsed with a corrected template.
- **Duplicate rule without a bank reference:** exact match on account, booking date, amount,
  currency and normalised description, with no date tolerance. Identical rows count by
  multiplicity. The member can force-include any flagged row.
- **US-25-04 split:** the cash opening balance is in sprint 5 (#232). Opening holdings with cost
  basis are #233 (US-25-05, backlog, needs EPIC 15).
- **Workspace currency (#224):** new `workspace.currency`. `app_user.reporting_currency` stays
  only as the member's personal default for ad hoc reads. "Container" means the financial
  institution. Any active member with a login may change it (EPIC 03 has no workspace roles).
- **Batch rollback hard delete** is the one permitted hard delete of transactions (FR-LIF-001
  matrix, FR-LIF-010), guarded by a `BEFORE DELETE` trigger that only the rollback path passes.

## Working agreements for this sprint

- Several stories add migrations in parallel (#229, #230, #231, #232, #224). Take the next free
  `V<n>` when you open the PR, rebase and renumber if another PR merged first, and run
  `./mvnw clean` after switching branches (stale `target/classes` with two equal versions breaks
  every Spring test).
- Before starting a ticket, `git fetch` and check `gh pr list --search "<issue#>"`: other sessions
  work this backlog in parallel.
- Merge only through `scripts/merge_pr.py` (the merge gate from #187/#228) once it is on `main`.

## Not in sprint 5

- EPIC 15 positions and cost basis (US-15-01/02): blocked on **OPEN-008** (cost-basis method).
- EPIC 14 prices and EPIC 16 performance: blocked on **OPEN-005** (market-data provider) and
  **OPEN-017** (store vs recompute the daily valuation series).
- Holdings reconciliation and opening holdings (#233): after EPIC 15.

## Open specification decisions to take during sprint 5

These were due "before sprint 5" in `SPRINT-3.md` and now block sprint 6's investment core.

| Item | Blocks | Needed by |
|---|---|---|
| OPEN-008 cost-basis method | US-15-01/02, #233 | sprint 6 planning |
| OPEN-005 market-data / price provider | EPIC 14, US-12-02 | sprint 6 planning |
| OPEN-017 store vs recompute daily valuation series | US-16-01, US-11-02 | sprint 7 |

## Sprint 6 preview

Positions and cost basis (US-15-01/02) if OPEN-008 is decided, opening holdings (#233),
holdings reconciliation, the first shipped institution templates (from the spike #238; synthetic fixtures only),
mobile import and accounts screens on top of #183.
