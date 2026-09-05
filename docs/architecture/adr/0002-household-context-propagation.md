# ADR 0002: Household context propagation via TransactionSynchronization

## Status
Accepted

## Context

FR-TEN-001/003 require the row-level-security policies in `V20__tenancy_row_level_security.sql`
to have a trustworthy `app.current_household_id` session variable, set once per database
transaction and resolved server-side from the authenticated principal - never from a
client-supplied path/query parameter (`US-28-01`,
`docs/user-stories/EPIC-28-tenancy-auth-authorization.md`).

The RLS policies rely on `SELECT set_config('app.current_household_id', ?, true)`, where the
`true` (`is_local`) argument scopes the value to the current transaction so it can never leak
across two requests sharing a pooled connection - a real risk under HikariCP-style connection
pooling. The mechanism must therefore run exactly once **per database transaction**, not once per
HTTP request: a single endpoint may open zero, one, or several transactions, and US-28-01's
Definition of Done requires an automated test proving no leakage when two sequential requests
share one pooled connection (a connection pool sized to 1).

`V20`'s own SQL comment forward-references `com.trackmywealth.backend.config.HouseholdContextExample`
as the intended implementation pattern; that class does not exist yet in the codebase - this ADR
is the first real design decision for it, not a record of existing behaviour.

## Decision

Implement household-context propagation as a Spring `TransactionSynchronization`, registered via
`TransactionSynchronizationManager.registerSynchronization` at the point a transaction begins,
whose callback issues the `set_config` call before any other statement runs in that transaction.
The household id itself is resolved once from the authenticated principal (Spring Security's
`Authentication`/a custom principal type) - never from a request path or query parameter,
satisfying US-28-01's explicit acceptance criterion.

## Consequences

- Correctly scoped to "once per transaction," matching the literal requirement, rather than
  approximating it at the HTTP-request layer or relying on every database access happening to
  route through an AOP-advised service method (see Alternatives).
- Makes the required pooled-connection-leakage test straightforward to write: open a transaction,
  assert the synchronization fired and the variable is set; commit; open a second transaction on
  the same pooled connection; assert the variable was not carried over from the first.
- Because this hooks the transaction lifecycle rather than the HTTP layer, any future non-HTTP
  entry point - background jobs (Quartz, see `BACKLOG-remaining-epics.md` EPIC 30/31), admin
  tooling, batch imports - must go through the same registration mechanism, or it will silently
  run with no household context. RLS will correctly deny such a job by default (fail-closed), but
  that denial will look like a mysterious bug rather than an intentional "this job legitimately
  needs cross-household access" case unless the job's own service code explicitly opts in. This is
  a related but separate concern from the eventual migration-role/runtime-role split documented in
  `database-schema.md`'s "Production hardening not yet wired up" section.
- US-28-01's second acceptance criterion (resolving ambiguity for a member viewing a shared grant
  from another household via an explicit "acting as household X" selector) is a service-layer
  concern that feeds into the same resolution step; this ADR governs where/how the resolved value
  is applied to the database session, not how it is resolved when ambiguous.

## Alternatives considered

- **AOP aspect around the `..service..` package.** Simpler to implement and explain, but only
  correct if every household-scoped database access is guaranteed to go through an advised service
  method. A repository call reached some other way - a future batch job, an admin tool, a
  differently-layered code path - would silently lose protection with no compiler or test signal
  until the FR-TEN-010 cross-tenant suite happened to exercise that exact path.
- **Servlet `Filter`/`HandlerInterceptor`.** Rejected. Runs outside the transaction/connection
  boundary: at `preHandle` time no database connection is necessarily checked out from the pool
  yet, so `set_config` would require acquiring its own connection separately from whichever one
  Spring's transaction manager later binds to the actual service call - defeating the
  transaction-local (`is_local = true`) guarantee the entire design depends on.
