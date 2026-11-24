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

## Administrator and workspace provisioning (US-03-08, #414)

- First-run setup creates a workspace-free administrator. The selected currency is an
  administrator profile preference. The legacy workspace name field remains accepted at setup.
- Creating a Standard User provisions a workspace, its default Personal Assets portfolio,
  the sole membership and the login in one transaction. User creation accepts an optional
  workspace name and currency, with CHF as the default. The audit record includes the
  workspace and membership IDs.
- Additional administrators have no membership. The API refuses role changes between Standard
  User and System Administrator. The database rejects an administrator with a member link, and
  the authenticated administrator principal has no workspace context.
- `GET /api/v1/admin/workspaces` returns paged IDs and names. Its database function uses
  the same ownership and runtime grants as V81's authentication functions.
- V127 stops an upgrade when it finds a legacy administrator linked to a member. It reports
  the IDs and preserves the data. Reset only disposable development databases. An installation
  whose data must be retained requires a separate data-preserving migration plan.

## Feature configuration (FR-ADM-001..008, separate story)

A workspace-scoped `workspace_feature_setting` table (workspace FK, validated feature key, enabled
flag, optimistic version, actor/timestamp) behind `/api/v1/admin/workspaces/{id}/features`, with
every change audited. Backend feature gates enforce the setting on every relevant operation, not
only in the UI (FR-ADM-004). The administrator's metadata repository reads workspace settings and
feature flags only, never account or transaction data. Integrations (bank/API syncs) get their own
`workspace_integration` configuration with encrypted secret references; it is not an access
control.
