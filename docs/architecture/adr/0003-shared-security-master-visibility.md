# ADR 0003: The security master is shared across tenants, and its membership is visible

## Status
Accepted

## Context

`V7__security_master.sql` defines `security` as global reference data: one row per instrument,
carrying no `workspace_id`, deliberately outside the row-level-security scheme (`V20` lists
`security` among the "shared, non-tenant" tables). US-12-01 (#141) makes that table writable for
the first time, lazily, on first reference: `POST /api/v1/securities` finds or creates a row by
ISIN, and `GET /api/v1/securities?isin=` looks one up without persisting (FR-SMD-007).

Sharing is the point. Ten workspaces holding the same ETF must resolve to one master record, or
every downstream aggregate - allocation, look-through, corporate actions, GICS - has to reconcile
duplicates first. NFR-LIC-007 protects this by forbidding any tenant identity on the row or in its
`security_field_provenance`: nothing stored says *which* workspace caused a record to exist.

That protects the row's contents. It does not protect the fact that the row exists, and with
manual mode as the supported baseline (NFR-LIC-004/008, no external provider until OPEN-005) every
row in `security` was typed in by a user of this instance. So:

- A member of workspace B can ask whether ISIN X is in the master. A "yes" means somebody on this
  instance tracks instrument X, and returns the display name workspace A typed.
- The real ISIN universe is a published, downloadable list of a few million entries, so this is
  enumerable, not a lucky guess.
- The inference is instance-wide, not per-workspace: it identifies no user or workspace, only that
  *one of them* holds it. On a single-household self-hosted instance - this project's stated
  deployment target - the set of "other tenants" is usually empty and the leak is vacuous.

The alternative designs were considered and rejected as worse trades:

- **Scope the master per workspace.** Destroys the shared-reference-data model that DM-25,
  FR-SMD-001/004 and every EPIC-17 aggregate assume, and multiplies rows by tenant count.
- **Require a provider-confirmed ISIN before a record may exist.** There is no provider (OPEN-005),
  and manual mode is the documented baseline.
- **Return 404 for an ISIN the caller's workspace does not already reference.** Defeats the purpose
  of the lookup, which exists precisely so a client can check before creating.

## Decision

Keep the shared, unscoped master and accept that its membership is visible to every authenticated
member of the instance. Bound the exposure rather than pretend it is closed:

1. `GET /api/v1/securities?isin=` is rate-limited per source (`app.rate-limit.security-lookup`,
   120/min by default) so a sweep of the published ISIN list is not free. This does not close the
   channel - a determined member with time still enumerates it - it removes the cheap bulk sweep.
2. `POST /api/v1/securities` is rate-limited more tightly (30/min) as a write to global data.
3. A security with no ISIN is reachable only by its `id`, an unguessable UUID. There is
   deliberately no search or listing endpoint over the master, so a hand-entered private holding
   without an ISIN is not discoverable at all.
4. NFR-LIC-007 stands unchanged: no workspace or user id on the row or its provenance, so the
   channel can never be narrowed from "somebody here" to "this person".

## Consequences

- On a multi-tenant deployment, "is ISIN X tracked on this instance" is answerable by any
  authenticated member. This is a known, accepted property, not a defect to be re-reported.
- Anyone hosting this for mutually untrusting tenants must treat that as a disclosure and decide
  whether it is acceptable for their population - it is a deployment-model question, and the
  single-household target is what makes the trade reasonable here.
- If a shared multi-tenant offering ever becomes a goal, this ADR is the thing to revisit first,
  most likely by pairing a provider-sourced master (OPEN-005) - whose membership reveals nothing
  about users, because it is not user-created - with per-workspace records for manual entries only.
- The rate limits are a deterrent sized by judgement, not a proof. They are configuration
  (`RATE_LIMIT_SECURITY_LOOKUP_*`), so a deployment that wants a tighter or looser trade can set it
  without a code change.

## Alternatives considered

See Context - per-workspace masters, provider-gated creation, and reference-scoped 404s were all
rejected for breaking the shared-master model that the rest of the domain is built on.
