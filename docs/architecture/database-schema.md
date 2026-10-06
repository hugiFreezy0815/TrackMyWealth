# TrackMyWealth — Database Schema Design

This document is the companion to the Flyway migrations under
`backend/src/main/resources/db/migration/`. It explains the shape of the schema and *why* it is
shaped that way; the migrations themselves carry the requirement-ID-level detail (grep them for
`FR-`, `DM-`, `RULE-` and `DB-` references back to the requirements documents in the project). It
also satisfies **FR-POR-003** — schema documentation sufficient for the statutory-portability
extraction described in section 31 to be performed reliably.

Source documents: `requirements-personal-wealth-platform.md` and
`TrackMyWealth_Consolidated_Requirements_Specification_v5.docx` (both in the project's knowledge
base). Requirement IDs below refer to the consolidated v5 specification unless noted otherwise.

## 1. Design principles carried through every table

| Principle | Where it comes from | How it shows up in the schema |
|---|---|---|
| Money is never a float | DB-01, NFR-CALC-001, NFR-TEC-001 | `NUMERIC(20,4)` for amounts, `NUMERIC(28,10)` for quantities, everywhere, no exceptions |
| Class-table inheritance, never `INHERITS` | DM-18, DB-09/DB-13 | `account` is the abstract parent; `account_credit_card`, `account_mortgage`, `account_pension`, etc. are extension tables sharing its PK |
| Behaviour varies by capability, not by subtype | FR-ACC-010/011 | Boolean capability flags on `account` (`holds_positions`, `is_discretionary`, ...) rather than ever-deeper subtypes |
| The ledger is append-only | RULE-024, FR-TRX-007 | `transaction` has a `BEFORE UPDATE` trigger that rejects any change to a financial field; corrections are void-and-replace |
| A Snapshot and a Transaction are different things | RULE-025, FR-REC-001 | `account_snapshot`/`snapshot_holding` vs `transaction` are separate tables; `reconciliation_result` compares the two |
| Everything derived is rebuildable from source | FR-DAT-008 | `position`, `tax_lot`, `daily_valuation` are all documented as rebuild targets, never written to directly by an API request |
| Tenant isolation is enforced in the database | FR-TEN-003 | PostgreSQL row-level security on every workspace-scoped table (`V20`), not just service-layer filtering |
| A user override is never silently overwritten | RULE-031 | Provenance tables (`security_field_provenance`, `transaction_categorization_log`) record `is_user_override` and the refresh jobs must check it |
| Estimates are always visibly marked | PR-011 | `is_estimated` columns on `position`, `daily_valuation`, `security_asset_class_weight`, `tax_lot`, `snapshot_holding` |

## 2. Migration index

