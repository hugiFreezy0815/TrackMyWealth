# EPIC 04 — Portfolios (formerly Financial Institutions / Containers)

Covers `institution_catalogue` and `portfolio` (V3 as `financial_institution`, renamed by V111 in
US-04-06). Section 9, FR-INS-*, RULE-001..003, RULE-020..022, C1-C9.

**Terminology (PO decision 2026-10-20, US-04-06):** the container below the workspace is a
**Portfolio** the member names freely (`/api/v1/portfolios`). "Institution" means only the real
bank or broker behind a portfolio: the shared catalogue (`/api/v1/institutions/catalogue`),
`portfolio.catalogue_institution_id` and `portfolio.institution_type`. The stories below were
written before the rename; read "institution" in them as "portfolio" wherever the container is
meant.

---

## US-04-01 — Create an institution from the catalogue or as a custom entry

**Actor:** Workspace member
**Objective:** FR-INS-001/003/007.
**Story:** As a workspace member, I want to search the seeded institution catalogue when adding a
new financial institution, or add one that is not listed, so that no provider is ever
unsupported.
**Preconditions:** Workspace exists (its default Personal Assets container already exists per
`V19`'s trigger).
**Acceptance criteria:**
- Given the seeded catalogue (`V19`: PostFinance, Yuh, VIAC, DKB, Sparkasse), when a member
  searches "Post", then PostFinance is suggested with its logo/type/country pre-filled.
- Given a member selects a catalogue entry, when the container is created, then
  `financial_institution.catalogue_institution_id` links back to it and `container_currency`
  defaults sensibly for the country (CHF for CH, EUR for DE) but remains user-editable.
- Given no catalogue match exists, when the member types a custom name and saves, then a
  `financial_institution` row is created with `catalogue_institution_id = NULL` and identical
  capabilities to a catalogued one (FR-INS-009/010 — no institution is ever second-class).
**Applicable business rules:** FR-INS-001/003/004/007/008.
**Data requirements:** `container_currency` required (CHAR(3)); `institution_type` defaults to
`OTHER` if not chosen.
**Error/edge cases:** Creating a second container also flagged `is_personal_assets_default` must
be rejected — enforced by the partial unique index in `V3`.
**Authorization/privacy:** Workspace-scoped write (RLS + FR-HOU-004 sharing).
**Dependencies:** EPIC 03.
**Priority:** MUST.
**Definition of Done:** Integration test for both the catalogue-backed and custom paths.
**Data-quality behaviour:** N/A.

---

## US-04-02 — Institution type never restricts which account types can be added

**Actor:** Workspace member
**Objective:** FR-INS-008/RULE-020 — the single rule most likely to be violated by a careless
future change.
**Story:** As a workspace member, I want to add any account type to any institution — for example
a securities depot under a "Bank" or a cash account under a "Pension Provider" — so that the
system correctly models providers like PostFinance (bank + broker + 3a) or VIAC (pension provider
with fund positions).
**Preconditions:** A `financial_institution` of any `institution_type` exists.
**Acceptance criteria:**
- Given a `financial_institution` with `institution_type = 'PENSION_PROVIDER'` (e.g. VIAC), when a
  member adds a `CASH` account under it, then the account is created successfully with no
  validation error referencing institution type.
- Given the same institution, when a member adds a second, third Pillar 3a account under it
  (staggered withdrawal, C4), then all three coexist with distinct user-defined names.
- This is the FR-INS-012/FR-NAV-17 extensibility verification: introducing a new `account_type`
  enum value in a future migration must require no change to `financial_institution` or its
  service layer. Covered by an architecture test (e.g. ArchUnit) asserting no code path branches
  on `institution_type` when creating an account.
**Applicable business rules:** FR-INS-008/012, RULE-020/022, C3/C4.
**Data requirements:** None beyond EPIC 05's account creation requirements.
**Error/edge cases:** None — the absence of a restriction is the point.
**Authorization/privacy:** Standard workspace-scoped write.
**Dependencies:** EPIC 05.
**Priority:** MUST.
**Definition of Done:** Test creates a pension provider institution and adds a CASH, a
SECURITIES and two PENSION accounts under it in the same test.
**Data-quality behaviour:** N/A.

---

## US-04-03 — Institution Summary aggregates correctly, including negative totals

**Actor:** Workspace member
**Objective:** FR-INS-006/FR-INS-SUM-001..004.
**Story:** As a workspace member, I want each institution's summary to show total assets,
liabilities and net value in the container currency, including a correctly displayed negative
total where a container mixes assets and liabilities, so that "Sparkasse: current account +
mortgage" is not misread as an error.
**Preconditions:** An institution with at least one asset and one liability account.
**Acceptance criteria:**
- Given a Sparkasse container with a EUR 2,000 current account and a EUR 300,000 mortgage, when
  the institution summary is requested, then net value shows EUR -298,000, clearly labelled, not
  suppressed or clamped to zero.
- Given accounts in multiple currencies within one container, when the summary is computed, then
  each account's value is converted to the container currency using the FX rate/date used, and
  that rate/date is inspectable (FR-INS-SUM-004, FR-CUR-011).
- Given the summary is requested, when displayed, then it is drillable to the contributing
  accounts (FR-INS-SUM-002).
**Applicable business rules:** FR-INS-SUM-001..004, C6.
**Data requirements:** Depends on EPIC 05 accounts and EPIC 06 FX rates.
**Error/edge cases:** A container with zero accounts (C7) must show a valid, empty summary, not
an error.
**Authorization/privacy:** Workspace-scoped read.
**Dependencies:** EPIC 05, EPIC 06.
**Priority:** MUST.
**Definition of Done:** Integration test for the mixed asset/liability negative-total scenario.
**Data-quality behaviour:** If any contributing account is unreconciled or has a stale FX rate,
the summary must surface that at the headline figure (FR-CON-007/PR-011), not only in account
detail.

---

## US-04-04 — Reassign an account to a different institution without losing history

**Actor:** Workspace member
**Objective:** FR-INS-009 — institutions merge, rebrand and get acquired.
**Story:** As a workspace member, I want to move an account from one institution container to
another, so that I can correct a mismodelled provider or reflect a real-world institution merger
without losing the account's transaction history.
**Preconditions:** Two institutions exist in the workspace; an account exists under the first.
**Acceptance criteria:**
- Given an account under Institution A, when it is reassigned to Institution B, then
  `account.financial_institution_id` (now `account.portfolio_id`) is updated, all of its transactions/positions/snapshots
  remain unchanged and attached to the same `account.id`, and both institutions' summaries
  recompute correctly on the next read.
**Applicable business rules:** FR-INS-009, C2.
**Data requirements:** None beyond the FK update itself.
**Error/edge cases:** Reassigning to an institution in a different workspace must be rejected
(cross-tenant integrity, enforced by RLS plus a service-layer check that both institutions belong
to the same workspace).
**Authorization/privacy:** Requires edit access to both the account and the destination
institution.
**Dependencies:** US-04-01, EPIC 05.
**Priority:** SHOULD.
**Definition of Done:** Integration test reassigns an account with transaction history and
confirms both institutions' summaries are correct before and after.
**Data-quality behaviour:** N/A.

---

## US-04-06 — Portfolios: user-named, a suggested type and the Portfolio View (#390)

**Actor:** Member.
**Objective:** FR-INS-001/003/006/008/013/014, glossary "Portfolio" and "Depot" (spec v5).
**Story:** As a member, I want to create freely named portfolios and see each one as a tab with a
section per account type, so that PostFinance (cash, savings, 3a, depot) and Yuh (cash, depot,
crypto) are each shown the way their contents need.

**Implemented (PO decisions on #390, 2026-11-02):**
- **Rename:** `financial_institution` → `portfolio`, `container_currency` → `portfolio_currency`,
  `account.financial_institution_id` → `account.portfolio_id` (V111). API `/api/v1/portfolios`
  with operations `createPortfolio`, `getPortfolio`, `updatePortfolio` (`PUT`, full replacement
  under `If-Match`), `getPortfolioSummary` and `getPortfolioView`; account DTOs carry
  `portfolioId`, the move is `POST /api/v1/accounts/{id}/reassign-portfolio`. Only the catalogue
  search stays under `/api/v1/institutions`. `listPortfolios` is US-04-05 (#381); archiving a
  portfolio is a follow-up story.
- **Name:** a portfolio linked to a catalogue entry may still be named by the member ("VIAC 3a");
  the catalogue's name is the default.
- **Portfolio type** (V112, `portfolio_type` + `portfolio_type_overridden`): descriptive only,
  never restricts account types (FR-INS-008). Recomputed whenever an account is added, moved,
  archived or restored, while the member has not set it; `portfolioType: null` in the update goes
  back to the suggestion. Account type → section → type family:

  | Account | Section | Family |
  |---|---|---|
  | `CASH`, `SAVINGS` | cash & savings | `BANK` |
  | `PENSION` with scheme `CH_PILLAR_3A` | Pillar 3a | `PILLAR_3A` |
  | other `PENSION`, `VESTED_BENEFITS` | pension | `PENSION_FUND` |
  | `SECURITIES`, `MANAGED_MANDATE` | depot | `PORTFOLIO` |
  | `CRYPTO` | crypto | `CRYPTO` |
  | `CREDIT_CARD` | cards | `BANK` |
  | `CUSTOM_ASSET`/`REAL_ESTATE`, `MORTGAGE` | property & mortgage | none |
  | `LOAN`, other `CUSTOM_ASSET` | other | none |

  All active accounts in one family → that family; any account without a family, or several
  families → `MIXED`; no account → `BANK`; the Personal Assets default → `PERSONAL_ASSETS`.
- **Portfolio View** `GET /api/v1/portfolios/{id}/view`: signed total in portfolio and workspace
  currency, `complete`, `valueStatus`, warnings, and the non-empty sections in the order above,
  each with signed subtotals and its accounts (own-currency balance, value in portfolio currency,
  value status, positions count for accounts holding positions, `totalReturn` (always `null` until
  EPIC 15), card due date, mortgage's linked property). Same valuation as the summary and net
  worth.
- **Value status** (`CURRENT`, `ESTIMATED`, `STALE`, `UNRECONCILED`, `UNKNOWN`) is derived from the
  existing valuation result; a total takes the worst of its accounts. #380/#381 reuse it.

**Acceptance criteria:** see #390; covered by `PortfolioViewControllerTest`,
`PortfolioControllerTest`, `PortfolioMigrationTest`, `PortfolioTypeTest` and `ValueStatusTest`.
**Authorization:** every member of the workspace has full access (US-03-06); RLS keeps workspaces
apart, and another workspace's portfolio answers the same audited 404 as an unknown id.
**Priority:** MUST. **Size:** L.

