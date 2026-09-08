# Backlog — EPIC 20-24, 29-32

These epics are real MVP-or-near-MVP scope per specification section 37/39, but sequence after
the epics with full stories (`EPIC-01` through `EPIC-28` in this directory) because they either
depend heavily on those epics' outputs, or are cross-cutting concerns best refined once the core
domain model is stable. Each entry below gives the specification anchor, a sizing note, and a
starter list of story titles — decompose each into the full section-40 template
(`docs/user-stories/README.md`) during the sprint that picks it up.

---

## EPIC 20 — Financial Goals

**Anchor:** `goal` table (V14), section 4.1 (`Goal` entity), FR-PEN-007/008 (retirement/FIRE
projections reference goals conceptually).
**Sizing:** Small-medium. Mostly CRUD over `goal` plus progress computation against net worth
(EPIC 11) and pension contribution tracking (EPIC 26).
**Starter stories:**
- Create/edit/archive a goal (savings target, house deposit, FIRE target, Pillar 3a max-out).
- Goal progress view showing current vs. target, on a documented projection basis.
- Retirement/FIRE projection with user-editable assumptions, results as scenario ranges, not a
  single number (FR-PEN-007/008 — must carry an "illustrative model, not a forecast" disclaimer
  per NFR-REG-02-equivalent language in the requirements' advice-boundary section).

## EPIC 21 — Localization

**Anchor:** NFR-I18N-001..005, section 32.
**Sizing:** Medium, cross-cutting — touches every user-facing string across every other epic.
**Starter stories:**
- Externalize all user-facing strings as complete parameterized units (no concatenated
  sentences) — an architecture/linting story more than a feature story.
- Locale-aware number/date/currency formatting, including the CHF `1'234.56` vs. EUR `1.234,56`
  thousands-separator distinction (NFR-I18N-003).
- Runtime language switching without reinstall (NFR-I18N-002); `system_user`/`app_user.language`
  already exists in the schema (V2) to support this.
- CI check that adding a new user-facing string without an EN+DE pair fails the build.

## EPIC 22 — Privacy & Security

**Anchor:** Section 30, NFR-PRIV-*, NFR-SEC-*, NFR-CON-*, NFR-LIC-*.
**Sizing:** Large, cross-cutting. Several stories here are prerequisites the other epics already
assume exist (encryption at rest, credential storage) — pull the highest-priority ones (below)
forward if a security review flags them as blocking earlier epics.
**Starter stories:**
- Encryption at rest for sensitive fields (NFR-SEC-001) and in transit (NFR-SEC-002) — confirm
  PostgreSQL's own at-rest encryption (disk-level or `pgcrypto` column-level) meets the bar for
  `app_user.mfa_totp_secret` specifically.
- Provider credential storage in a dedicated secret store, never the application database
  (NFR-SEC-001, section 30.1's NFR-LIC-001/002).
- Outbound request minimisation/batching for price lookups so a provider cannot infer a
  workspace's portfolio composition from request patterns (NFR-SEC-006, NFR-DPR-007).
- Manual-price-only degraded mode is documented and fully functional with zero external
  entitlement (NFR-LIC-004/008, NFR-CON-004) — largely already true of the schema; this story is
  the end-to-end verification and documentation pass.
- Independent penetration test before public (hosted) launch (NFR-SEC-003, NFR-DPR-009).

## EPIC 23 — Backup / Restore

**Anchor:** Section 31.3, FR-BCK-*.
**Sizing:** Medium. Deployment-topology-dependent (self-hosted operator responsibility vs. hosted
service RPO/RTO targets, section 51.2).
**Starter stories:**
- Documented `pg_dump`/`pg_basebackup`-based backup procedure for a self-hosted deployment, with
  an in-app "last successful backup" prompt (FR-BCK-005) — this needs a small tracking table this
  backlog item should introduce (e.g. `backup_record`), not yet present in the current migrations.
- Encrypted backup support (FR-BCK-003).
- Backup integrity verification without a destructive restore (FR-BCK-004).
- Hosted-topology RPO/RTO targets and a tested restore runbook (NFR-OPS-001/002).

## EPIC 24 — External Data & Institution Integrations

**Anchor:** Section 5.1 (v0.1 doc)/28 FR-DAT-08/09/40 aggregator and bLink connectivity; explicitly
**Post-MVP** per specification section 38 ("Read-only Swiss/German bank and broker connectors").
**Sizing:** Large, and deliberately not on the MVP critical path (specification section 7/37:
"Swiss coverage via file/document import at launch, bLink in Release 2"). Do not schedule before
EPIC 07's CSV/template import framework is solid — every connector this epic adds is an
alternative *source* feeding the exact same `import_batch`/`transaction` pipeline EPIC 07 builds.
**Starter stories (Post-MVP):**
- PSD2 aggregator integration for German accounts, behind the `FR-DAT-40` provider-abstraction
  interface with failover.
- Swiss bLink evaluation and integration (RISK-005 — the single least substitutable item in the
  plan; start the evaluation early even though implementation is Post-MVP).
- Connector health status surfaced in-app, with a plain-language explanation and manual fallback
  (FR-IMP-014/015, FR-DAT-20/21) — read-only credentials only, never anything permitting order
  placement or payment initiation (NFR-SEC-005).

## EPIC 29 — API Platform & Client Shell

**Anchor:** Section 50, FR-API-*, FR-CLI-*.
**Sizing:** Medium-large. This is where the OpenAPI contract (already a `pom.xml` dependency,
`springdoc-openapi`) becomes a real, versioned, tested deliverable rather than just auto-generated
documentation.
**Starter stories:**
- OpenAPI spec generated from and validated against the implementation as a release artifact
  (FR-API-001); DTOs only, never JPA entities, across the boundary — this is worth an ArchUnit
  rule given how easily it erodes (per the architect-skill's own emphasis on this exact point).
- Idempotency-key support on state-changing endpoints (FR-API-006) — directly relevant to
  EPIC 07's import commit and EPIC 25's reconciliation-resolution endpoints.
- Pagination and server-side filtering on every collection endpoint, in particular
  `GET /transactions` against the filters in FR-TRX-005 (EPIC 07).
- Consistent error envelope with a stable code, message and correlation id (FR-API-005), never
  leaking internal detail or another tenant's existence.
- Monetary values transported as strings with explicit currency, never JSON numbers
  (FR-API-011) — a DTO-serialization convention to lock in early, since it is painful to retrofit
  once clients exist.
- Client-side token storage per platform (Keychain/Keystore/httpOnly cookie, FR-AUT-006/FR-CLI-004)
  — React Native client work, out of this backend repository's scope but tracked here for
  cross-team visibility.

## EPIC 30 — Deployment, Operations & Data Protection

**Anchor:** Section 48 (topologies, already largely addressed by
`docs/architecture/adr/0001-database-auto-migration.md` and NFR-DEP-001..006), section 51
(background jobs, FR-JOB-*; hosted-service operations, NFR-OPS-*; data protection, NFR-DPR-*).
**Sizing:** Large, cross-cutting. Several `FR-JOB-*` stories are direct prerequisites for EPIC 14
(price refresh) and EPIC 16 (daily valuation build) — pull those two forward alongside those
epics rather than deferring the whole of EPIC 30.
**Starter stories:**
- Scheduled price/FX refresh and daily-valuation-series build as Quartz-backed jobs
  (`spring-boot-starter-quartz` is already a `pom.xml` dependency), idempotent and resumable
  (FR-JOB-002), never blocking interactive reads (FR-JOB-004).
- Tenant fairness under Quartz/job load — one workspace's large import must not degrade another's
  interactive response (FR-JOB-003).
- Production role split for row-level security (the commented-out template at the bottom of
  `V20__tenancy_row_level_security.sql`) — migration-owner role vs. runtime role, wired into the
  deployment pipeline.
- Hosting-jurisdiction declaration and data-residency configuration (NFR-DPR-001).
- Breach detection/notification runbook meeting both GDPR and revFADP deadlines (NFR-DPR-006).
- Partition-maintenance job/alert for `price`/`fx_rate`/`daily_valuation`'s `_default` catch-all
  partitions (see `database-schema.md` section 5 — this is a concrete, scoped story already
  flagged there).

## EPIC 31 — Data Lifecycle, Deletion & Audit

**Anchor:** Section 52 (FR-LIF-*, largely already implemented at the schema level in
`V10`/`V15`'s append-only/rollback triggers), section 57 (FR-AUD-*, tables already exist in V17).
**Sizing:** Medium. Much of the *data model* for this epic already exists (V10's append-only
trigger, V15's batch rollback status machine, V17's three audit tables) — this epic is primarily
about the service-layer orchestration and the workspace-erasure flow that doesn't exist yet.
**Starter stories:**
- Workspace (tenant) erasure: the *only* hard delete in the system (section 52.1) — removes all
  workspace data including from backups within the published retention window (NFR-DPR-004),
  requires recent re-authentication, explicit confirmation and a cancellable grace period
  (FR-LIF-023).
- Financial-change audit log population wired into every service-layer write path
  (FR-AUD-001) — the `financial_audit_log` table (V17) exists; nothing writes to it yet.
- Soft-delete purge job for the 30-day restore window (FR-LIF-006, OPEN-032's proposed 30 days).
- Cascade preview before any action affecting dependent records (FR-LIF-007) — e.g. archiving an
  institution with accounts still under it.

## EPIC 32 — Reference Data Administration

**Anchor:** Section 55, FR-REF-*. Schema already exists in V18 (`reference_package`,
`pension_scheme_rule`, `gics_structure_version`, `fallback_sector_taxonomy`, `trading_calendar`);
this epic is the administration UI/workflow around it.
**Sizing:** Medium.
**Starter stories:**
- Reference-package import flow: upload, checksum-verify (FR-REF-005), preview what will change,
  reject in full if invalid (FR-REF-006) — never partial application.
- Package versioning that never discards a prior version where historical reproducibility depends
  on it (FR-REF-007) — GICS structure versions and effective-dated pension limits specifically.
- Rollback to the previous reference-data version (FR-REF-008).
- Staleness warnings for effective-dated values with no entry for the current period
  (FR-REF-011) — this is the mechanism EPIC 26's US-26-02 explicitly depends on.
- Never overwrite user-created institutions/templates/category customisations/overrides on
  import (FR-REF-009).
