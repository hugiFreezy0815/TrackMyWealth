# EPIC 03 — Workspace & Membership

Covers `workspace` and `workspace_member` (V2, V110). FR-HOU-*, FR-TEN-008.

Membership is the only access grant: every active member of a workspace sees and changes
everything in it (US-03-06). US-03-01..03 below described the withdrawn ownership and grant model
(OPEN-021 closed, PO decision 2026-10-20); they stay here as history, marked withdrawn, and V110
removed what they built (`account_ownership`, `sharing_grant`, `is_dependent`).

---

## US-03-01 — Add a workspace member without a login (dependent) — WITHDRAWN

> Withdrawn by US-03-06 (#389): every member is a user with a login, and there is no ownership
> or grant within a workspace.

**Actor:** Workspace member with edit access
**Objective:** RULE-018 — a workspace member may be represented financially without having a
login.
**Story:** As a workspace member, I want to add a dependent (e.g. a child) to the workspace's
financial model without creating a login for them, so that I can track custodial accounts held on
their behalf.
**Preconditions:** Authenticated user with a workspace.
**Acceptance criteria:**
- Given a workspace, when a member adds a dependent with `is_dependent = true`, then a
  `workspace_member` row is created with no linked `app_user`.
- Given a dependent member, when accounts are assigned to them via `account_ownership`, then
  consolidated person-scoped figures for that dependent work exactly as for any other member
  (FR-HOU-005), even though they cannot log in.
**Applicable business rules:** RULE-018, FR-HOU-001.
**Data requirements:** `display_name` required.
**Error/edge cases:** Converting a dependent into a full user later (linking an `app_user`) must
not lose their ownership history.
**Authorization/privacy:** Requires `EDIT` or `FULL` workspace-level access.
**Dependencies:** EPIC 02.
**Priority:** MUST.
**Definition of Done:** Integration test creates a dependent, assigns an account, and confirms
person-scoped net worth includes it.
**Data-quality behaviour:** N/A.

---

## US-03-02 — Assign fractional or joint ownership to an account — WITHDRAWN

> Withdrawn by US-03-06 (#389): every member is a user with a login, and there is no ownership
> or grant within a workspace.

**Actor:** Workspace member with edit access
**Objective:** FR-HOU-002/003/005/006.
**Story:** As a workspace member, I want to assign an account to one or more owners, optionally
with a fractional share, so that person-scoped views are correct for jointly held accounts.
**Preconditions:** Account and workspace members exist.
**Acceptance criteria:**
- Given an account with no ownership row yet, when a member assigns 100% ownership to themself,
  then an `account_ownership` row is created with `effective_from = today`.
- Given a joint account, when two members are each assigned 50%, then a person-scoped net-worth
  view for either member shows 50% of the account's value, while the workspace-scoped view shows
  100% exactly once (FR-HOU-005 — never double-counted).
- Given an ownership change (e.g. a member buys out their partner's share), when the new
  ownership is recorded, then the old `account_ownership` row is closed (`effective_to` set) and
  a new one opens, rather than being overwritten in place (FR-HOU-006).
**Applicable business rules:** FR-HOU-002/003/005/006, FR-HHL-001.
**Data requirements:** Sum of concurrently-effective ownership shares for one account should be
validated by the service layer to not exceed 100% (not a DB constraint, since partial data entry
during onboarding is normal and must not be blocked).
**Error/edge cases:** Overlapping `effective_from`/`effective_to` ranges for the same
(account, member) pair — prevented by the partial unique index on "current" ownership in `V6`;
service layer must close the old row in the same transaction as opening the new one.
**Authorization/privacy:** Only members with `EDIT`/`FULL` access to the account may change
ownership.
**Dependencies:** US-03-01, EPIC 05 (accounts must exist).
**Priority:** MUST.
**Definition of Done:** Integration test for the 50/50 joint-account scenario in the acceptance
criteria above, asserting both the person-scoped and workspace-scoped totals.
**Data-quality behaviour:** N/A.

---

## US-03-03 — Grant and revoke sharing access between workspace members — WITHDRAWN

> Withdrawn by US-03-06 (#389): every member is a user with a login, and there is no ownership
> or grant within a workspace.

**Actor:** Workspace member with `FULL` access to an account or the workspace
**Objective:** FR-TEN-008 — explicit, revocable, granular sharing.
**Story:** As a workspace member, I want to grant another member a specific access level
(no access / balance-only / read / edit / full) to one account, one institution, or the whole
workspace, so that visibility is never an implicit merge of another member's private data.
**Preconditions:** Two or more `workspace_member` rows in the workspace.
**Acceptance criteria:**
- Given no grant exists between member A and member B for account X, when B requests account X,
  then access is denied (FR-TEN-002 — deny by default).
- Given A grants B `READ` access to account X, when B requests account X, then B can view it but
  not edit it; a `sharing_grant` row is created with `granted_by_member_id = A`.
- Given A revokes the grant, when the revoke is processed, then `revoked_at` is set and B's very
  next request for account X is denied — not merely after B's token expires (FR-TEN-009).
**Applicable business rules:** FR-TEN-002/008/009, FR-HOU-004.
**Data requirements:** `scope_type` in `{ACCOUNT, INSTITUTION, WORKSPACE}`; exactly one of
`scope_account_id`/`scope_institution_id` set to match (see the `CHECK` constraint on
`sharing_grant` in `V6`).
**Error/edge cases:** Granting access to an account the granter does not themself have `FULL`
access to must be rejected (a member cannot share what they cannot fully see).
**Authorization/privacy:** This story is itself an authorization primitive; every other epic's
workspace-scoped read/write ultimately consults `account_ownership` + `sharing_grant`.
**Dependencies:** US-03-01, US-03-02.
**Priority:** MUST.
**Definition of Done:** Integration test grants, verifies access, revokes, and verifies immediate
denial on the next request.
**Data-quality behaviour:** N/A.

---

## US-03-04 — Last-member protection

**Actor:** Workspace member
**Objective:** FR-HHL-015.
**Story:** As a workspace member, I want the system to prevent a workspace from ever being left
with zero active members, so that a workspace can never become inaccessible.
**Preconditions:** A workspace with exactly one active member.
**Acceptance criteria:**
- Given a workspace with one active member, when that member attempts to deactivate themself,
  then the request is rejected with a clear explanation.
- Given a workspace with two active members, when one deactivates themself, then the operation
  succeeds and the other remains.
**Applicable business rules:** FR-HHL-015 (by analogy with FR-USR-005).
**Data requirements:** None.
**Error/edge cases:** Deactivating the last member via a bulk/admin path must be blocked
identically to the self-service path.
**Authorization/privacy:** N/A.
**Dependencies:** US-03-01.
**Priority:** MUST.
**Definition of Done:** Unit test on the service-layer guard.
**Data-quality behaviour:** N/A.

---

## US-03-06 — Every workspace member has full access

**Actor:** Any member of a workspace.
**Objective:** FR-HOU-007, FR-HOU-008, FR-TEN-008, RULE-018 (v5 spec).
**Story:** As a member of a workspace, I want to see and change everything in my workspace
without owners or grants, so that whatever I create or import is simply there.
**Preconditions:** A workspace with one or more active members, each a user with a login.
**Acceptance criteria:**
- Given a workspace with two active members A and B, when A creates an institution, an account
  and a portfolio and imports a file into the account, then B sees all of them, including the
  imported transactions, balances and positions, in every list, detail and report endpoint
  without any further step.
- Given the same workspace, when B edits or deletes something A created, then it succeeds
  (subject to the usual optimistic-locking version check).
- Given a member of workspace X, when they request any id from workspace Y, then the response is
  404, exactly as for an unknown id.
- Given an account is created, then no owner has to be supplied and none is returned.
- Given a deactivated member, when they call any endpoint, then access is refused on the next
  request; all data they created stays in the workspace.
- Given the API, then no endpoint for account ownership or sharing grants exists.
- Given an attempt to create a member without a login, then it is rejected.
**Applicable business rules:** FR-HOU-007/008, FR-TEN-001/002/008/009, RULE-018,
FR-USR-008/011, FR-STA-007. Withdrawn: FR-HOU-002..006, FR-HHL-001, FR-HHL-005.
**Data requirements:** V110 drops `account_ownership`, `sharing_grant` and
`workspace_member.is_dependent`; `app_user.workspace_member_id` is unique, and a member without a
login is refused at commit. A database holding a member without a login, or with two, stops the
migration with a message (the dev phase has no production data).
**Error/edge cases:** Access levels (`BALANCE_ONLY`, `READ`, `EDIT`) are gone with the grants: a
member may do everything, a non-member nothing.
**Authorization/privacy:** Workspace membership is the only access grant. Administration rights
confer no financial access (FR-TEN-007); separating the administrator from every workspace
(FR-USR-013) is a follow-up story.
**Dependencies:** Spec PR #391.
**Priority:** MUST.
**Definition of Done:** `WorkspaceMembershipAccessIntegrationTest` covers every acceptance
criterion with two real members and a member of another workspace;
`WorkspaceMembershipMigrationTest` covers V110's refusals; the cross-tenant suite (US-28-04)
stays green.
**Data-quality behaviour:** N/A.