| File | Contents |
|---|---|
| `V1` | Extensions, `updated_at`/optimistic-locking trigger helpers |
| `V2` | `workspace`, `workspace_member`, `app_user` (System User vs Workspace Member, RULE-018) |
| `V3` | `institution_catalogue` (shared seed data) and `financial_institution` (the container) |
| `V4` | `account` — the class-table-inheritance parent, capability flags, generated `nature` column |
| `V5` | Account subtype extension tables: securities, credit card, mortgage, loan, pension, vested benefits, custom asset |
| `V6` | `account_ownership` (dated, fractional) and `sharing_grant` (explicit, revocable) |
| `V7` | Security Master: `issuer`, `security`, `security_identifier`, `security_asset_class_weight`, provenance and per-workspace overrides |
| `V8` | `listing` (Security ≠ Listing, DM-26), `price` and `fx_rate` — both range-partitioned by date |
| `V9` | `corporate_action` and successor linkage |
| `V10` | `transaction` — the append-only ledger, with an idempotency unique index and a split-category table |
| `V11` | `account_snapshot`, `snapshot_holding`, `reconciliation_result`, `tax_lot`/`tax_lot_disposal`, `daily_valuation` (partitioned) |
| `V12` | `position` (current) and `position_history` (time series) |
| `V13` | Three-layer categorization: `category`, `category_source_mapping`, `categorization_rule`, `transaction_categorization_log` |
| `V14` | `budget`/`budget_line`, `savings_rate_methodology`, `goal`, `pension_contribution_tracking` |
| `V15` | Template-driven import framework: `import_template`, `import_batch`, `import_row_raw` |
| `V16` | `refresh_token`, `user_session`, `authorization_denial_log` |
| `V17` | `financial_audit_log`, `admin_audit_log`, `auth_audit_log` — three separate audit surfaces by design |
| `V18` | `reference_package`, `pension_scheme_rule`, `gics_structure_version`, `fallback_sector_taxonomy`, `trading_calendar` |
| `V19` | Baseline reference-data seed (categories, institution catalogue, fallback taxonomy) — **must run before V20** |
| `V20` | Row-level security: `current_workspace_id()`, per-table policies, the workspace bootstrap sequence |
| `V21` | Fixes `trg_transaction_append_only` (V10) to also cover `fee_amount`/`fx_rate_to_account_currency`/`fx_rate_date`, which the original trigger omitted |
| `V47` | Bounds `authorization_denial_log`: `RATE_LIMITED` summary reason, `suppressed_count`, retention index (#205) |
| `V48` | Adds optimistic-concurrency revisions to remaining mutable API resources (#207) |
| `V49` | Adds immutable `corrects_transaction_id` lineage for transaction corrections (#178) |
| `V50` | Adds immutable `restores_transaction_id` lineage for restoring a void (#179) |
| `V51` | Adds `settlement_match.transfer_fx_rate`, the rate a confirmed cross-currency transfer implies (#181) |
| `V52` | `fx_rate_history_requirement`: the earliest booking date across all workspaces, kept by a trigger on `transaction` - how far back the FX import loads history (#223) |
| `V53` | `fx_rate_pre_2023`: one partition for FX history before 2023, moving any such rows out of `fx_rate_default` (#223) |
| `V54` | `fx_rate.derived` and `fx_rate_currency_in_use` (kept by triggers on `account`, `transaction`, `financial_institution`, `app_user`, `listing`): the FX import stores the cross rates between the currencies in use as master data; `transfer_detection_fx_pending`: transfer detection re-run once FX rates cover a date (#223) |
| `V55` | Row-level security for `savings_rate_methodology`, which `V20` had missed (#223 review) |
| `V56` | `transfer_detection_fx_pending.rechecks`: the FX job stops re-running a date no rate can ever cover (#223 review) |
| `V57` | `transaction`'s currency-in-use trigger reads `NEW.currency` directly instead of serialising the row (#223 review) |
| `V58` | Opening balances: `uq_account_snapshot_opening_balance` (at most one per account), `chk_account_snapshot_opening_balance_manual`, and the snapshot currency guard now expects a credit card's `billing_currency` (#232) |
| `V59` | `workspace.currency`: the workspace's display currency for workspace-level totals, backfilled from the oldest login member's reporting currency (#224). V58 is left to #232 (opening balance) |
| `V60` | Cash reconciliation: at most one account-level `reconciliation_result` per snapshot; security-level rows remain available for later holdings reconciliation (#234) |
| `V61` | `fx_import_setting`: one global row holding the FX import interval an administrator set at runtime; it wins over `FX_IMPORT_CRON` at every start (#227) |
| `V62` | `reconciliation_result.version` + `reconciliation_result_bump_version`: member decisions on a result need `If-Match` (#235) |
| `V63` | `transaction.reconciliation_result_id`: the result that booked a row as its adjusting entry, only on a manual `VALUATION_ADJUSTMENT` and only a result of the row's own account (composite FK on `(account_id, id)`, so never another workspace's), frozen by the append-only trigger (#235) |
| `V64` | Import template versioning: `template_family_id`, `is_current` (one current version per family, `uq_import_template_family_current`), `is_active`, `header_columns`, `version` + `import_template_bump_version`, and a check on the row-skipping counts (#229) |
| `V65` | `category` and `import_template`: V20's shared-or-own policy split per command - every workspace reads the shipped rows (`workspace_id IS NULL`), but `INSERT`/`UPDATE`/`DELETE` reach its own rows only. V20 let a workspace delete a shipped row and move a shipped template into its own workspace (#279) |
| `V66` | `import_template.file_format` (`CSV`, `PDF_TEXT`, `PDF_OCR`) and `pdf_layout` (required exactly for PDF) (#268, #276) |
| `V90` | Quartz job-store schema (framework-owned, deliberately gapped — see "Migration numbering and out-of-order application" below) |

All twenty of the original migrations have been applied end-to-end against a real PostgreSQL 16
instance as part of this design (including a positive/negative row-level-security test as a
non-superuser role); see the commit history for the fixes that came out of that pass
(reserved-word collision on `system_user`, partitioned-table primary key rules for
`price`/`fx_rate`/`daily_valuation`).

### Migration numbering and out-of-order application

`V90` (Quartz's own job-store schema) is deliberately numbered far above the domain migrations so
it reads as visually distinct framework-owned schema rather than another `V21`, `V22`, ... in the
same sequence as `workspace`/`account`/`transaction`. That only works because
`spring.flyway.out-of-order` is `true` (`application.yml`): once `V90` has been applied to a
database, Flyway's default (`out-of-order: false`) would refuse every subsequent domain migration
numbered below it (`V21`-`V89`) as "not applied in order." `V21` (the append-only trigger fix
below) is what surfaced this — it was rejected on a database that already had `V90` applied until
`out-of-order` was turned on. This is safe here because `V21` and `V90` touch disjoint,
independent parts of the schema (the transaction ledger vs. Quartz's tables); it would not
automatically be safe for two migrations that *do* depend on each other's ordering, so don't reach
for `out-of-order` reflexively when adding a future migration in this range - the pairing has to
be independent for it to be sound.

## 3. The account hierarchy (class-table inheritance)

```mermaid
erDiagram
    FINANCIAL_INSTITUTION ||--o{ ACCOUNT : contains
    ACCOUNT ||--o| ACCOUNT_SECURITIES : "extends (SECURITIES)"
    ACCOUNT ||--o| ACCOUNT_CREDIT_CARD : "extends (CREDIT_CARD)"
    ACCOUNT ||--o| ACCOUNT_MORTGAGE : "extends (MORTGAGE)"
    ACCOUNT ||--o| ACCOUNT_LOAN : "extends (LOAN)"
    ACCOUNT ||--o| ACCOUNT_PENSION : "extends (PENSION)"
    ACCOUNT ||--o| ACCOUNT_VESTED_BENEFITS : "extends (VESTED_BENEFITS)"
    ACCOUNT ||--o| ACCOUNT_CUSTOM_ASSET : "extends (CUSTOM_ASSET)"
    ACCOUNT ||--o{ ACCOUNT_OWNERSHIP : "owned via"
    ACCOUNT ||--o{ TRANSACTION : has
    ACCOUNT ||--o{ ACCOUNT_SNAPSHOT : "reported via"
    ACCOUNT ||--o{ POSITION : holds
```

Every subtype-specific table's primary key is also a foreign key to `account(id)`, which
structurally enforces "at most one extension row per account" (G2, disjoint specialisation) —
there is no way in SQL to attach two different extension rows to the same account. A
`trg_extension_type_guard` trigger additionally stops a `CREDIT_CARD` account from acquiring a
`account_mortgage` row by mistake. `account_type` itself is frozen after creation by
`trg_account_type_immutable` (FR-ACC-005/G5).

`nature` (`ASSET`/`LIABILITY`) is a PostgreSQL `GENERATED ALWAYS AS` column derived from
`account_type` (DB-12) — no application code path can write an inconsistent sign, which is the
specific bug DM-12 calls out ("a mortgage being added to rather than subtracted from net worth").

## 4. Tenancy enforcement

The isolation boundary is the workspace (FR-TEN-001). Every workspace-scoped table carries a
`workspace_id` column and a row-level-security policy comparing it to a PostgreSQL session
variable, `app.current_workspace_id`, set once per request/transaction by the application via
`SELECT set_config('app.current_workspace_id', ?, true)` — the `true` (is_local) argument scopes
it to the current transaction, so it can never leak across two requests sharing a pooled
connection. `FORCE ROW LEVEL SECURITY` is set on every one of those tables so that even an
elevated database role is bound by policy (NFR-DEP-004 — a deployment believed to have one
workspace is not permitted to relax any control).

The actual mechanism is `com.trackmywealth.backend.config.WorkspaceContextTransactionExecutionListener`
(US-28-01, see `docs/architecture/adr/0002-workspace-context-propagation.md`) — `V20__tenancy_row_level_security.sql`'s
own header comment now names it directly (originally a placeholder forward-reference predating the
class's own implementation, corrected in place under the household→workspace rename's one-time
exception to the "never edit an applied migration" rule - see `development-standards.md`).

Tables that hang off a workspace-scoped table but do not carry `workspace_id` directly
(`account_credit_card`, `tax_lot`, `price`, `snapshot_holding`, ...) are protected **transitively**
through a join to `account`/`security`/`account_snapshot`. `security`, `listing`, `price`,
`fx_rate`, `fx_rate_history_requirement`, `fx_rate_currency_in_use`, `fx_import_setting`, `issuer`, `institution_catalogue` and the reference-data
tables in `V18` carry **no** `workspace_id` at all and are **not** RLS-protected — this is deliberate: they are shared,
global reference data (DM-25, NFR-LIC-006/007), not tenant data.

**Shared-or-own tables** (`category`, `import_template`) hold shipped rows (`workspace_id IS NULL`)
next to workspace rows. Since `V65` their policy is one per command: `SELECT` sees shipped and own
rows, `INSERT`, `UPDATE` and `DELETE` own rows only. A single `USING (shared OR own) WITH CHECK
(own)` policy, as `V20` had, is not enough: `DELETE` is checked against `USING` alone, and an
`UPDATE` that sets `workspace_id` to the caller's workspace passes both. Because `SELECT ... FOR
UPDATE` must also pass the `UPDATE` policy, a service that locks such a row locks the workspace's
own rows only and, when that misses, tells a shipped row (read-only) from a hidden one by a plain
read (`ImportTemplateService.requireChangeable`).

`transfer_detection_fx_pending` (`V54`) is the one table with a `workspace_id` outside RLS: a
background job reads it across workspaces to re-run transfer detection once FX rates cover a date
(#223). It holds a workspace id and a date only - no amount, account or description - no endpoint
reads it, and the job does the detection itself inside that workspace (`SystemWorkspaceContext`),
under RLS like any request.

**Production hardening not yet wired up:** the application runs migrations and its own queries
under the same database role for local-development simplicity. Before a multi-workspace hosted
deployment goes live, split this into a migration-owner role (used only by Flyway, at deploy
time) and a `NOSUPERUSER`, non-owner runtime role (used only by the running application, granted
`SELECT/INSERT/UPDATE/DELETE` but not `CREATEDB`/`ALTER`) — the commented-out template at the
bottom of `V20__tenancy_row_level_security.sql` is the starting point. The automated cross-tenant
suite `FR-TEN-010` requires (`CrossTenantIsolationTest`, US-28-04) already runs as such a role -
`NOSUPERUSER NOBYPASSRLS` - because a superuser bypasses RLS unconditionally and the suite would
pass for the wrong reason; keep any new isolation test on that role, not the migration role.

### Object-level denial audit lifecycle (US-28-02, #205)

Every audited single-resource denial still returns the same generic 404 and records only the
principal, requested entity type/id and a non-enumerating reason. An audit row the budget below
calls for is **never lost** (decided 2026-10-01): it is written synchronously, as one auto-committed
`INSERT`, before the 404 is returned, so it is durable and survives the request's rollback.

**Connections.** Rows are written through a small connection pool of its own
(`AuthorizationDenialAuditPool`, `audit-pool-size`, 2 by default), never the main pool. The earlier
nested `REQUIRES_NEW` write took its second connection from the main pool while the request still
held one, so enough concurrent denials could wait on each other until the pool timed out. An audit
connection is never held while waiting for a main-pool one, so that circular wait cannot occur.
The audit pool:

- starts from the main pool's `spring.datasource.hikari.*` settings (driver/SSL
  `data-source-properties`, lifetimes), so the database is configured once;
- is deliberately not a `DataSource` bean, which would make Spring Boot back off its own;
- publishes Hikari's Micrometer metrics under `pool=denial-audit` and has its own health check
  (`authorizationDenialAuditPool`);
- adds its connections on top of `DB_POOL_MAX`: size PostgreSQL's `max_connections` for
  `instances × (DB_POOL_MAX + audit-pool-size)`.

**Timeouts, and the one 500 (decided trade-off).** A denied request keeps its main-pool connection
while it writes the audit row, so the write is bounded twice: `audit-connection-timeout` (1s by
default; startup fails unless it is shorter than the main pool's `connection-timeout`) and
`audit-statement-timeout` (2s, cancels a hung `INSERT`; a socket timeout of twice that is the
backstop). If the row still cannot be written - audit pool exhausted beyond its timeout, statement
cancelled, database down - the request fails with a 500 instead of continuing without its audit
row. This deliberately deviates from #205's first acceptance criterion ("no 500"): never losing a
row was decided to outweigh it. It is identical whether the id exists or not, so it reveals
nothing, and in normal operation it does not occur: a burst of 50 concurrent denials against the
2-connection pool all complete as 404 (`AuthorizationDenialAuditTransactionTest`).

**Volume.** To prevent a signed-in caller from growing the table without bound, exact `NOT_FOUND`
rows are capped per principal and refill window (200 per hour by default). The first denial over
that budget writes one `RATE_LIMITED` summary row with no requested id; later denials in the same
window add no rows, but are counted. The principal's first denial after the window closes writes a
second `RATE_LIMITED` row whose `suppressed_count` says how many were suppressed, so the magnitude of
probing survives. A principal who doesn't return has the count written when its window is evicted
from the bounded, expiring in-memory cache, or at shutdown; only a crash, or a database failure at
that moment (logged with the count), can lose it - the window's first summary row is already
durable. The budget is in memory, so with several application instances each bound applies once per
instance. By default one principal can cause at most about 202 rows per hour per instance (about
436,000 over the 90-day retention).

**Retention.** `AuthorizationDenialAuditRetentionService` deletes rows older than
`app.authorization-denial-audit.retention` (90 days by default), every `cleanup-interval` (1 hour),
in batches of `cleanup-batch-size` (5,000) rows per transaction, using V47's `occurred_at` index.
Several instances running it at once is harmless. All of these values are deployment configuration
in `application.yml`.

### Security master: lazy creation and manual mode (US-12-01)

`security` is created only when a workspace first references an instrument (FR-SMD-001) and is
shared afterwards (FR-SMD-004). `POST /api/v1/securities` finds or creates by ISIN;
`GET /api/v1/securities?isin=` looks up without ever writing (FR-SMD-007), and
`GET /api/v1/securities/{id}` is the only way to find a security that has no ISIN. There is
deliberately no search or listing of the shared master: a hand-entered private holding must not be
discoverable by another workspace. Rules that follow from the table being global, unscoped data:

- **Existing wins, visibly.** A second caller supplying a different name, currency or type for a
  known ISIN gets the existing row back unchanged, with an `X-Security-Ignored-Fields` header
  naming which supplied values differed (absent when nothing differed). Shared rows are never
  edited by a caller. A workspace-specific change is an override
  (`workspace_security_override`, US-12-04); note that a wrong `denominationCurrency` or
  `instrumentType` on the shared row therefore cannot be corrected by the workspace that
  introduced it, only overridden per workspace - US-12-04 must cover those fields.
- **No tenant traces.** Neither the row nor its `security_field_provenance` names the workspace or
  user that caused it to exist; a hand-entered value has source `MANUAL` (NFR-LIC-007).
- **Race-safe.** Creation is `INSERT ... ON CONFLICT DO NOTHING`, so two workspaces creating the
  same new ISIN at once yield one row (a caught unique violation would abort the PostgreSQL
  transaction).
- **Retry-safe without an ISIN.** An optional `idempotencyKey` makes a no-ISIN create safe to
  retry. It is stored only as a SHA-256 of workspace id and key inside the synthetic key
  (`MANUAL-<hex>`), so it is not recoverable and never collides across workspaces. Without a key
  every call creates a new record (`MANUAL-<uuid>`). A key together with an ISIN is a 422.
- **Who may create.** EDIT on at least one active account in the caller's workspace, and at most
  `app.rate-limit.security-create` requests per source (default 30/min), since every insert is
  visible to every tenant. Lookups are not rate-limited by this rule.
- **Manual mode is the supported baseline** (NFR-LIC-004/008, NFR-CON-004): no external
  reference-data provider is configured (OPEN-005), so a record is entered by hand (name,
  denomination currency, instrument type, asset class; ISIN optional and check-digit validated).
  The asset class is stored as one 100% `security_asset_class_weight` row flagged estimated.
  `legalName` is optional; when omitted it is set to the display name and its provenance
  confidence is `DERIVED`. Downstream code cannot tell such a record from a provider-fed one.
- **Completeness (FR-SMD-011).** The response lists the analysis-relevant fields still missing
  (`securityCountry`, `issuerCountry`, and `gicsSubIndustry` for equity instruments). Until a
  story can set a GICS code, a manual equity is always reported incomplete on `gicsSubIndustry` -
  that is the honest state, not an error.
- **Vocabulary.** `V32` adds a `CHECK` on `security.instrument_type` (EQUITY, ETF, FUND, BOND,
  CRYPTO, DERIVATIVE, OTHER; NULL allowed) so no other writer can put an unrecognised value into
  shared data. `CRYPTO` (an instrument type) and `CRYPTOCURRENCY` (an asset class) are different
  vocabularies on purpose; adding a type is a one-line migration.

### Snapshots: manual entry (US-25-01)

`POST /api/v1/accounts/{id}/snapshots` records a statement balance, and for an account that
`holds_positions` its per-security quantities, as a `MANUAL` `account_snapshot` with
`snapshot_holding` rows (FR-REC-006). It never touches the ledger (RULE-025).

- **Currency and sign.** A snapshot is always in the account's own currency - `native_currency`,
  or for a credit card its `billing_currency`, which its ledger is summed in (not client input;
  `V33` guards it, `V58` corrected the guard for cards) - and its balance uses the same convention
  as the ledger-derived balance (a liability's balance is the positive amount owed), so US-25-02
  can compare the two directly.
  `reported_cost_basis` is a total in that same currency.
- **Holdings** reference existing security-master ids only (create first via `POST
  /api/v1/securities`), at most once per snapshot (`V33`'s `uq_snapshot_holding_security`). A
  balance may be omitted only when holdings are given.
- **Duplicates and corrections.** A second `MANUAL` snapshot for the same account and date is a
  409 carrying `existingSnapshotId`; `PUT .../snapshots/{snapshotId}` replaces a `MANUAL`
  snapshot's balance and holdings and sets `updated_at`/`updated_by` (`V33`). Snapshots from any
  other source are never edited through the API.
- **Isolation.** `account_snapshot` is RLS-protected; `snapshot_holding` is not and is only read
  by the id of a snapshot already loaded under that policy.

### Opening balances (US-25-04)

An account's dated opening balance (FR-REC-007) is its one `account_snapshot` with
`is_opening_balance = TRUE`, written only through `/api/v1/accounts/{id}/opening-balance`; see
`calculation-methodology.md` for how it values the account.

- **One per account.** `V58`'s partial unique index `uq_account_snapshot_opening_balance` on
  `(account_id) WHERE is_opening_balance`; regular snapshots are unaffected. A second `POST` is a
  409 carrying `existingOpeningBalanceId`; a race between two first ones is a 409 `RETRY`.
- **Shape.** `chk_account_snapshot_opening_balance_manual`: `source = 'MANUAL'` and a non-null
  `balance`. No `snapshot_holding` rows (opening holdings with cost basis are US-25-05). It shares
  the `(account_id, snapshot_date, source)` key with regular manual snapshots, so one on the same
  date is a 409 naming the other, in both directions.
- **Listed, not edited, as a snapshot.** `GET .../snapshots` includes it (`openingBalance =
  true`); `PUT .../snapshots/{id}` on it is a 409, so the earlier-transactions guard cannot be
  bypassed. `PUT`/`DELETE .../opening-balance` replace or remove it under `If-Match`.
- **No new capability flag.** Whether its account's value stays unknown (a holding account) is
  read from the existing `holds_positions`, which defaults to true for `SECURITIES`,
  `MANAGED_MANDATE` and `CRYPTO` and may be set for e.g. a pension that holds funds.
- **Replaced in place and hard-deleted - an exception to FR-LIF-001.** The deletion matrix says a
  snapshot is never hard-deleted but superseded and retained. That rule protects what an
  institution reported. An opening balance is the member's own starting point, not an observation,
  so `PUT` overwrites it and `DELETE` removes the row, and no history of earlier values is kept
  (`updated_at`/`updated_by` show only the last change). Product-owner decision on the #241
  review, 2026-10-03; a change history comes with the financial audit log (FR-AUD-001, EPIC 31).
  Regular snapshots keep the FR-LIF-001 rule.
- **Card snapshots before V58.** V58 stops with `account_snapshot_card_currency`, naming them, if a
  credit card has a snapshot in its native currency while its billing currency differs (recorded
  under V33's rule). It cannot be converted without a rate and is not relabelled silently: delete
  it, or re-record it in the billing currency, then restart. `CardSnapshotCurrencyMigrationTest`.
- **Not recorded yet.** A read, replace or delete without one is a 404
  `OPENING_BALANCE_NOT_RECORDED`, only ever after the account access check; an account the caller
  cannot see stays a plain `NOT_FOUND`.


### Cash reconciliation (US-25-02)

The newest non-opening `account_snapshot` with a balance is the observed provider/member figure
against which the cash ledger is reconciled. The engine writes an account-level
`reconciliation_result` (`affected_security_id IS NULL`) only when the two figures differ; V60's
partial unique index `uq_reconciliation_result_snapshot_cash` guarantees one such result per
snapshot without constraining the later per-security reconciliation rows.

- **Derived side.** The same US-25-04 convention is used: opening balance plus live ledger rows with
  `opening_date < booking_date <= snapshot_date`; liabilities invert the cash-direction ledger
  sign. Without an opening balance at or before the snapshot, there is no comparison and the API
  reports `NOT_RECONCILABLE / NO_OPENING_BALANCE`.
- **State.** A non-zero difference is `OPEN`; exact agreement creates no row, or changes that
  snapshot's existing `OPEN` row to `RESOLVED`, keeping its `difference_amount` and
  `probable_cause` as history. Reconciling locks the snapshot row first, so two concurrent ledger
  writes on one account update one result instead of colliding on the unique index. A newer snapshot changes older open results to
  `SUPERSEDED`. Member decisions (US-25-03) cover one exact amount, see below.
- **Cash scope.** This story reconciles transaction-backed accounts that do not hold positions.
  Holdings accounts and ledger-less accounts remain `NOT_RECONCILABLE / CASH_SCOPE_NOT_APPLICABLE`
  until the investment reconciliation slice lands.
- **Isolation.** `reconciliation_result` already carries `workspace_id` and is under V20's
  ENABLE + FORCE RLS policy. Cross-tenant coverage explicitly exercises the table.

### Reconciliation decisions (US-25-03)

`POST /api/v1/accounts/{accountId}/reconciliations/{id}/accept|dismiss|reopen` need `EDIT` on the
account and the result's `version` in `If-Match` (V62). Only the result of the account's newest
snapshot can be decided on; a superseded one, or one not in the state the action applies to, is a
409 `RECONCILIATION_STALE` with the current `status` and `differenceAmount`.

A decision first re-evaluates the comparison, and a `RECONCILIATION_STALE` or
`RECONCILIATION_FINALIZED` 409 commits that re-evaluation (`noRollbackFor`), so the reload shows the
figure the 409 named. It is an ordinary engine run with the requesting member as its actor: when it
withdraws an adjusting row the ledger has overtaken, `deleted_by` names that member although their
request was answered with the 409. The engine would have withdrawn the row on the next write anyway;
V39 only requires an actor, and the member whose request revealed the change is the closest one.

- **Accept** (`note` required) inserts a `transaction` of type `VALUATION_ADJUSTMENT`, `source =
  'MANUAL'`, dated to the snapshot, for the missing ledger amount (asset: the difference;
  liability: its negation), and links it as `resolution_transaction_id`; status `ACCEPTED`. The
  member's note is the row's `notes`; it has no `merchant_description`, so no server-chosen
  language is stored and a client labels it from its type and `reconciliationAdjustment`. The
  row names its owner in `transaction.reconciliation_result_id` (V63), written on insert and frozen
  by the append-only trigger, so a withdrawn row still names it. That link, not the type, makes a
  row a reconciliation adjustment: a `VALUATION_ADJUSTMENT` that no result booked (a future
  investment valuation or import) is an ordinary row. The type is not accepted by `POST
  /transactions`, is not categorized, and is in no cash-flow figure. Correcting, removing, restoring or categorizing the row directly is a 409
  `RECONCILIATION_ADJUSTMENT_LOCKED` (its `removal` is `null`): only its result's reopen takes it
  back. The ledger shows where the row stands in `reconciliationAdjustment`: `REOPENABLE`,
  `FINALIZED` or `WITHDRAWN`; the 409 carries the same value.
- **Dismiss** (`note` required) sets `DISMISSED`; the account shows `DISMISSED_DIFFERENCE` with the
  amount and no `OPEN_RECONCILIATION_DIFFERENCE` warning.
- **Reopen** sets `OPEN` again (FR-STA-003). For an accepted result it soft-deletes the adjusting row
  (`deleted_by` = the member) and clears the link; the difference is then re-evaluated at once.
- **The engine keeps decisions honest.** An `ACCEPTED` result stays while the account agrees. Once a
  ledger or snapshot change makes it disagree, the engine soft-deletes the adjusting row
  (`deleted_by` = the member whose write caused it) and re-evaluates: booking the missing row
  afterwards therefore ends `RESOLVED`, any other change `OPEN` with the real difference. A
  `DISMISSED` result stays while the difference is the dismissed amount, becomes `RESOLVED` on
  agreement and `OPEN` on any other amount. `resolution_note` is kept on reopen, so the history
  still says why it had been decided.
- **A lost comparison basis retires decisions too.** When the newest snapshot can no longer be
  compared (its opening balance is deleted or moved past it, or the account leaves cash scope), a
  decided result on it becomes `SUPERSEDED` like an open one, and an accepted one's adjusting row is
  soft-deleted (`deleted_by` = the member whose write caused it). Once the basis is back, the
  difference shows as `OPEN` again for a new decision.
- **A newer snapshot finalizes older decisions**, like a closed period. That snapshot was compared
  against a ledger containing the decision (an accepted one's adjusting row included), so taking
  it back would rewrite a comparison that is already closed. An `ACCEPTED` or `DISMISSED` result
  whose snapshot is no longer the newest reports `finalized: true`; reopening or deciding on it
  again is a 409 `RECONCILIATION_FINALIZED`, and its adjusting row stays (`FINALIZED`). A
  correction goes into the newest comparison: if the missing row turns up later, the newest
  snapshot shows the overlap as an open difference, which the member accepts as a visible
  counter-adjustment. Finalization is derived, not stored, so it follows a snapshot whose date is
  edited later. If the opening balance is later moved past a finalized decision's snapshot, its
  adjusting row falls before the new starting point and is left out like any earlier row; the row
  carries `BOOKED_BEFORE_OPENING_BALANCE`, but the account does not raise
  `TRANSACTIONS_BEFORE_OPENING_BALANCE` for it: the new opening balance already contains that
  correction, and the locked row gives the member nothing to act on.

### Category taxonomy: shared defaults, workspace customisation (US-08-04)

`/api/v1/categories` manages a workspace's reporting taxonomy: the shipped defaults
(`workspace_id IS NULL`, `V19`) plus the workspace's own categories. Decisions are recorded on
issue #144.

- **Defaults are never edited by a workspace.** RLS keeps them out of a workspace's writes (`V65`;
  `V20` still let a `DELETE` through), and editing the shared row would change it for everyone.
  Relabelling or deactivating a default is stored per workspace in `workspace_category_override`
  (`V34`; a NULL column inherits the shipped value, and an override that overrides nothing is
  deleted). It is a user customisation a reference package must not overwrite (FR-REF-009).
  Defaults keep their shipped position; only workspace categories can be moved.
- **Codes.** Reports key on `code`, never on a label (FR-CAT-008). Workspace codes are generated
  from the English label, immutable, and carry a `WS_` prefix that defaults never use
  (`category_code_namespace`), so a future default cannot collide with a workspace code. `V34`
  also replaces V13's `UNIQUE (workspace_id, code)`, which let two defaults share a code (NULLs are
  distinct), with `uq_category_workspace_code ... NULLS NOT DISTINCT`.
- **Database label/default integrity.** `V46` mirrors the API's 100-character category-label
  limit at the database boundary for both `category` and `workspace_category_override`, rejects
  blank-after-trim labels, and enforces `(workspace_id IS NULL) = is_system_default`. This keeps
  reference-package/import/script writers aligned with the DTO contract and prevents the API's
  derived `systemDefault` flag from disagreeing with stored data. "Blank" covers tabs and line
  breaks as well as spaces, as `@NotBlank` does. Two first overrides of the same shipped default
  cannot race through `CategoryService`, which locks the workspace row before reading overrides;
  should a writer that bypasses that lock ever hit `uq_workspace_category_override`, the answer is
  a retryable 409 (`RETRY`) rather than a generic constraint conflict.
- **Service-layer rules** (`CategoryService`): at most 3 levels (a move checks the moved subtree's
  height) and no cycles; EN and DE labels unique among siblings, ignoring case; deactivation
  cascades to every subcategory, while reactivation affects one category and needs an active
  parent; `UNCATEGORIZED` and `TRANSFER_INTERNAL` can never be deactivated, deleted or given
  subcategories. A category counts as active only if every ancestor is active too, so a default a
  later reference package adds under a deactivated default is inactive as well. `requireAssignable`
  is the single check later stories (US-08-01/02) call before assigning a category: an inactive
  one is a 422. It has a batch form that loads the taxonomy once, for bulk categorization.
- **Concurrency.** Every change locks the workspace row (`SELECT ... FOR UPDATE`) before reading
  the tree, so two changes to one workspace's taxonomy run one after the other. The depth, cycle
  and sibling-label rules are checked in memory and no constraint backs them, so without the lock
  two concurrent moves could form a cycle. `PUT` also carries the `version` the client last read
  and is a 409 if the category changed since. For a default, that token tracks the workspace's
  override.
- **Deletion (FR-LIF-001).** A default is never hard-deleted (409, deactivate instead). A
  workspace category is deleted only if no transaction, split, rule, source mapping, budget line,
  categorization-log entry or subcategory refers to it; the foreign keys are the backstop for a
  reference the workspace cannot see.
- **Isolation.** `workspace_category_override` is RLS-protected like any tenant table. Its
  foreign keys cascade (`V35`), so an override goes with its workspace or with a retired default.
  `category_parent_scope_guard` stops a category being placed under another workspace's category,
  which the foreign key alone would allow, since FK checks bypass RLS.
- **Access.** Any workspace member may read; changes need EDIT on the workspace, because the
  taxonomy shapes every member's reports. Each response carries `canEdit`, so a client can hide
  the actions that would be refused.

### Automatic categorization (US-08-01)

`CategorizationService` assigns a category to every new cash or card row (`INCOME`, `EXPENSE`,
`DEPOSIT`, `WITHDRAWAL`, `INTEREST`, `FEE`, `TAX`, `REFUND`, `CREDIT_CARD_PURCHASE`) in the same
transaction that records it. `SETTLEMENT` and investment types get no category. Decisions are on
issue #145.

- **Layers, first assignable hit wins.** The layers run in this order:
  1. The workspace's active `categorization_rule`s, lowest `priority` first. `MERCHANT` is a
     case-insensitive "contains" on the merchant description; `SOURCE_CODE` is an exact code such
     as `MCC:5812`.
  2. The shipped `category_source_mapping`, trying ISO 20022 purpose, then bank transaction code,
     then MCC.
  3. `TRANSACTION_TYPE`: the category a row's own type implies, `FEE` to `FEES` and `TAX` to
     `TAXES`. This is where a card's foreign-transaction fee row lands, since it has no merchant
     or code of its own.
  4. `FALLBACK_MATCH`: the most pg_trgm-similar earlier row of the workspace whose category a
     rule or a user assigned, at or above `app.categorization.fuzzy-similarity-threshold`. It must
     share the merchant's brand word, because a shared city name alone is as similar as a shared
     brand. The query filters with pg_trgm's `%` operator, which V10's GIN trigram index serves
     (`similarity() >= t` alone scans the workspace's whole history). The threshold for `%` is set
     with `set_config(..., true)`, for the current transaction only, so it never outlives the
     request on a pooled connection.
  5. `UNCATEGORIZED`.

  A candidate whose category is inactive for the workspace (itself or an ancestor) is skipped.
  `categorizeAll` categorizes many rows (an import) against one load of each workspace's
  taxonomy, rules and shipped defaults, and resolves each distinct source code once.
- **Source codes** are read from `raw_source_data`: `mcc`, `purposeCode` and `bankTransactionCode`
  (`DOMAIN-FAMILY-SUBFAMILY`), the keys a camt import must write.
- **Provenance.** Every assignment writes a `transaction_categorization_log` row (`rule_id` for a
  rule, `confidence` for a fuzzy match). `UNCATEGORIZED` gets none, because nothing assigned it.
  Responses carry `categoryId` and `categoryAssignedBy`.
  `GET /accounts/{id}/transactions?uncategorized=true` is the actionable list (FR-CAT-013).
- **Rules** (`/api/v1/categorization-rules`): create, list and deactivate. Reading needs
  membership; changes need EDIT on the workspace. A rule is never edited, so its log rows keep
  their meaning. A `MERCHANT` value needs at least three characters, since a shorter "contains"
  would match almost every merchant. `COUNTERPARTY_IBAN` and `AMOUNT_PATTERN` are rejected until
  imports supply that data.
- **User override (US-08-02, RULE-031).** `PUT /accounts/{id}/transactions/{txId}/category` sets
  a member's own category, logged as `USER` with `is_user_override`. `DELETE` on the same path
  resets it to automatic and re-categorizes at once. Both need EDIT on the account. Any type may be
  overridden, to any assignable category except `UNCATEGORIZED`; voided rows cannot be changed.
  Every automatic path, including the internal `CategorizationService.recategorizeWorkspace`,
  skips an overridden row. The log row names the member who made the override
  (`assigned_by_user_id`, `V38`; required when `is_user_override`, `NULL` for automatic rows).
- **Override against a concurrent re-run.** The override and reset lock their row
  (`SELECT ... FOR UPDATE`) before checking it, and the re-run locks each page of rows before it
  checks them for overrides. Whichever comes second sees what the first committed, so a re-run can
  never write over an override made between its check and its write.
- **Re-run.** `recategorizeWorkspace` walks the workspace in keyset pages of 500 rows by
  `(created_at, id)`, clearing the persistence context after each page, so neither memory nor the
  override check's `IN` list grows with the workspace. A single unbounded list fails above 65,535
  rows. It writes a row only when its assignment changes: another category, or the same category
  reached another way (e.g. a rule instead of a fuzzy guess, or another rule). An unchanged
  decision writes nothing, so repeated runs don't grow the log.
- **Reading the log.** A transaction's latest log row describes its category only while the two
  still match: a reset that lands in `UNCATEGORIZED`, or a type the engine doesn't categorize,
  writes no row of its own. Responses, the override guard and the fuzzy matcher's learning query
  all apply that rule.
- **`V37`** adds the defaults `LEISURE > DINING`, `LEISURE > TRAVEL`, `HOUSING > UTILITIES`,
  `HEALTH`, `SHOPPING`, `TAXES` and `FEES`, and seeds 43 MCC and purpose-code mappings
  (FR-CAT-010: configuration, extendable by a reference package). It adds `TRANSACTION_TYPE` to
  the log's provenance values. It also backfills existing cash and card rows: the mapped MCC where
  assignable, else the type's category for a `FEE` or `TAX`, else `UNCATEGORIZED`. The backfill sets
  `row_security = off`, so a migration role that cannot bypass RLS fails loudly instead of
  updating nothing.

### Internal transfers and cash flow (US-10-01)

A transfer between two of a workspace's own accounts is never income or spending (FR-CF-001/002,
DM-05). Decisions are on issue #147.

- **Recorded as a pair.** A `TRANSFER` or `PENSION_CONTRIBUTION` with a `counterpartyAccountId`
  writes both legs at once, each in its own account's currency, both flagged `is_internal_transfer`
  and pointing at each other's account. The incoming leg links to the outgoing one through
  `related_transaction_id`. Removing or restoring either leg takes the other along (US-07-02), and
  either way the member needs EDIT on both accounts.
- **Matched when recorded separately.** `TransferDetectionService` pairs a negative
  TRANSFER/WITHDRAWAL/EXPENSE with a positive TRANSFER/DEPOSIT/INCOME on another own account: same
  currency, exact amount, at most 5 days apart. Card accounts are excluded, since cards settle
  through US-09-02. Matches reuse `settlement_match` with `match_kind = 'TRANSFER'` (`V41`): the
  debit is the "payment" leg, the credit the "card" leg, and the credit's account the "card"
  account. So confirm, reject and dissolve work as for a card settlement. Only an unambiguous
  TRANSFER↔TRANSFER pair is applied automatically; every other pair is only proposed, and a
  rejected pair is never proposed again. Confirming any match, by a member or by the system,
  rejects every other proposal of either kind that shares one of its legs. A card settlement
  and a transfer can both be proposed for the same WITHDRAWAL; card matching wins an unambiguous
  pair. A run around a date decides only pairs with a leg within 5 days of it, and loads
  candidates 15 days either side so each is judged with all competitors in view. Each run holds
  a transaction-scoped advisory lock per workspace (`pg_advisory_xact_lock`), not the workspace
  row. `V42` indexes `transaction (workspace_id, booking_date)` for that scan. The API also
  returns each match's legs as `debit*`/`credit*` fields, which fit both kinds, and
  `GET /settlement-matches?kind=` lists one kind only.
- **Cross-currency legs (US-10-06, #181).** Legs in different currencies pair when they agree
  within `app.fx.transfer-match-tolerance` (default 2%) once converted. The tolerance only
  recognises the pair; nothing is charged or recorded as a margin. The rate is the most specific
  one available: a leg's own imported rate where it converts between the two currencies (the
  debit's first), else the stored daily rate on the debit's booking date - the latest on or
  before it, direct in either direction (compared the other way round, never divided) before a
  chain via USD. No rate at all means no pair. Such a pair is always proposed, never applied by
  the system. Confirming it stores the rate its amounts imply, credit / debit at ten places, in
  `settlement_match.transfer_fx_rate` (`V51`): on the match, because a leg's
  `fx_rate_to_account_currency` converts into its own account and is frozen by the append-only
  trigger. The realised FX difference is not reported. Fetching missing rates from a provider is
  a separate story.
- **One-sided legs.** An unlinked TRANSFER leg is pending review. `POST
  …/transactions/{id}/untracked-transfer` confirms it as money moved to or from an untracked own
  account (`is_internal_transfer` with no counterparty); a counterpart recorded later still pairs
  with it. If that match is later undone, the leg is pending review again: the confirmation is
  not remembered apart from the flag.
- **Cash flow** (`GET /api/v1/cash-flow`, per month and currency; the four figures never
  overlap):
  - **income:** INCOME, INTEREST, DIVIDEND
  - **spending:** purchases, withdrawals, expenses, fees, tax
  - **saving:** internal-transfer credits into an account whose `counts_as_saving` is true from
    one whose flag is false, less the reverse. `counts_as_saving` is a declared capability, on by
    default for savings, depot, mandate, crypto, pension and vested-benefits accounts, and
    overridable. The current flag applies to every month, so changing it restates history.
  - **pendingReview:** unresolved settlements, unlinked transfer legs, and proposed pairs counted
    once. A pair's credit leg is counted when its debit is not in the same figure (another month,
    or an account the caller cannot read), so an income leg held back from income is never
    dropped.

  Voided pairs and soft-deleted rows are in none of them. Savings rate is US-10-05.

### Reference-data packages (US-01-04)

`reference_package` records which version of the shipped or imported reference data is loaded;
exactly one row is current (`uq_reference_package_current`). `GET /api/v1/admin/reference-data`
shows it, for `SYSTEM_ADMINISTRATOR` only.

- **Baselines.** V19 recorded `1.0.0-baseline`. V43 adds `1.1.0-baseline` (V37's extra default
  categories and source-code mappings) as current, and keeps 1.0.0 as history. Publication dates
  are release dates, not install dates.
- **How the two version fields relate, as built today:**
  - A package's `content_manifest` lists everything loaded while it is current. The 1.1.0
    manifest covers the catalogue, categories, mappings, fallback taxonomy and GICS, so a
    package reads as a **cumulative snapshot**.
  - Each row's `reference_package_version` names the package that **introduced** it. The
    catalogue and GICS rows stay `1.0.0-baseline`; only the mappings are `1.1.0-baseline`.
  - Nothing filters reference data by the current package. Every loaded row is used, whichever
    package is current.
- **Open for EPIC 32 (package import).** FR-REF-008 describes rollback as flipping `is_current`
  back. Under the model above, that changes the version shown but not the data in use: rolling
  back to 1.0.0 would leave the 1.1.0 mappings active. Before import or rollback is built, decide
  either to remove or deactivate rows introduced after the target package on rollback, or to
  filter reads by the current package.
- **Staleness warnings** (FR-REF-011) cover effective-dated values missing for the current
  period, never the baseline's age. Each is `{code, subject, missingPeriod}`, so a client can
  translate it. The list is empty until effective-dated reference data exists.

### Removing a transaction (US-07-02)

The ledger stays append-only (RULE-024): removing a row never changes a financial field, and
`trg_transaction_append_only` stays the backstop. The system picks the removal from the row's
provenance (FR-LIF-002b), and every response shows it as `removal`. Decisions are on issue #143.

- **T1, manual row: soft delete.** `deleted_at`/`deleted_by` (`V39`) hide the row. The entity's
  `@SQLRestriction("deleted_at IS NULL")` keeps it out of every JPA query (lists, balances, cash
  flow, matching). Native queries filter `deleted_at` themselves; only the restore paths read
  deleted rows on purpose. A soft-deleted row is restorable for 30 days
  (`POST …/transactions/{id}/restore`, listed at `GET …/transactions/deleted`), then only no longer
  restorable. It is **never purged**: FR-LIF-001 forbids a hard delete of a transaction, which
  closes OPEN-032. Its idempotency key stays taken.
- **T2, imported row: void.** The original gets `voided_at`/`voided_by`/`void_reason`, with the
  reason required (`V39`). A reversing row of the same type is added, with every amount and the
  quantity negated, dated to the void (or to the original's date if that is later), and linked by
  `replaces_transaction_id` (at most one per original, `uq_transaction_reversal`). Balances sum
  both rows. Cash-flow, category, matching and re-categorization queries leave out both the voided
  original and its reversal (`replaces_transaction_id IS NULL`). The reversal gets no category and
  can never be removed on its own.
- **Linked rows (FR-LIF-007).** A card purchase's FEE row (`related_transaction_id`) is removed
  and restored with it. A settlement match that includes the removed row is dissolved: a
  confirmed match's flags on the other leg are cleared. Only open matches (proposed, confirmed)
  are removed: a rejected match is a member's decision and outlives both a void and a soft delete,
  so a restore cannot re-propose or auto-confirm the rejected pair. While a leg is soft-deleted,
  match queries leave the match out (`SettlementMatchRepository.HIDDEN_LEG_EXCLUDED`), since
  Hibernate cannot load a leg its `@SQLRestriction` hides, and the match is not actionable (404).
- **Concurrency.** Removal and restore first lock every card whose matching the row can take part
  in (the account itself if it is a card, else every card using it as settlement source, plus the
  cards of existing matches), in id order, before the row. A detection run holding a card's lock
  therefore finishes before the row is hidden or voided, and never links a match to it.
- **Signs on a reversal.** `V40` exempts a reversing row from V36's `tax_withheld_amount >= 0`, as
  V36's sign rules already did, so a dividend with withholding tax can be voided; `net = gross -
  tax` still holds. The unresolved-settlement figure leaves out reversals too: voiding a card-side
  `SETTLEMENT` credit adds a negative `SETTLEMENT` row that is not a payment awaiting review.
- **Correction (US-07-06 / FR-LIF-004).** `PUT …/transactions/{id}` compares the requested
  immutable financial state with the locked row. A merchant-description/notes-only edit stays on
  the row; a financial change applies the same T1/T2 removal above and inserts a replacement in the
  same transaction. `V49.corrects_transaction_id` points from that replacement to the row it
  corrects and is distinct from `replaces_transaction_id`, which only means "reversal of a void".
  Both lineage columns are frozen by the append-only trigger. The replacement preserves source
  provenance but not `external_id`; the source row keeps that idempotency identity. A current USER
  category override is copied to the replacement after normal categorization, while the
  replacement's type is categorized and the category is still assignable; otherwise the
  replacement keeps whatever category it got like any new row.
  - The request body is the desired state: an omitted FX rate means "derive it" (a row with an
    explicit rate is then corrected), an omitted fee or counterparty means "none". An estimated
    rate sent back unchanged (a client echoing the row it read) counts as omitted, so it never
    turns a description edit into a correction and a replacement estimates its rate anew. The
    flip side: a correction cannot confirm an estimate as a disclosed rate at the same value
    (`fx_rate_estimated` stays true); only a different rate or a `billedAmount` replaces it. Only an
    omitted `mcc` keeps the original's; a different MCC is source data and corrected by
    replacement, set in the original's `raw_source_data`.
  - A description-only edit re-runs automatic categorization (rules match on that text); a member's
    override stays. The category itself is not part of a correction: `PUT/DELETE …/category`.
  - A corrected row cannot be restored (409), nor its fee row or transfer leg on its own: next to
    its replacement it would count twice. The replacement is the entry to restore or correct.
  - A row that only exists as part of another one (its `related_transaction_id` is set: the
    incoming leg of a two-sided transfer, or a card purchase's FEE row) is never corrected on
    its own (409, naming the row to correct instead, #216). Removing it removes its whole group,
    which a request for this one row could not re-create. Its description and notes stay
    editable. A restored copy of such a row (US-07-07) is linked the same way and follows the
    same rule.
- **Restoring a void (US-07-07, FR-LIF-006).** `POST …/transactions/{id}/restore` also restores a
  void within 30 days of it. The void stays untouched: the original keeps `voided_at`/`void_reason`
  and its reversal stays. An ordinary copy of the original is inserted (same account, type, date,
  amounts, provenance and `raw_source_data`; no `external_id`, which stays with the original),
  linked by `restores_transaction_id` (`V50`, at most one per voided row, frozen by the append-only
  trigger). The ledger reads A, -A, A', so the balance includes the original again from the copy
  on and history still shows the void. Decisions are on issue #179 / PR #218.
  - The copy is a normal row: no query special-cases "voided but restored". It is categorized
    like a new row (a member's override carries over while assignable), matched again by
    detection, and can be corrected, voided and restored again any number of times.
  - A FEE row or incoming transfer leg voided with its purchase or outgoing leg is restored
    through that head row, from whichever row the member restores: the group comes back whole and
    the copies link to each other. A row voided on its own (e.g. a waived fee) is restored alone,
    linked to its parent's current copy. A two-sided transfer needs EDIT on both accounts, and
    both accounts' cards are locked before any row.
  - A rejected settlement match on a voided row is carried to its copy, so detection never
    re-proposes a pair the member rejected (as for a soft delete).
  - A void older than 30 days, already restored, or made by a correction (`corrects_transaction_id`)
    is not restorable (409). `GET …/transactions/deleted` lists every restorable row of both tiers.
- **Not yet:** T3 (a reconciled row reopens its reconciliation) arrives with US-25-02.

### Workspace display currency (US-06-05)

`workspace.currency CHAR(3) NOT NULL DEFAULT 'CHF'` is the default currency for workspace-level consolidated
figures such as net worth. Existing workspaces are backfilled from the oldest login-capable
member's `app_user.reporting_currency`, falling back to CHF when no login exists. The column
keeps its `DEFAULT 'CHF'` on purpose: setup always sets the currency (the administrator's), so
the default only serves direct SQL inserts such as test fixtures. The per-user
`reporting_currency` remains a personal client preference and is not used by the server as the
workspace-total default.

Container-level figures default independently to
`financial_institution.container_currency`, while an account balance defaults to the account's
own native (or credit-card billing) currency. Read endpoints may request another ISO 4217
`currency` for presentation; this never rewrites the underlying account, transaction or
valuation data.

### FX import interval (US-06-07)

`fx_import_setting` (`V61`) is global, not tenant data: one row, enforced by a `singleton BOOLEAN`
column that is always `TRUE` and unique. `V61` inserts that row, so the setting has a `version`
for `If-Match` (ADR 0004) before anyone changes it. `import_interval_hours` is `NULL` until a
`SYSTEM_ADMINISTRATOR` sets it through `PUT /api/v1/admin/fx-import` to 1, 2, 6, 12 or 24
(`CHECK`); until then the deployment's `FX_IMPORT_CRON` applies.

A stored interval wins over `FX_IMPORT_CRON` at every start: the scheduled-import trigger bean
builds its cron from the row, and `spring.quartz.overwrite-existing-jobs` replaces the stored
trigger with it. Otherwise the next redeployment would silently undo the administrator's choice.
An interval runs clock-aligned in `FX_IMPORT_CRON_ZONE` (`0 0 0/6 * * ?` for 6 hours, midnight
for 24). Across the autumn DST fall-back, elapsed time to the next local clock boundary can be one
hour longer; after a runtime change the service adds a one-shot deadline import only in that case,
so the response's next run is still no later than the selected interval. A change updates the row,
writes `admin_audit_log` (`FX_IMPORT_INTERVAL_CHANGED`, from and to) and reschedules the stored
Quartz trigger in one transaction. The JDBC job store joins Spring's transaction on the
shared data source, so all three take effect together or not at all, on every node.

### Import templates (US-07-03)

`import_template` (`V15`, `V64`) describes one institution's CSV export as data, never code
(FR-IMP-020). It has `category`'s per-command RLS policies (`V65`, see section 4): a row with a
`NULL` `workspace_id` is a shipped template every workspace reads and none writes; the others
belong to one workspace. The application connects as a role that may bypass RLS in some
deployments, so `ImportTemplateRepository` filters on the workspace as well.

**Versioning (FR-IMP-023).** Every version of a template is its own row. The rows of one template
share `template_family_id`, and exactly one of them has `is_current` (partial unique index
`uq_import_template_family_current`). A change to any parse-relevant column (everything but
`name` and `institution_catalogue_id`, plus `header_columns`) inserts a new row with
`template_version` + 1 and `effective_from` = today, after setting `is_current = false` on the old
one in the same transaction. The old row is never changed again (JPA maps those columns
`updatable = false`), so an `import_batch` that points at it through `template_id` and
`template_version_used` can always be re-parsed exactly as it was. A name or institution change
updates the current row in place. `version` is the `If-Match` revision of ADR 0004; a write to a
retired version is a 412 naming the current one.

**Fingerprint (FR-IMP-022).** `header_columns` keeps the header cells of the sample the template
was built from (a JSON array; `NULL` for a file without a header row, `header_row_index = -1`).
`header_fingerprint` is derived from it on the server: SHA-256 (hex) of the cells, each trimmed
and lower-cased, joined by the ASCII unit separator (U+001F). Detection reads an uploaded file with
each active template's own encoding, delimiter and skipped rows, and ranks an exact fingerprint
before a header that merely holds every mapped column.

**Delete and deactivate (FR-LIF-001).** A template is hard-deleted with all its versions only
while no `import_batch` references any of them; otherwise it is deactivated (`is_active =
false`), which hides it from detection and from the default list.

`column_mapping` maps canonical fields (`bookingDate`, `amount` or `debitAmount` + `creditAmount`,
`currency`, `description`, ...) to a header cell's text or a 0-based column index;
`type_mapping` maps source type texts to cash `transaction_type`s. Sprint 5 imports only
`CASH_TRANSACTIONS` with `account_identification_strategy = USER_SELECTED`; the parser refuses
the other values with `IMPORT_TEMPLATE_UNSUPPORTED`.

**PDF statements (V66, #268).** `file_format` says how the file is read: `CSV`, `PDF_TEXT` (the
PDF's text layer, PDFBox) or `PDF_OCR` (a scanned PDF, rendered and read by a local Tesseract
process; nothing leaves the server). OCR is switched off unless `app.import.ocr.enabled`
(`IMPORT_OCR_ENABLED`) is set: a misread digit is still a valid amount, so it stays off until #276
adds a confidence threshold below which a row is an error; while off, an OCR read is a 503
`IMPORT_OCR_UNAVAILABLE` and no `PDF_OCR` template can be saved (a 422 on `fileFormat`). A PDF
template carries `pdf_layout`: column names (at most 100 characters each), a row
pattern whose capture groups are the cells, a record-start pattern that marks booking lines, and
a document marker the statement must contain. The text of each page is rebuilt line by line with
every word's position on the page (PDFBox, sorted by position; the line text is exactly what
PDFBox's text stripper writes, so a layout of these four fields reads as before). The optional
fields add to those four without changing them (#268), all of them read in one pass over the
lines:

- `headerLabels` (text layer only, a 422 for `PDF_OCR`): the labels of the booking table's header
  line. A line holding every label in order is a header line, never a booking, and sets the
  columns for the lines below it: each label spans its words on the page, and a word of a booking
  line is the cell of the label it overlaps most (the nearest when none), named by the label. So an
  unsigned amount under `BELASTUNG` is a debit through the existing `SEPARATE_DEBIT_CREDIT` rule,
  and a table that moves between pages is still read by its labels, never by coordinates. A word
  is placed by position alone: text that runs on past its column's label (a long description
  under `REFERENZ`) lands in the next column's cell, so a template maps a column such as the
  external id only where the bank keeps its text within the column. A header line that the
  record-start pattern finds too is the row error `IMPORT_ROW_LINE_AMBIGUOUS` (the line, and
  `headerLabels`): it still sets the columns, but a booking line holding every label would
  otherwise vanish without a trace.
- `continuationColumn`: lines after a booking line that are no booking are appended whole to that
  column's cell (a counterparty, an IBAN). When it is a header label, only lines that start in its
  column: a remark at the margin ends the booking. A header line ends the booking too, except the
  table header the next page repeats at its top: a booking at the foot of a page continues below
  it, its own cells read by its page's columns and its continuation lines placed by the next
  page's. A second table header on that page ends it. Text layer only (a 422 for `PDF_OCR`): OCR
  reads every page as one text, so it cannot tell a page footer from a continuation line. A
  booking with more than 20 continuation lines is the row error `IMPORT_ROW_CONTINUATION_TOO_LONG`
  rather than a row with the rest of the statement in its description.
- `continuationEndPattern` (needs `continuationColumn`): a matching line that is no page furniture
  ends the booking above it, and no line continues one until the next booking line - e.g. the
  closing text after a statement's last booking, which a continuation column that is no header
  label would otherwise append to it.
- `sectionPattern` and `sectionColumn`: a matching line starts a section, and its first capture
  group is the `sectionColumn` cell of the section's bookings (with currency mode `PER_ROW`, its
  currency). Text before the first section, such as a summary page, is ignored, except a table
  header line, so one header above all sections sets their columns. A line below such a table
  header that the record-start pattern finds and no balance line pattern does is the row error
  `IMPORT_ROW_LINE_BEFORE_SECTION` (the line): a section pattern that misses the first section's
  title would otherwise drop that section's bookings without a trace. A dated line of the summary
  above any table header stays no row; without header labels nothing tells it from a booking, so
  such a layout reports none. A marker of the section's own
  value at the top of a later page (no booking on that page before it) repeats the section's title
  there and does not start it again: the running balance goes on, and a booking at the foot of the
  page before keeps its continuation lines. A balance line between such a marker and the next
  booking only states the balance that booking starts from, as at a section's start, since the
  marker may also open a new section of the same value (a second account in that currency).
  Nothing else tells the two apart: a second section of the same value that opens at a page's top
  without such a balance line continues the running balance of the section before, so its first
  booking is the row error `IMPORT_ROW_BALANCE_MISMATCH` - reported, never imported unchecked. A
  marker below a booking on its page always starts a new section. A section's start
  that the record-start pattern finds too is also the row error `IMPORT_ROW_LINE_AMBIGUOUS` (the
  line, and `sectionPattern`): it starts the section, but a booking a too-broad section pattern
  takes would otherwise vanish, since a section starts with no balance to check it against.
- `balanceLinePattern` and `balanceColumn`: a matching line states a balance (its first capture
  group) and is never a booking. Each booking is checked against the balance before it (the one
  stated since the last booking, or the last booking's): stated balance = balance before + amount,
  compared exactly. A mismatch is the row error `IMPORT_ROW_BALANCE_MISMATCH`, which catches an
  amount or sign read from the wrong column; the next booking starts from the balance the statement
  prints, so one misread row does not fail the rows after it. A section starts with no balance.
  A balance line below a booking (before the next booking of its section, e.g. the section's
  closing balance) must state the balance the bookings lead to; otherwise the balance line is an
  error row of its own, `IMPORT_ROW_BALANCE_LINE_MISMATCH` with the line as its raw data, since a
  booking above it was not read as one - for instance a line the balance line pattern took for its
  own. The bookings around it keep their own status, so the ones read correctly still import. A
  balance that the template's amount rule does not read (e.g. a trailing minus, `1.234,56-`, or a
  debit suffix, `1.234,56 S`) states nothing, in the balance column as on a balance line: it checks
  nothing, the booking beside it keeps its own status (the balance column only checks the
  amounts), and the running balance goes on from the booking's amount, so the next balance that is
  an amount checks that booking too. Without `balanceColumn`, nothing
  checks a balance line, so one the record-start pattern finds too (e.g. a dated opening entry) is
  also the row error `IMPORT_ROW_LINE_AMBIGUOUS` (the line, and `balanceLinePattern`), never a
  silently skipped booking. Every PDF row counts against the file's row limit, a balance line's
  error row included; a PDF row's number is its place among the rows, not an index into the
  statement's booking lines. Carry-forward lines that state the balance at a page break (e.g.
  `Uebertrag`) belong in this pattern: their amount changes from page to page, so they are no page
  furniture, and a continuation column would otherwise append them to the page's last booking. As
  balance lines they continue no booking, and with `balanceColumn` the one below a booking is
  checked like a closing balance. A balance line pauses the booking above it: lines below it on
  that page no longer continue it, but a balance line at the top of a later page, before any
  booking there, carries it over, so a booking split across the page keeps the continuation lines
  below the carry-forward line on the next page. The amount representation applies to balances
  too: with `NEGATIVE_IN_PARENTHESES`, `(1,100.00)` is a negative balance.

Lines repeated in the top or bottom three lines of every page, or every page but one, are page
furniture and never continue a booking. In a text layer the line must also sit at the same place
on those pages, within 6 points of the same distance from the page's top or from its bottom edge:
a page header or footer is printed at a fixed place (a footer keeps its distance from the bottom
also on a portrait first page before landscape ones), while a counterparty that happens to end a
booking at the foot of each page moves with the bookings above it and stays in its booking. Only
page numbers set their digits aside - `Seite 2 von 3`, `Page 2/3`, `Blatt 2`, `S. 2` anywhere in
the line, or a line that is just `2/3` or `- 2 -`; dates, transaction references and account
identifiers must repeat exactly, so distinct references at the foot of each page remain in their
bookings. A word's text is the text the line holds for it, a ligature glyph resolved (`ﬁ` reads
`fi`), so a header label is found on the page exactly as detection finds it in the text. A line a
pattern marks as a booking, balance, section or header is never furniture, so no booking is ever
dropped as one. A row's raw data also keeps the whole booking line with its continuation lines,
under `#line`, a name no layout column or header label may take. All patterns are RE2 (linear-time matching, no
backreferences), so a member's pattern cannot backtrack catastrophically; RE2 is linear in its
compiled program too, so a pattern whose program would exceed 2,000 instructions
(`Re2Patterns`, e.g. nested counted repeats) is refused with a 422. From the cut columns on,
a PDF row goes through exactly the CSV rules; a booking line the row pattern misses is the row
error `IMPORT_ROW_LINE_UNMATCHED`. The layout's cell names (its columns, then its header labels,
then its section column) act as the stored header columns. A dry run reports the header
fingerprint of a PDF only when the statement holds the header line of its labels. A PDF template stores a
`header_fingerprint` only of its header labels, the one part of its names read from the file;
without labels, it stores none.
Detection differs from CSV: a PDF has no header row to read, so a `PDF_TEXT` template is a
candidate when the statement holds its marker and at least one booking line, and an exact header
match when it also holds the header line of the layout's labels (a layout without labels is never
one; a statement with the header line but no booking line is no candidate at all). The PDF is read once per
detection, however many PDF templates there are; an OCR template is never a candidate (OCR per
candidate is too slow). A PDF without a text layer is therefore a 422 `IMPORT_PDF_NO_TEXT` from
detection, not an empty list, so the member learns why no template fits. Limits: 20 pages, no encrypted PDF, no damaged PDF (it is parsed strictly,
never repaired), every stream decoded once into a counter the moment the parser meets it, before
PDFBox or anything else decodes it (16 MiB per stream, 64 MiB in all, images of at most 50 million
pixels: PDFBox decodes a whole stream into memory, already while loading the cross-reference and
object streams, and a few hundred kilobytes can inflate to gigabytes), at most 20,000 objects
declared by the cross-reference sections and object streams together (a few bytes declare one
object, and PDFBox keeps several in memory for each: a 24 KB file declaring 2.7 million exhausted
a 512 MB heap; the samples declare at most about 300), at most one million drawing
operations per read (forms drawing each other over and over make a tiny file run for hours; a real
statement page runs a few thousand) within 30 s for the text layer, 200,000 characters per read
(counted one by one as they are shown: a single text operator can show millions, and each is an
object until its page is read; the longest sample holds about 20,000), four PDF reads at a time (a further one waits 5 s, then is a 503 `IMPORT_PDF_BUSY` with
`Retry-After`), OCR pages of at most 1500 points a side, two OCR documents at a time, 30 s per page
and 120 s per document, rendering included (busy or too slow: a 503 with `Retry-After`). These
concurrency limits hold per backend process: each instance reads its own four PDFs and two OCR
documents at a time; an OCR read gives its read turn back once its pages are checked, so a
recognition (up to two minutes) holds only its OCR slot. A readable PDF over any of these limits
is a 422 `IMPORT_PDF_LIMIT_EXCEEDED` (a shorter export helps); a damaged or encrypted one is
`IMPORT_FILE_MALFORMED`. Detection and dry runs parse after
their read-only transaction has ended, so OCR never holds a pooled connection.

## 5. Time-series data and partitioning

`price`, `fx_rate` and `daily_valuation` are the volume-dominant tables (DB-02, NFR-TEC-003) and
are all `PARTITION BY RANGE` on their date column, with explicit yearly partitions for
2023–2027 and a `DEFAULT` catch-all partition so an out-of-range insert never fails outright.
`fx_rate` also has `fx_rate_pre_2023` (`V53`) for everything earlier: the FX import (#223) loads
history from the first booking on, and the ECB series reaches back to 1999.
**Operational note:** add a new year's partition (`CREATE TABLE price_y2028 PARTITION OF price
FOR VALUES FROM ('2028-01-01') TO ('2029-01-01');`, and the equivalent for `fx_rate` and
`daily_valuation`) via a normal numbered migration before each table's `DEFAULT` partition
accumulates a meaningful volume of rows — rows can be moved out of `DEFAULT` into a proper
partition later, but it is cheaper to stay ahead of it. A scheduled job (EPIC 30/31) should alert
when any of the three `_default` partitions is non-empty.

PostgreSQL requires every `PRIMARY KEY`/`UNIQUE` constraint on a partitioned table to include the
partition key column. `price` and `fx_rate` therefore use a composite `(id, price_date)` /
`(id, rate_date)` primary key rather than `id` alone; `daily_valuation`'s natural key
`(account_id, valuation_date)` already satisfies this. This is a partitioning artifact, not a
modelling decision — application code should still treat `id` as the row's identity where it is
usable (i.e. everywhere except the small number of write paths that need the composite key).

## 6. What is deliberately not fully implemented yet

This schema covers the MVP baseline (section 37 of the specification) as agreed for the epics
listed in `docs/user-stories/`. Explicitly deferred, consistent with the specification's own
Post-MVP list (section 38) and open decisions (section 42):

- **SNB classification** (`security.snb_institutional_sector_code`/`snb_securities_category_code`)
  is present as free-text columns pending **OPEN-007/D12** (exact SNB taxonomy TBD).
- **GICS/constituent look-through data licensing** (OPEN-006/OPEN-019) — the schema supports it
  (`gics_sub_industry_code`, `fallback_sector_taxonomy`) but no license is assumed.
- **Workspace splitting** (section 53) is not built, but `account_ownership`/`sharing_grant` are
  already dated relations rather than static columns specifically so it can be added later
  without rewriting history (FR-HHL-001..005).
- **User-facing data export** (OOS-008) is out of scope per the specification; the schema
  satisfies FR-DAT-008 (everything derived is rebuildable from source) so it costs nothing to add
  later if that decision reverses (OPEN-031).
- The `refresh_token`/`user_session` pair (V16) is consumed by the Spring Security configuration,
  the JWT filter chain and the password-hashing and token-rotation services (EPIC 02, EPIC 28);
  see `docs/user-stories/EPIC-02-user-administration-and-auth.md` and
  `EPIC-28-tenancy-auth-authorization.md` for the stories.
