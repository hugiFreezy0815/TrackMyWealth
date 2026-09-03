# EPIC 07 — Transaction Ledger & Imports

Covers `transaction`, `transaction_category_split` (V10) and the template-driven import framework
`import_template`/`import_batch`/`import_row_raw` (V15). Section 12, 28, 28.1. One story per
supported institution template is deliberately **out of this file** — see INPUT-002 and the note
at the bottom.

---

## US-07-01 — Manually record a transaction of any supported type

**Actor:** Household member
**Objective:** FR-DAT-01 (v0.1 doc numbering) / FR-IMP-001, FR-TRX-002.
**Story:** As a household member, I want to manually enter a transaction (income, expense,
transfer, buy, sell, dividend, interest, fee, tax, deposit, withdrawal, ...) against an account,
so that I have full data quality before, or instead of, any import.
**Preconditions:** An account exists.
**Acceptance criteria:**
- Given a `CASH` account, when a member enters an `EXPENSE` of CHF 45.00 on today's date, then a
  `transaction` row is created with `source = 'MANUAL'`, `booking_date = today`, and no
  `security_id`.
- Given a `SECURITIES` account, when a member enters a `BUY` of 10 shares at CHF 100 with a CHF
  5 fee, then `quantity`, `unit_price`, `fee_amount` and `amount` (net cash impact) are all
  recorded, and the transaction references the correct `security_id` (looked up or newly created
  per EPIC 12's lazy-instantiation rule).
- Given `trade_date` and `settlement_date` are both supplied, when the transaction is saved, then
  both are retained distinctly (FR-TRX-008) — performance-relevant logic (EPIC 16) reads
  `trade_date`; cash-balance logic reads `settlement_date`.
**Applicable business rules:** FR-TRX-001..008, DM-01/02/06.
**Data requirements:** `transaction_type`, `booking_date`, `amount`, `currency` required;
`security_id` required for investment-activity types (`BUY`/`SELL`/`DIVIDEND`/...).
**Error/edge cases:** Negative quantity on a `BUY` — rejected by validation. Currency mismatched
against the account's native currency without an FX rate supplied — the service layer must
require/derive an `fx_rate_to_account_currency` (DM-06).
**Authorization/privacy:** Household-scoped write.
**Dependencies:** EPIC 05, EPIC 12 (security lookup for investment transactions).
**Priority:** MUST.
**Definition of Done:** Integration test covers a cash expense and a security buy end to end.
**Data-quality behaviour:** N/A (this is the source-of-truth entry point).

---

## US-07-02 — Void a transaction and record a compensating entry (append-only)

**Actor:** Household member
**Objective:** RULE-024, FR-TRX-007, FR-LIF-002/002a/004 — the ledger is append-only; corrections
are new entries, and the permitted operation depends on provenance (T1 soft-delete / T2 void-only
/ T3 void-reopens-reconciliation).
**Story:** As a household member, I want to correct or remove a transaction in a way appropriate
to its origin, so that manually-entered typos are cheap to fix while imported/reconciled records
retain a full audit trail.
**Preconditions:** A transaction exists.
**Acceptance criteria:**
- Given a manually entered transaction that was never reconciled (Tier T1), when the user deletes
  it, then it is soft-deleted (hidden from views, recoverable for 30 days per FR-LIF-006) — no
  compensating entry is created.
- Given an imported transaction (Tier T2), when the user attempts to delete it, then the API
  instead performs a void: `voided_at`/`voided_by`/`void_reason` are set on the original row
  (unchanged financial fields, per the `trg_transaction_append_only` DB trigger), and a new
  reversing `transaction` row is inserted with the opposite sign, linked via
  `replaces_transaction_id`.
- Given a transaction that is part of a completed reconciliation (Tier T3), when it is voided,
  then the affected `reconciliation_result` row's `status` reverts to `OPEN` and is flagged for
  review (FR-STA-003).
- Given any attempt to directly `UPDATE` a financial field (`amount`, `quantity`, dates, etc.) on
  an existing transaction, when it reaches the database, then it is rejected by
  `trg_transaction_append_only` regardless of which tier the service layer thought it was
  applying — the DB trigger is the backstop, not the primary enforcement.
**Applicable business rules:** RULE-024, FR-TRX-007, FR-LIF-001/002/002a/002b/002c/003/004/006.
**Data requirements:** `void_reason` required on void.
**Error/edge cases:** Bulk removal of imported records must go through import-batch rollback
(FR-LIF-002c), never multi-select delete — the API must not expose a bulk-void endpoint for
imported transactions outside batch rollback.
**Authorization/privacy:** Household-scoped write; voiding requires the same access level as
editing.
**Dependencies:** US-07-01, EPIC 25 (reconciliation).
**Priority:** MUST.
**Definition of Done:** Three integration tests, one per tier, plus a negative test asserting the
DB trigger rejects a direct financial-field update regardless of tier.
**Data-quality behaviour:** A voided item shown in a historical view must display as voided, not
silently omitted (FR-LIF-003).

---

## US-07-03 — Define a reusable CSV import template for one institution's export format

**Actor:** Household member or administrator
**Objective:** FR-IMP-020/021 — support for one institution is a declarative template, not code.
**Story:** As a household member whose bank has no shipped template, I want to define a CSV
mapping (delimiter, encoding, decimal/thousands separators, date format, column mapping, type
mapping) once and save it for reuse, so that no institution is ever unsupported — only more or
less convenient (FR-IMP-024).
**Preconditions:** A CSV export file from the institution is available.
**Acceptance criteria:**
- Given a raw CSV sample, when the user configures delimiter=`;`, decimal separator=`,`, date
  format=`dd.MM.yyyy` and maps columns to `booking_date`/`amount`/`description`, then an
  `import_template` row is created with `household_id` set (a household-authored template, not a
  shipped one) and `column_mapping`/`type_mapping` populated as JSON.
- Given the template is saved, when a second file from the same institution is imported later,
  the user can select the saved template instead of re-configuring the mapping.
- Given `header_fingerprint` is recorded, when a new file is uploaded, then the system attempts
  automatic template selection by matching the file's header row against known fingerprints,
  while always allowing manual override (FR-IMP-022).
**Applicable business rules:** FR-IMP-003/009/020..025, FR-INS-010.
**Data requirements:** All fields in `import_template` (V15) capability set: delimiter, encoding,
decimal/thousands separators, date format, preamble/trailing row counts, amount representation,
currency mode, column mapping, type mapping, account identification strategy.
**Error/edge cases:** A template that no longer matches the institution's current export format
must fail with a clear diagnostic (US-07-05), not silently misalign columns.
**Authorization/privacy:** Household-scoped for user-defined templates; system-provided templates
(`household_id IS NULL`) are read-only to households (visible via the `category`/`import_template`
shared-or-own RLS policy in V20).
**Dependencies:** US-07-01.
**Priority:** MUST.
**Definition of Done:** Integration test defines a template against a synthetic CSV fixture and
successfully re-uses it for a second file.
**Data-quality behaviour:** N/A.

---

## US-07-04 — Import preview with deduplication before commit

**Actor:** Household member
**Objective:** FR-IMP-004, FR-DAT-004 — nothing is written without explicit confirmation.
**Story:** As a household member, I want to preview exactly what an import will do — new records,
detected duplicates, rows with errors — before anything is committed to the ledger, so that I
never accidentally double-import a file.
**Preconditions:** A file and a matching (or manually selected) `import_template`.
**Acceptance criteria:**
- Given a file is uploaded, when it is parsed against the template, then an `import_batch` row is
  created with `status = 'PARSED'` and one `import_row_raw` row per source row, each classified
  `PARSED`/`DUPLICATE`/`ERROR`.
- Given some rows match an existing transaction by `(account_id, source, external_id)` or by the
  documented fuzzy fallback (date/amount/currency/description), when the preview is shown, then
  those rows are flagged `DUPLICATE` and excluded from the default commit selection, with the
  user able to force-include one anyway.
- Given the user confirms commit, when it is processed, then only non-duplicate, non-error rows
  become `transaction` rows tagged with this `import_batch_id`, and `import_batch.status` becomes
  `COMMITTED`.
- Given the same file is uploaded a second time unmodified (V-18 in the golden dataset), when
  previewed, then every row is flagged `DUPLICATE` and the resulting commit is a no-op.
**Applicable business rules:** FR-IMP-004/005/012, DB-04, FR-TRX-004/009.
**Data requirements:** None beyond the template's own fields.
**Error/edge cases:** A file with some unparseable rows must still preview/import the valid rows
and report the rest (FR-IMP-012) — partial success, not all-or-nothing failure.
**Authorization/privacy:** Household-scoped write; the account being imported into must belong to
the caller's household.
**Dependencies:** US-07-03.
**Priority:** MUST.
**Definition of Done:** Integration test using the V-18/V-19/V-20/V-21 golden-dataset fixtures
(EPIC 27) — same file twice, two structurally different formats, separate debit/credit columns
with a trailing summary row, and a template-version change between two imports.
**Data-quality behaviour:** Rows classified `ERROR` must be visible and exportable for correction
(FR-IMP-012), never silently dropped.

---

## US-07-05 — Roll back an import batch

**Actor:** Household member
**Objective:** FR-IMP-005, FR-LIF-010/011 — an import is identifiable and reversible as a batch;
whether rollback hard-deletes or voids depends on whether any of its records has been modified
since commit.
**Story:** As a household member, I want to roll back an entire import batch if I imported the
wrong file or the wrong account, so that I can cleanly undo the mistake.
**Preconditions:** A `COMMITTED` `import_batch` exists.
**Acceptance criteria:**
- Given a committed batch none of whose transactions has since been edited, categorised, or
  referenced by a reconciliation resolution (`contains_modified_records = false`), when rollback
  is requested, then every transaction it created is hard-deleted and the batch's `status`
  becomes `ROLLED_BACK` (FR-LIF-010).
- Given a committed batch where at least one transaction has since been edited or is referenced by
  a reconciliation resolution, when rollback is requested, then every transaction it created is
  voided instead of deleted, the batch's `status` becomes `VOIDED`, and the response reports which
  records were retained (voided) and why (FR-LIF-011).
- Given rollback is requested, when it executes, then it is fully atomic — either every affected
  transaction is rolled back/voided, or none are (NFR-REL-001, FR-LIF-012).
**Applicable business rules:** FR-IMP-005, FR-LIF-010/011/012, FR-STA-002.
**Data requirements:** None beyond the batch reference.
**Error/edge cases:** Rollback of a batch that is itself mid-recomputation (a concurrent
background job touching the same transactions) must be serialised, not race (FR-CNC-005/007).
**Authorization/privacy:** Household-scoped write.
**Dependencies:** US-07-04.
**Priority:** MUST.
**Definition of Done:** Two integration tests — clean rollback (hard delete) and rollback after
one row was edited (void path) — plus an atomicity test that forces a failure partway through and
asserts no partial state.
**Data-quality behaviour:** N/A.

---

## Note on per-institution templates (INPUT-001..005)

Per section 28.1.1 of the specification, the actual list of CH/DE banks and brokers to support,
their sample exports and expected parse results is a **deferred delivery input**, supplied
alongside user-story creation rather than enumerated in the requirements document itself. Treat
each supported institution as its **own** story under this epic once that list is delivered
(INPUT-002), sized independently and never on the critical path of the import framework itself
(US-07-03/04/05 above must be built and tested against at least two structurally different
synthetic fixtures — one Swiss, one German — before any real institution-specific work begins,
per INPUT-003/FR-IMP-026). Each such story's Definition of Done includes an anonymised sample
file and its expected parse result as an automated test fixture (FR-IMP-026).
