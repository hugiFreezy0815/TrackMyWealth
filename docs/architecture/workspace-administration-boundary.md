# Workspace membership and administration boundary (US-03-06, #389)

## Product rules (spec v5, PO decisions 2026-10-20..22)

- Every ACTIVE member of a workspace has full access to every financial resource in it. There is
  no account ownership, no per-account or per-scope grant and no access level (FR-TEN-008,
  FR-HOU-007).
- Every member is a user with a login (FR-USR-008). The MVP allows one member per workspace
  (FR-HOU-010); the model and the access checks treat membership as a set, so a later release can
  allow several without a data migration.
- Workspace A's users never see workspace B's data. PostgreSQL RLS stays the boundary, with
  `AccessControlService` as its application-level half.
- A `SYSTEM_ADMINISTRATOR` administers users, workspace lifecycle, features and job schedules, and
  may see a workspace's technical metadata including its name (FR-USR-015), but never its
  financial data. An administrator login is never a workspace member (FR-USR-013).

## Done in #389

- `AccessControlService` decides on membership alone: an active member of the resource's
  workspace may proceed, anyone else gets the audited 404. Access levels are gone from its API.
- Account ownership and sharing grants are removed end to end: controllers, services, entities,
  repositories, DTOs, and the `account_ownership`/`sharing_grant` tables (V110). The savings
  pocket no longer depends on owners.
- `workspace_member.is_dependent` is dropped; `app_user.workspace_member_id` is unique, and V110
  refuses a member without a login at commit. The column stays nullable, because an administrator
  has no membership.
- `WorkspaceMembershipAccessIntegrationTest` proves every acceptance criterion with two real
  members, and `WorkspaceMembershipMigrationTest` proves V110's refusals.

## Follow-up: administrator never a member (FR-USR-013, FR-USR-016)

Today `SetupService` still links the bootstrap administrator to a workspace member, and
`AdminUserService.createUser` adds every new user to the acting administrator's workspace.

**Interim rule (in #389).** Before #389, ownership and grants kept the administrator out of
another member's accounts. Membership-only access would have removed that barrier, so
`AccessControlService.requireActingMember` refuses an administrator's membership as soon as the
workspace has a second active member. Until then the bootstrap login can set up and use a fresh
install; afterwards it reaches none of the workspace's financial data, not even what it created
itself, and every refusal is an audited 404 (`authorization_denial_log`).
`AdministratorFinancialAccessTest` proves it.

The follow-up story replaces this rule with the real boundary. It changes all of the following
together, because each part alone leaves a deployment unusable:

1. First-run setup creates the administrator only, with no workspace and no membership.
2. `createUser` sets up a new workspace for each Standard User, who becomes its only member
   (FR-USR-016, closed-registration variant), and refuses to make an administrator a member.
3. A role change across the boundary is refused: a member never becomes an administrator, and an
   administrator never becomes a member (FR-USR-013).
4. The principal carries no workspace for an administrator, enforced in one place
   (`AuthenticatedUserPrincipal`), with a shared role constant instead of string literals.
5. A forward migration stops with a message on a database whose administrator is linked to a
   member (PO decision 2026-10-23: reset the development database rather than repair it).
6. Test fixtures that use the bootstrap token for financial endpoints log in as a Standard User.
7. Admin metadata reads (the workspace name, FR-USR-015) go through a narrow read path that
   returns metadata only, since the `workspace` table is RLS-protected and an administrator has no
   tenant context; a `SECURITY DEFINER` function like V81's is the likely shape.

## Feature configuration (FR-ADM-001..008, separate story)

A workspace-scoped `workspace_feature_setting` table (workspace FK, validated feature key, enabled
flag, optimistic version, actor/timestamp) behind `/api/v1/admin/workspaces/{id}/features`, with
every change audited. Backend feature gates enforce the setting on every relevant operation, not
only in the UI (FR-ADM-004). The administrator's metadata repository reads workspace settings and
feature flags only, never account or transaction data. Integrations (bank/API syncs) get their own
`workspace_integration` configuration with encrypted secret references; it is not an access
control.
