# EPIC 28 — Tenancy, Authentication & Authorization

Covers the row-level-security mechanism in `V20__tenancy_row_level_security.sql` and its
application-layer counterpart (the `current_household_id()` session variable, object-level
authorization). Section 48.4, FR-TEN-*. Complements EPIC 02 (which covers login/MFA/sessions
themselves); this epic covers the *isolation* guarantee that sits underneath every other epic's
"household-scoped" acceptance criteria.

---

## US-28-01 — Every request sets the household context exactly once, transaction-scoped

**Actor:** Developer
**Objective:** FR-TEN-001/003 — the mechanism the RLS policies in V20 depend on.
**Story:** As a developer, I want every authenticated API request to set
`app.current_household_id` via `SELECT set_config(..., true)` once, at the start of its database
transaction, derived from the authenticated principal's resolved household context (never from a
client-supplied parameter), so that PostgreSQL's row-level security policies have a trustworthy
value to check against.
**Preconditions:** EPIC 02 authentication is in place.
**Acceptance criteria:**
- Given an authenticated request for household A's data, when it reaches the service layer, then
  the household id used to set the session variable is resolved server-side from the principal's
  own household membership/grants — never taken from a request path/query parameter, even if one
  happens to be present (prevents a client simply passing a different household's id).
- Given a request spans a household-switch scenario (a member belonging to a household viewing a
  shared grant from another household — see US-03-03), when the effective household context is
  ambiguous, then it is resolved explicitly per-request (e.g. via an explicit "acting as household
  X" selector validated against the principal's actual grants), never left implicit.
- Given two requests share a pooled database connection (common under connection pooling), when
  the first request's transaction commits, then its session variable does not leak into the next
  request on the same physical connection — verified because `set_config(..., true)` is
  transaction-local (`is_local = true`) by construction.
**Applicable business rules:** FR-TEN-001/002/003.
**Data requirements:** None.
**Error/edge cases:** A request with no resolvable household context at all (e.g. a
`SYSTEM_ADMINISTRATOR` with no household link, per EPIC 02's US-02-05) — the session variable is
left unset, and every RLS policy then denies by default (verified directly in
`database-schema.md` section 4's manual test and must be covered by an automated test here too).
**Authorization/privacy:** This story is itself the tenancy enforcement mechanism.
**Dependencies:** EPIC 02, `V20__tenancy_row_level_security.sql`.
**Priority:** MUST.
**Definition of Done:** A Spring interceptor/aspect implementing this is unit-tested for the
pooled-connection-leakage scenario (open two sequential requests against the same connection from
a test pool sized to 1, assert no leakage).
**Data-quality behaviour:** N/A.

---

## US-28-02 — Object-level authorization on every read and write, not just endpoint-level

**Actor:** Developer
**Objective:** FR-TEN-004 — "broken object-level authorization... is the most common serious API
vulnerability."
**Story:** As a developer, I want every single-resource read/write endpoint to verify the
authenticated principal is entitled to that *specific* object, not merely that they are
authenticated and calling a household-scoped-looking endpoint, so that changing an id in a request
can never retrieve another household's record even if RLS were somehow misconfigured for a given
query path.
**Preconditions:** US-28-01.
**Acceptance criteria:**
- Given a valid session for household A, when a request for `GET /accounts/{id}` supplies an id
  belonging to household B, then the response is a `404` (indistinguishable from "does not
  exist," per FR-TEN-006), and an `authorization_denial_log` row is written
  (`reason = 'NOT_FOUND'` at the API layer, even though the underlying cause is "not authorized"
  — the two are deliberately presented identically to the caller).
- Given the same scenario for a genuinely nonexistent id, when requested, then the response is
  identical in shape, status code, and timing profile (within normal variance) to the
  cross-tenant case above.
**Applicable business rules:** FR-TEN-004/005/006.
**Data requirements:** None.
**Error/edge cases:** Timing-based enumeration — the story requires "indistinguishable... in
response and in timing," which should be verified with a basic timing-variance test, not just a
status-code assertion.
**Authorization/privacy:** This story is itself an authorization requirement.
**Dependencies:** US-28-01.
**Priority:** MUST.
**Definition of Done:** Covered by the cross-tenant test suite (US-28-04).
**Data-quality behaviour:** N/A.

---

## US-28-03 — Non-enumerable identifiers everywhere

**Actor:** Developer
**Objective:** FR-TEN-005.
**Story:** As a developer, I want every externally visible identifier to be a non-sequential,
non-guessable UUID, so that enumerating ids to probe for other households' data is infeasible.
**Preconditions:** None — this is already the schema-wide convention (`gen_random_uuid()` primary
keys throughout, V1).
**Acceptance criteria:**
- Given the full schema, when audited, then no table exposes a `SERIAL`/`BIGSERIAL`/sequential
  integer id in any API response.
- Given a UUID is generated, when inspected, then it is UUIDv4 (random), not a UUIDv1/time-based
  variant that could leak creation-order information.
**Applicable business rules:** FR-TEN-005.
**Data requirements:** None.
**Error/edge cases:** None — this is a convention-verification story.
**Authorization/privacy:** N/A.
**Dependencies:** None (already satisfied by the schema as designed; this story is the explicit
verification/documentation step).
**Priority:** MUST.
**Definition of Done:** A schema-linting script (or a simple SQL query against
`information_schema`) run in CI asserting no `SERIAL`-typed primary key exists anywhere.
**Data-quality behaviour:** N/A.

---

## US-28-04 — Automated cross-tenant test suite across every endpoint and entity type

**Actor:** Developer / QA
**Objective:** FR-TEN-010 — "isolation is asserted by tests, not by inspection."
**Story:** As a developer, I want an automated test suite that, for every API endpoint and every
entity type in the schema, attempts to access another household's data and asserts denial, so
that tenancy isolation is continuously verified rather than relying on manual review.
**Preconditions:** US-28-01, US-28-02, a reasonably complete API surface (this story grows
alongside every other epic — treat it as a living suite, not a one-time deliverable).
**Acceptance criteria:**
- Given two households A and B each with a full set of entities (accounts, transactions, budgets,
  goals, ...), when the suite runs, then for every entity type it attempts to read/update/delete
  household B's instance while authenticated as household A, and asserts denial for every single
  one.
- Given a table protected only transitively (e.g. `tax_lot`, protected via a join to `account` —
  see `database-schema.md` section 4), when the suite runs, then it specifically exercises that
  table's endpoints too, not only the directly-RLS-scoped tables — this is exactly the gap the
  documentation on that transitive-protection design calls out as needing explicit test coverage.
- Given the suite runs, when included in CI, then it runs on every pull request (it is fast enough
  to be part of the standard build, not a nightly-only job, given the small fixed dataset size
  involved).
**Applicable business rules:** FR-TEN-010.
**Data requirements:** Two fully-populated synthetic households as fixtures.
**Error/edge cases:** As entity types are added by future migrations, the suite must be extended
in the same pull request — enforce via a lightweight registry/checklist (e.g. a test that asserts
every `@Entity` class has a corresponding cross-tenant test case registered) rather than relying
on developer memory.
**Authorization/privacy:** This story validates every other privacy/authorization requirement in
the backlog.
**Dependencies:** US-28-01, US-28-02, and grows with every entity-introducing epic.
**Priority:** MUST.
**Definition of Done:** Suite exists, runs in CI, covers every entity type present at the time
EPIC 19 (consolidated reporting) is considered done, and is documented as an ongoing maintenance
obligation for every future entity.
**Data-quality behaviour:** N/A.
