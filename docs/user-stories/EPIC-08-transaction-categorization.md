# EPIC 08 — Transaction Categorization

Covers `category`, `category_source_mapping`, `categorization_rule`,
`transaction_categorization_log` (V13). Section 13, FR-CAT-*, DM-22/23.

---

## US-08-01 — Automatic categorization from source codes with fallback

**Actor:** System (categorization service invoked on import/creation)
**Objective:** FR-CAT-009/010/011, DM-22 — the three-layer model: source code → canonical
category → optional external mapping.
**Story:** As the system, I want to automatically assign a reporting category to an imported
transaction using its source code (MCC or ISO 20022 bank transaction code) where available, and
fall back to merchant-name/counterparty/amount-pattern matching where it is not, so that most
transactions never need manual categorization.
**Preconditions:** `category_source_mapping` seeded for common MCC/ISO 20022 codes.
**Acceptance criteria:**
- Given a card transaction with MCC `5411` (grocery stores) present in `raw_source_data`, when
  categorization runs, then the transaction's `category_id` is set to "Groceries" and a
  `transaction_categorization_log` row records `assigned_by = 'SOURCE_CODE'`.
- Given a bank transaction with no source code but a merchant description matching an active
  `categorization_rule`, when categorization runs, then the rule's category is applied and logged
  with `assigned_by = 'RULE'`.
- Given neither a source code nor a matching rule exists, when categorization runs, then the
  transaction is left in the "Uncategorized" category and is surfaced as an actionable item
  (FR-CAT-013) — never silently absorbed into a generic "Other" bucket.
