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
- Every read-modify-write endpoint requires `If-Match: "<version>"`.
- The tag is a quoted non-negative decimal integer. Weak tags, wildcard tags and unquoted values are
  rejected as malformed.
- Missing `If-Match` returns **428 Precondition Required** with stable code `VERSION_REQUIRED`.
- A version that no longer matches returns **412 Precondition Failed** with stable code
  `VERSION_CONFLICT`.
- The resource is looked up and authorization is checked *before* comparing the version. A caller
  therefore still receives the ordinary non-enumerating 404 for a resource they cannot see.
- JPA `@Version` remains enabled. If another write wins after the explicit version check but before
  flush, the resulting optimistic-lock failure is translated to the same
  **412 / VERSION_CONFLICT** contract.
- New APIs are strict immediately; there is no transition mode that accepts a missing version.

The first retrofit covers every existing mutation of the two mutable resource APIs named by #172:
accounts (PUT, archive, restore, institution reassignment) and categories (PUT, activate, deactivate,
delete).

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
