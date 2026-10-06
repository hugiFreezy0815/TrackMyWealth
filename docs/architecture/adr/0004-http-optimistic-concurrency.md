# ADR 0004: HTTP optimistic concurrency with ETag / If-Match

**Status:** Accepted  
**Date:** 2026-10-01  
**Decision owners:** TrackMyWealth backend architecture  
**Requirements:** FR-CNC-001, FR-CNC-002, FR-API-005, issue #172

## Context

Every mutable TrackMyWealth entity already has a database/JPA version column. That protects a race
inside one backend request, but it does not protect the longer read-edit-save interval at the client:
two members can read version N, edit independently, and otherwise save one after the other with the
second write silently replacing the first.

The API therefore needs a version the client can retain and an update precondition the server can
enforce after authorization but before mutation.

## Decision

Use HTTP strong entity tags for client-visible optimistic concurrency.

- Mutable resource DTOs expose a numeric `version`, including list items that may be edited inline.
- Single-resource responses also send `ETag: "<version>"`.
- Every read-modify-write endpoint requires `If-Match: "<version>"` (see *Rollout* for which
  endpoints already do).
- The tag is a quoted non-negative decimal integer. Weak tags, wildcard tags and unquoted values are
  rejected as malformed.
- Missing `If-Match` returns **428 Precondition Required** with stable code `VERSION_REQUIRED`.
- A version that no longer matches returns **412 Precondition Failed** with stable code
  `VERSION_CONFLICT`.
- The resource is looked up and authorization is checked *before* comparing the version. A caller
  therefore still receives the ordinary non-enumerating 404 for a resource they cannot see.
- JPA `@Version` remains enabled. If another write wins after the explicit version check but before
  flush, the resulting optimistic-lock failure is translated to the same
  **412 / VERSION_CONFLICT** contract when the request carried `If-Match`. A request without
  `If-Match` sent no precondition that could fail (RFC 9110), so the same race there is
  **409 / VERSION_CONFLICT**. Clients branch on the code, which is the same in both cases.
- New APIs are strict immediately; there is no transition mode that accepts a missing version.

## Rollout

The first retrofit (#172) covers every existing mutation of the two mutable resource APIs it names:
accounts (PUT, archive, restore, institution reassignment) and categories (PUT, activate, deactivate,
delete).

#207 retrofits every other existing mutating endpoint: account ownership, snapshot replace,
statement config, settlement source and matches, sharing-grant revoke, categorization-rule
deactivate, transaction category/removal/untracked-transfer actions, workspace-member deactivate
and admin user edits. New mutable endpoints must follow this ADR from the start.

### Not read-modify-write

These mutating endpoints take no `If-Match`, because there is no client-held version of an
existing resource that a concurrent write could silently overwrite:

- **Creates** (`POST` on a collection): accounts, snapshots, users, categories, categorization
  rules, import templates, import batches (the upload, US-07-04), institutions, sharing grants,
  transactions, custom-asset valuations. Transactions and
  valuations are append-only besides. An account's opening balance is created the same way
  (`POST .../opening-balance`, a singleton: a second one is a 409); replacing or deleting it needs
  `If-Match`.
- **Idempotent operations:** `POST /securities` (find-or-create of shared reference data) and
  `POST .../settlement-matches/run` (re-runs matching; no client-held state).
- **Import dry runs** (US-07-03): `POST /import-templates/detect`, `POST /import-templates/test`
  and `POST /import-templates/{id}/test` parse an uploaded file in memory and write nothing.
- **Credential exchanges and bootstrap:** login, token refresh, MFA verification, and the one-time
  administrator setup.
- **The caller's own MFA enrollment** (enroll, confirm, disable): each step is authorized by a fresh
  TOTP code or the password, which already proves the caller acts on the current state.
- **Session revocation:** a terminal, idempotent transition; two revocations cannot lose an
  update.
- **Error rendering:** `ProblemErrorController` is mapped for every HTTP method so an error
  forwarded from any request gets the problem body; it writes nothing.

Two tests enforce this from the one reviewed list (`IfMatchExceptions`): `IfMatchCoverageTest`
fails the build for any other mutating handler without `If-Match` (shortcut annotations and
`@RequestMapping` alike), and `ApiConventionsIntegrationTest` fails it for any other POST, PUT,
PATCH or DELETE route Spring serves whose OpenAPI operation lacks a required `If-Match` with its
412/428 responses. Adding an entry to the list means adding it here too.

## Why ETag / If-Match

A body `version` field would work for ordinary PUTs but is awkward for state-changing POST/DELETE
operations and creates a different convention for every request shape. `If-Match` gives all
mutations the same transport contract while response bodies still carry `version` so list items can
retain it naturally.

## Consequences

Clients must keep the version they read. On `VERSION_CONFLICT` they fetch the current resource,
show the current state to the user, and only retry after an explicit user decision. They must never
silently retry a stale mutation.

CORS must allow `If-Match` and expose `ETag`. OpenAPI must document the header and response
version. New mutable resource endpoints must follow this ADR as part of code review.

## #207 retrofit decisions

V48 adds database-owned revisions to the older mutable rows that did not already have one:
`account_credit_card`, `account_snapshot`, `categorization_rule`, `settlement_match` and
`sharing_grant`. `transaction` already had the same version/trigger convention from V10; #207
only maps and exposes it. `app_user`, `workspace_member` and `account` were already versioned.

Statement-cycle configuration and settlement-source configuration intentionally share the
`account_credit_card.version`: they mutate different fields of the same card-extension resource,
so changing either invalidates a stale editor of the other. Transaction category, lifecycle and
untracked-transfer commands similarly share `transaction.version`.

Admin user, workspace member and sharing grant gained narrow authorized GET-by-id endpoints because
those resources previously had command endpoints but no way to refresh an existing concurrency
token. List/create responses continue to expose versions where they already serve as reads.

Account ownership is a full-replacement aggregate whose rows are dated history and may
legitimately be empty, so it uses the parent `account.version` as its token
(`AccountOwnershipSetResponse.version`); a replacement advances it. Consequences, accepted:

- Ownership and the account's own fields share one token: changing either makes a client's stale
  copy of the other a 412 - a reload, never a lost update.
- The replacement advances the token with an UPDATE of the account row that changes no column
  itself; the row's triggers bump `version` and also set `updated_at`, so an ownership change shows
  as a change of the account.
- Two replacements based on the same read cannot both win: `assignOwnership` takes the account row
  lock (`findByIdForUpdate`) before comparing versions, so the loser waits for the winner's commit
  and then sees the advanced version (412). `AccountOwnershipControllerTest` races exactly that.
- `GET`/`PUT .../ownership` answer an object (`accountId`, `version`, `owners`) instead of a bare
  list - a wire change made while no client consumed the endpoint yet.

Automatic recategorization (a rule change re-running categorization) writes the transaction row
too, so it advances `transaction.version` like a user's edit does. A client holding an ETag from
before such a run gets a 412 on its next edit and must reload - deliberate: the category it saw is
no longer the stored one.