**Applicable business rules:** FR-CAT-009..013, DM-22/23.
**Data requirements:** `category_source_mapping` seeded rows; well-formed `raw_source_data`.
**Error/edge cases:** A source code present but unmapped — falls through to the merchant-name
fallback, not to "Uncategorized" directly, since a mapping gap is common (FR-CAT-011: MCC is
"frequently absent, wrong, or reflects the acquirer rather than the merchant").
**Authorization/privacy:** Runs within the workspace-scoped import/creation transaction.
**Dependencies:** EPIC 07.
**Priority:** MUST.
**Definition of Done:** Table-driven test covering source-code match, rule match, and
uncategorized fallback.
**Data-quality behaviour:** Uncategorized transactions are queryable as a distinct, visible list
(FR-CAT-013), and the workspace's category-report totals show an explicit "Uncategorized" line
rather than omitting it.
**Clarified before development (issue #145, 2026-09-28):**
- **Order:** user rule, then shipped source-code mapping (purpose, then BTC, then MCC), then the
  category the type implies (`FEE` to `FEES`, `TAX` to `TAXES`), then fuzzy match
  (`FALLBACK_MATCH`), then Uncategorized. A category that isn't assignable is skipped.
- **Scope:** runs on new cash and card rows only, and existing rows are backfilled once.
- **Rules:** a minimal rule API (create, list, deactivate) with `MERCHANT` and `SOURCE_CODE` rules.
- **Taxonomy:** new defaults `DINING`, `TRAVEL`, `UTILITIES`, `HEALTH`, `SHOPPING`, `TAXES` and
  `FEES`, and about 40 seeded codes.
- **Uncategorized:** listed per account via `?uncategorized=true`. The workspace-wide list and the
  report totals line come with the reporting epic.

See `docs/architecture/database-schema.md`.

---

## US-08-02 — User override always wins and is never silently replaced

**Actor:** Workspace member
**Objective:** FR-CAT-003/006/014, RULE-031.
**Story:** As a workspace member, I want my manual category correction on a transaction to stick
permanently, so that a later automatic re-categorization run (e.g. after a rule change) never
quietly reverts my choice.
**Preconditions:** A transaction has been automatically categorized.
**Acceptance criteria:**
- Given a transaction currently categorized "Groceries" by a source-code match, when the user
  changes it to "Workspace," then `category_id` updates, a `transaction_categorization_log` row
  is written with `assigned_by = 'USER'` and `is_user_override = true`.
- Given the user-overridden transaction, when the bulk re-categorization job runs (e.g. after a
  new `categorization_rule` is added), then the transaction is skipped — its category is
  unchanged — because the service layer checks for the most recent `is_user_override = true` log
  entry before applying any automatic result.
**Applicable business rules:** FR-CAT-003/006/014, RULE-031.
**Data requirements:** None beyond the log table.
**Error/edge cases:** A user "undoes" their own override back to automatic — this should be an
explicit action (e.g. "reset to automatic"), not achievable by accident.
**Authorization/privacy:** Workspace-scoped write.
**Dependencies:** US-08-01.
**Priority:** MUST.
**Definition of Done:** Integration test overrides a category, re-runs the automatic job, and
asserts the override persists.
**Data-quality behaviour:** N/A.
**Clarified before development (issue #146, 2026-09-29):**
- **API:** `PUT`/`DELETE /api/v1/accounts/{id}/transactions/{txId}/category`, with EDIT on the
  account.
- **Targets:** any transaction type, any assignable category except UNCATEGORIZED.
- **Reset:** re-categorizes at once.
- **Guard:** on every automatic path, with an internal re-run for the DoD. The user-facing re-run
  with preview stays US-08-03.

See `docs/architecture/database-schema.md`.

---

## US-08-03 — Retroactive rule application with preview

**Actor:** Workspace member
**Objective:** FR-CAT-007/012, FR-CSH-02.
**Story:** As a workspace member, I want a newly created categorization rule to optionally apply
to my existing historical transactions, with a preview of exactly what would change before I
confirm, so that I can fix a whole category of past mis-categorizations in one action without
surprises.
**Preconditions:** A `categorization_rule` exists (e.g. "Migros" → "Groceries").
**Acceptance criteria:**
- Given the rule, when the user requests a retroactive-application preview, then the system
  returns the count and a sample of transactions that would change, without writing anything.
- Given the user confirms, when the retroactive application runs, then it respects US-08-02 (never
  overwrites a transaction with `is_user_override = true`), and every changed transaction gets a
  new `transaction_categorization_log` row with `assigned_by = 'RULE'`.
**Applicable business rules:** FR-CAT-007/012.
**Data requirements:** None beyond the rule and matching transactions.
**Error/edge cases:** A rule that would match an extremely large number of transactions — the
preview/apply operation should run as an asynchronous job (FR-JOB-001/008) rather than block the
request, consistent with EPIC 30/31's background-processing pattern.
**Authorization/privacy:** Workspace-scoped write.
**Dependencies:** US-08-01, US-08-02.
**Priority:** MUST.
**Definition of Done:** Integration test creates a rule, previews, applies, and confirms
user-overridden transactions are skipped.
**Data-quality behaviour:** N/A.
**Notes from the US-08-02 review (PR #177, 2026-09-29):**
- **Build on `CategorizationService.recategorizeWorkspace`.** It already pages through a workspace
  (500 rows, keyset on `created_at, id`), locks each page before checking it for overrides, and
  writes only rows whose assignment changes.
- **Commit per page for large workspaces.** Today the whole run is one transaction, so every
  page's rows stay locked until it commits, and a member's override or reset of one of those rows
  waits for the run. For the user-facing apply, and the asynchronous job the edge cases above ask
  for, run each page in its own transaction and resume from the last `(created_at, id)`. The
  override guard still holds, because each page locks its rows before it checks them.
- **The preview must not lock or write.** Compute the decisions read-only, without the page lock,
  and apply only after the user confirms. Then re-check each row under its lock, because the
  preview can be stale by then.
- **Clarify the acceptance criterion.** The engine records whichever layer now decides a row,
  which can be `SOURCE_CODE` or `TRANSACTION_TYPE` as well as `RULE`. The criterion above says
  every changed row is logged as `RULE`. Decide whether the apply only touches rows the new rule
  matches, or re-runs all layers and reports the other changes too.

---

## US-08-04 — Custom, hierarchical, user-extensible category taxonomy

**Actor:** Workspace member
**Objective:** FR-CAT-001..003, section 13.
**Story:** As a workspace member, I want to create, rename, reorganize and deactivate my own
categories (up to Category > Subcategory), keeping the shipped defaults available but editable,
so that the taxonomy fits how my workspace actually thinks about spending.
**Preconditions:** Shipped default categories exist (`V19` seed).
**Acceptance criteria:**
- Given the shipped default "Leisure" category, when a workspace adds a subcategory "Streaming
  Subscriptions" under it, then a new `category` row is created with `workspace_id` set and
  `parent_category_id` pointing at the default "Leisure" row.
- Given a workspace attempts to hard-delete a default category that has existing mappings/rules
  depending on it, when the request is made, then it is rejected in favour of deactivation
  (`is_active = false`) — historical assignments are preserved (FR-LIF-001: "deactivate,
  historical assignments preserved. Only if never used" for hard delete).
- Given a category is renamed (EN/DE labels changed), when historical reports are viewed, then
  they are unaffected because classification is keyed to the category's stable `code`, not its
  label (FR-CAT-008).
**Applicable business rules:** FR-CAT-001..003/008/014, section 13.
**Data requirements:** `code` unique per workspace (or NULL-workspace default), EN/DE labels
required.
**Error/edge cases:** Depth beyond Category > Subcategory (3 levels total per FR-CAT-001, "at
least Category > Subcategory") — service-layer validation, not currently a DB constraint (the
`category` table's `parent_category_id` self-reference has no enforced depth limit; document this
as a validation the service layer owns).
**Authorization/privacy:** Workspace-scoped write for workspace-owned categories; default
categories (`workspace_id IS NULL`) are read-only to workspaces, writable only via a reference
package import (EPIC 32).
**Dependencies:** US-08-01.
**Priority:** MUST.
**Definition of Done:** Integration test creates a custom subcategory, attempts to hard-delete a
used default category (rejected), and deactivates it instead (succeeds).
**Data-quality behaviour:** N/A.
**Clarified before development (issue #144, 2026-09-28):** a workspace customises a shared default
through a per-workspace override (V34 `workspace_category_override`), never by editing the shared
row; max 3 levels; codes are server-generated with a `WS_` prefix; deactivation cascades;
`UNCATEGORIZED` and `TRANSFER_INTERNAL` are protected; only workspace categories can be moved; new
assignments to an inactive category are rejected. See `docs/architecture/database-schema.md`.
