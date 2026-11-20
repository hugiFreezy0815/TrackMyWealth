# ADR 0006: API contract, paging and client generation

## Status
Accepted. This ADR records the eight decisions in the
[Sprint 6 plan](../../backlog/SPRINT-6.md#architecture-decisions-po-confirmed-2026-10-20-unless-marked)
and the product decisions from #371. Client instructions and current endpoint exceptions live in
[API conventions](../../api/conventions.md). Policies that require follow-up implementation are
identified below, each with its issue.

## Contract decisions

| # | Decision and reason | Rejected alternative |
| --- | --- | --- |
| 1 | Generate and commit OpenAPI 3.1 from Spring controllers. CI checks drift so clients can review and generate against the same contract without running the server. | Runtime-only OpenAPI or a separately maintained spec that can drift from the controllers. |
| 2 | Evolve `/api/v1` additively. CI diffs against `main` with oasdiff. A deliberate breaking change requires the `api-breaking` label and a PR note. The initial paging-envelope migration (#373) was an explicitly approved exception. | Unreviewed in-place breaks, which can strand separately released clients. |
| 3 | Use an owned `PageResponse<T>` with zero-based offset paging, total counts, field-based filters and a stable unique sort tie-breaker. This supports numbered pages on the expected workspace data volumes and keeps the wire shape independent of Spring. After known changes to the result set, restart at page 0. Return arrays only for complete, domain-bounded lists. | Serializing Spring Data's `Page`, adopting cursors, or silently truncating an array. |
| 4 | Give every operation an explicit unique `operationId` and tag resource areas. The contract checks keep generated names stable and group related operations. | springdoc's scan-order suffixes for duplicate Java method names. |
| 5 | Generate TypeScript types from the committed contract using `openapi-typescript`. Keep the app's transport so refresh, timeout and conditional-write behavior stay centralized. Map `format: decimal` to `DecimalString`. | Hand-copying DTOs or replacing the transport with generated request code. |
| 6 | Derive collection scope from the session and enforce it server-side with workspace access checks and row-level security. Under #389, members have workspace-wide access subject to the operation's filters. Foreign IDs filter like unknown IDs. | Trusting a client-supplied workspace selector or giving foreign IDs distinct errors. |
| 7 | Seed synthetic demo data through the normal HTTP API (#385). This exercises the public contract and keeps real statement exports out of the demo. Demo credentials exist only after explicit seeding. | Direct SQL fixtures, real import samples, or a demo login in the default configuration. |
| 8 | Review read response shapes against the approved screen specifications (FR-API-010). Each screen should need one request or a small fixed number of requests and use server-calculated figures. | Freezing the shape before UX review or asking the app to assemble per-row results and compute totals. |

## Standards baseline and deviations

Use the [Zalando RESTful API Guidelines](https://opensource.zalando.com/restful-api-guidelines/)
as the REST design baseline. The contract uses [OpenAPI 3.1](https://spec.openapis.org/oas/v3.1.0),
[RFC 9110](https://www.rfc-editor.org/rfc/rfc9110) for HTTP semantics and conditional requests,
[RFC 6585](https://www.rfc-editor.org/rfc/rfc6585) for 428 and 429,
[RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) for problem details,
[RFC 3339](https://www.rfc-editor.org/rfc/rfc3339) for timestamps and
[ISO 4217](https://www.iso.org/iso-4217-currency-codes.html) for currency codes.

| Area | TrackMyWealth decision and reason | Baseline alternative |
| --- | --- | --- |
| Versioning | Keep the existing major version in `/api/v1`. Changing the path breaks existing clients and self-hosted setups. | Media-type version negotiation. |
| Base path | Keep the `/api` prefix. The backend serves `/api-docs` and its operational endpoints from the same host, and one prefix lets a reverse proxy route the API. Changing it breaks existing clients. | No `/api` base path. |
| Path segments | Keep the one published path that ends in a file name, `.../imports/{batchId}/errors.csv`. Renaming it breaks existing clients. No other path has a file extension, and none is added. | Kebab-case segments only, with the format chosen by content negotiation. |
| Property and filter names | Keep lower camel case for the published JSON and query vocabulary. Matching filter and response names makes the contract easier to use and preserves wire compatibility. | Snake case. |
| Action URLs | Keep documented commands such as archive, restore and import commit, which have their own preconditions and domain effects. | Model each transition as a separate noun resource. |
| Paging | Use numbered offsets and the `content` and `page` envelope with totals and deterministic sorting. This supports the UI's numbered pages and keeps the existing client contract. | Cursors and linked navigation in a response with an `items` collection. |
| Paging and sort parameters | Use `page`, `size` and a repeatable `sort=<field>,<asc\|desc>` (PO decision 2026-10-20, #373). The names fit numbered pages, and one `sort` parameter per field keeps each value a closed enum in the contract. | `offset` and `limit`, and one `sort` parameter with `+` and `-` prefixes such as `sort=+id`. |
| Money | Preserve operation-specific amount and currency fields and use exact decimal strings (#176), including where an operation already specifies the currency for all its amounts. | Zalando's common money object with a numeric amount and currency together. |
| Problem details | Add stable `code` and `correlationId` fields and document the corresponding `tmw.error.*` keys. Clients need a stable discriminator and localized copy. These extension members are compatible with RFC 9457. | Using only the problem type and standard fields as the client contract. |
| Request tracing header | Keep the published `X-Correlation-Id` as the id a member quotes to support, and add W3C `traceparent` for propagation ([Distributed tracing](#distributed-tracing)). A third, vendor-specific header would add nothing. | Zalando's `X-Flow-ID`. |
| Too-old app | Answer `426` with `APP_UPDATE_REQUIRED` and no `Upgrade` header (#576). The remedy is a new app build, and 426 tells any client that retrying will not help. | RFC 9110's 426 with an `Upgrade` header naming a protocol, or a `400`. |
| Throttling | Use `429` and `Retry-After` seconds. The existing limiter exposes the retry delay and does not advertise a general rate-budget contract. | Also publishing the recommended `X-RateLimit-*` counters. |
| Optional response fields | Response records publish nullable properties with their keys present so clients have one stable shape across responses. The hand-built problem schema omits optional members when unset. | Treating absent keys and explicit nulls as interchangeable on responses. |

[API conventions](../../api/conventions.md#which-collections-are-paged) lists the paginated
operations. #473 converted historical collections and #570 converted the remaining collections.
Only two creates send `Location` today ([Response headers](#response-headers-us-29-09-455));
[#459](https://github.com/hugiFreezy0815/TrackMyWealth/issues/459) adds it to every 201.

## Follow-up work

### Collection paging

[Issue #570](https://github.com/hugiFreezy0815/TrackMyWealth/issues/570) resolved the eight
remaining array responses with offset paging. Workspace membership has no domain cap and is
retrievable beyond 200 rows. Category traversal retains the hierarchy across pages, and detection
applies its ranking before slicing the matches. Each order ends with a unique ID tie-breaker.

The approved breaking change uses the existing operation URLs. There is no running production
version supporting the previous response shape. The [migration instructions](../../api/conventions.md#migrating-collection-consumers-570)
describe the supported builds and the request and response changes.

`ApiConventionsDocumentTest` verifies the guide's paged-operation and sort-field table against
OpenAPI and checks that collection responses follow the documented contract.

### Category request schema

`CreateCategoryRequest.parentId` and `UpdateCategoryRequest.parentId` are optional and nullable
in OpenAPI and the generated mobile types. Omission or an explicit JSON null selects the top
level for a workspace category, and a UUID selects a parent. For a shipped category, omission,
null or its current parent UUID retains its position. A different parent is rejected.

Declare request nullability where the API explicitly supports JSON null as part of the
operation's behavior. Apply this to the two category requests (#577) and review other request
fields individually. Bean validation still determines whether each property is required.

## Lifecycle policies

### Idempotent creates

[Issue #574](https://github.com/hugiFreezy0815/TrackMyWealth/issues/574) implements this
policy for the statement upload, and
[#629](https://github.com/hugiFreezy0815/TrackMyWealth/issues/629) for the remaining creating
POSTs, except three recorded as exempt. `docs/api/conventions.md` lists the operations that
support it and the exemptions with their reasons.

The approved follow-up starts with statement upload and then covers creating POST operations.
For a supported operation, the client sends an `Idempotency-Key` containing a fresh UUID for
each logical create, using the same UUID for each retry. The server scopes the key to the user
and workspace, associates it with the operation and request fingerprint, and keeps the result
for 24 hours. The same key and matching request replay the stored response without creating
another resource. Reusing the key with a different request returns 422. A matching request
still in flight returns 409. PUT and DELETE keep their HTTP idempotency semantics.

The
[IETF Idempotency-Key draft](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/)
is the design reference. Row deduplication alone was rejected because it cannot prevent duplicate
import batches after a lost upload response. Clients can rely on the header only once the
operation publishes support in OpenAPI.

### Deprecation and removal

[Issue #576](https://github.com/hugiFreezy0815/TrackMyWealth/issues/576) implements this
policy. [API conventions](../../api/conventions.md#app-version-and-deprecations) has the client
and operator instructions.

Mark deprecated operations or fields with `deprecated: true` in OpenAPI. Affected responses carry
`Deprecation` ([RFC 9745](https://www.rfc-editor.org/rfc/rfc9745)) with the announced
deprecation date and a `Link` with `rel="deprecation"` to migration instructions. Publish the
removal date in `Sunset` ([RFC 8594](https://www.rfc-editor.org/rfc/rfc8594)) once it can be
committed to.

**Implementation (#576).**

- **One annotation, `@ApiDeprecation(since, migration, sunset)`,** on a handler method (the
  operation) or a request or response record component (the field). The contract, the headers
  and the counter all derive from it, so they cannot drift. `Deprecations` resolves the
  annotations that affect an operation through its body types, including generics such as
  `PageResponse<T>`, lists and nested records. Several annotations merge to the earliest
  `since`, the earliest `sunset` and every migration note. Rejected: springdoc's
  `@Schema(deprecated = true)` and Java's `@Deprecated`, which carry no date or note, and a
  hand-kept list of deprecated paths next to the code.
- **A deprecated field announces itself on its whole operation.** RFC 9745 deprecates a resource,
  not part of a body, so `Deprecation` on an operation whose body has a deprecated field
  stretches its meaning. Clients only see the headers per response, though, and the contract
  names the field. Only a body field can be deprecated: a query or form parameter is not a schema
  property the contract could mark, so the operation is deprecated instead. The contract's
  generation fails for a deprecated component outside a body and for a body type it cannot follow
  (raw, `?` or `Object`), so no deprecation is left out silently.
- **The counter keeps at most 100 app versions apart and counts the rest as `other`.** The version
  comes from the client, so the tag values must be bounded. Dropping calls beyond the cap would
  undercount, and an operator could then wrongly conclude that no app uses the element any more.
  With `other`, the totals stay complete.
- **Headers from the marking on, not from the date.** `Deprecation` is sent as soon as the element
  is marked, with its date, which may lie in the future (RFC 9745 allows this). The contract
  shows `deprecated: true` from the same release, so the headers and the contract agree.
  Rejected: holding the header back until the date, which needs a clock in the interceptor and
  hides the announcement exactly while clients could migrate.
- **Set before the handler runs** (`DeprecationHeaders`, a `HandlerInterceptor`), so error
  responses carry them too. The contract documents them on success responses only, because
  error responses refer to the shared problem response.
- **Usage by app version.** Each call is counted in `tmw.api.deprecated.requests` by
  `operationId` and `X-App-Version`. Its version tag is capped at 100 values because the
  client sends it. `metrics` is exposed on the actuator, for administrators only like every
  endpoint except health and info. This lets an operator check "every supported app version
  has migrated" against real traffic.

**Minimum app version (#576).**

- **Header `X-App-Version: MAJOR.MINOR.PATCH`** from the app's `app.json`, on every request.
  Rejected: `User-Agent`, which a browser does not let the web build set.
- **A request without the header is served** (PO decision on #576). The minimum protects only
  against an app that is too old. Scripts, `curl` and `scripts/seed_demo.py` are not apps, and
  no native build has been distributed without the header. A malformed header is
  `400 VALIDATION_FAILED`.
- **`426 Upgrade Required` with `APP_UPDATE_REQUIRED`** (reaction `UPDATE_APP`), answered by
  `MinimumAppVersionFilter` in the security chain after CORS and before the rate limiter and the
  token check. Any client that does not know the code still sees a status that retrying does
  not fix. This deviates from RFC 9110, which expects an `Upgrade` header naming a protocol; the
  remedy here is a newer app, so none is sent. Rejected: `400`, which the catalogue reads as
  "fix the input".
- **Configured by the operator:** `MINIMUM_APP_VERSION`, empty by default, so every version is
  served. A malformed value stops the backend from starting instead of silently disabling the
  check.
- **The app** replaces every screen with an update screen on any 426. The screen keeps the stored
  session. On native it offers to try again, and on web it reloads the page, which loads the
  current build.

**Minimum notice.** The notice period is the supported-client window, not a fixed number of
days: removal can occur only after every supported app version has migrated and the announced
sunset date has passed. This is the recommendation of 2026-11-05 on #371, which the product owner
accepted as written. A fixed period in days is not used because the backend is self-hosted and
the app comes from the store: a calendar date says nothing about which app versions still call
the element. The condition can only be verified once a minimum supported app version is
enforced, which is part of #576.

Removal still follows the `api-breaking` process. Immediate removal or relying on an OpenAPI
marker alone leaves released clients without a migration window.

### Distributed tracing

[Issue #575](https://github.com/hugiFreezy0815/TrackMyWealth/issues/575) implements this
policy.

The approved follow-up accepts and propagates
[W3C Trace Context](https://www.w3.org/TR/trace-context/) `traceparent` using Spring Boot
Micrometer Tracing and records the trace ID in logs. Retain the existing validated
`X-Correlation-Id` as the client-visible support reference. Client-supplied identifiers never
influence authorization.
Using only the custom header would lose standard trace propagation, and replacing it outright
would break the existing support reference.

## Response fields (US-29-07, #451)

### Context

The committed `api/openapi.yaml` (#372) is what client code is generated from (#375). Until #451
no response schema listed a property as `required`, so a generator would make every response field
optional (`name?: string`), and the app would have to check for a missing key that the backend
always sends. The document also did not say which fields can be `null`.

The backend does say this:

- Jackson writes every component of a response record, and a null one as `null`. Nothing in the
  backend sets `@JsonInclude`, so every key is always present.
- In OpenAPI 3.1, `required` means only that the key is present. Whether its value may be null is a
  separate question: `type: [string, "null"]`.

### Decision

**Presence.** Every property of a response schema is `required`.

**Nullability: JSpecify `@Nullable` on the record component.** A response record component that can
be null is declared `org.jspecify.annotations.Nullable`. Without it, the component is never null.
This is Spring 7's own null-safety convention and is already on the classpath. Rejected:
`@Schema(types = {"string", "null"})` per component. It is OpenAPI-specific, so the code itself
would not say that the value can be null.

The contract declares an `@Nullable` component nullable:

- `type: [<type>, "null"]`, with `null` added to an enum's values;
- `anyOf` with `null` for a reference to another schema.

**Generated, never hand-edited.** `config.RecordSchemaMarker` (a swagger `ModelConverter`) marks each
schema with the record it is generated from. `config.ResponseFieldContract` (an `OpenApiCustomizer`)
then sets `required` and nullability from the record components. A response schema that has no
record behind it fails the document's generation. The hand-built `ApiProblem` schema declares its
own required fields: `title`, `status` and `code`. Its optional fields are omitted when unset.

**Response field processing leaves request schemas untouched.** Their `required` list is what
bean validation demands of the caller. Seven import-template parts are used by requests and responses alike
(`ImportColumnMapping`, `ImportPdfLayout`, ...). The document splits each of them: requests keep
the schema, and responses refer to a copy named `<name>Response`. The Java records stay shared.
Rejected: leaving shared schemas without `required`, which would leave seven response schemas
all-optional.

**Checked at runtime in the tests.** In every Spring test context, `ResponseNullabilityAdvice`
looks at each documented operation's response body before it is written. A `null` in a component
not marked `@Nullable` makes `DocumentedSuccessStatusFilter` answer 500 naming
`Record.component`, so the test making the request fails. Rejected: a static rule, for example
"every reference-typed component is annotated one way or the other". It cannot see what the backend
really returns.

### Consequences

- Generated client types are exact: `string` for a field that is always filled, `string | null` for
  a nullable one, and no optional response fields.
- Removing a response field is breaking by oasdiff's own rule (`response-required-property-removed`),
  also behind a nullable reference (`anyOf`). `scripts/api_breaking.py` still raises
  `response-optional-property-removed` to an error, for the optional fields of the hand-built
  `ApiProblem`.
- Making a field nullable later is a breaking change (`response-property-became-nullable`) and needs
  the `api-breaking` label and a note. A new response field is additive.
- A new response record component that can be null must be marked `@Nullable` in the same PR. The
  test suite fails on it as soon as any test sees the null.
- "Always present" holds only while Jackson writes nulls. `ResponseFieldContract` fails the
  document for a response record that sets `@JsonInclude` (other than `ALWAYS`) or has an
  `Optional` component, and `OpenApiContractTest` checks that the application's mapper writes a
  null component as `null`, so a global `default-property-inclusion` cannot change it unseen.
- The runtime check sees only what the tests exercise. A null on a path that no test reaches goes
  unnoticed until a test covers it. It also checks one way only: a component marked `@Nullable`
  that is never null is not reported. That is safe (the generated type is wider than needed), so
  remove such a mark when it is found.

## Response headers (US-29-09, #455)

### Context

Handlers send two headers that clients need. `ETag` carries the resource version for `If-Match`
(ADR 0004, #172), and `Location` gives the URL of a created resource. springdoc cannot see headers
set on a `ResponseEntity`. So until #455 the contract named `If-Match` on every change but never
said where its value comes from, and generated clients had no typed access to either header.

### Decision

**Declared on the handler: `@SendsHeaders`.** A handler lists the headers it sends on success, for
example `@SendsHeaders({ResponseHeader.ETAG, ResponseHeader.LOCATION})`. This follows the pattern of
`@SuccessStatus`. Rejected: reading the headers from the bytecode in main code. `/api-docs` is
served by the running application, so the documentation step would need a second call-graph walker
on Spring's ASM at startup. The ArchUnit walk is test code and cannot serve it.

**Documented as shared components.** `components.headers.ETag` and `components.headers.Location`
are both `required`. Each success response that carries a header refers to it by `$ref`:

- `ETag`: the declared 200 and 201, the two statuses `VersionedResponse` answers. Its description
  names `If-Match`, and its schema is a quoted version number (`pattern: '^"[0-9]+"$'`).
- `Location`: the 201 only, `format: uri-reference`.

Which statuses a header comes with is a property of the header (`ResponseHeader#comesWith`), not
of the handler. A handler that also answered, say, a 202 without an ETag would need a per-status
declaration; none does today, and the per-request check below fails one that starts to.

The annotation is `@SendsHeaders` with the values of the enum `ResponseHeader`, not the
`web.ResponseHeaders` named in the PO comment on #455: the plural annotation next to the singular
enum read as one type, and `@SendsHeaders` says what the handler does, as `@SuccessStatus` does.

**Checked both ways, as the statuses are.**

- `ArchitectureTest` (`api_operations_send_their_documented_headers`) reuses the bytecode walk of
  the success statuses: the handler, its lambdas and the helpers of its own class. It fails a
  handler that sends a header it does not declare, and one that declares a header it never sends or
  that none of its success statuses carries. It recognises `VersionedResponse`, `eTag(..)`,
  `ResponseEntity.created(uri)`, `location(..)`, `HttpHeaders.setETag` and
  `HttpHeaders.setLocation`.
- In every test, `DocumentedSuccessStatusFilter` fails a success response that lacks a declared
  header or carries an undeclared one. This also catches a header set by name, which the bytecode
  rule cannot see because javac inlines the constant.

**Only where sent.** Today 71 operations send `ETag`. Two send `Location`: creating an import
template and uploading an import batch. The other 201s (`VersionedResponse.created`) send no
`Location`, so the contract does not claim one. Sending it on every 201 is a separate story.

### Consequences

- Generated clients read the version for `If-Match` and the new resource's URL as typed headers.
- Adding a header to a response is additive. Removing one, or making it optional, is breaking.
- A new handler that sends either header must declare it in the same PR, or the build fails and
  names the handler.

## Enum values (US-29-08, #452)

### Context

The backend is self-hosted (for example on a NAS) and the app comes from the store, so they update
separately, and either one can be newer. About 28 response enums exist, for example `valueStatus`
and `portfolioType`. The generated types (#375) make each one a string union. TypeScript checks
that union at compile time, but at runtime an older app still receives a value a newer backend
added. A `switch` or a lookup map without a default then renders nothing or throws. oasdiff rates
a new response enum value a warning, so until #452 every such addition needed the `api-breaking`
declaration, although adding a value is the normal way these enums grow.

### Decision

**Clients tolerate unknown response values.** The app shows a response enum through `labelFor`
(`mobile/src/api/enum-label.ts`). A value it does not know gets a neutral label ("Other" /
"Sonstiges"), is never treated as one of the known values, and is logged once in development,
never in production. `DisplayEnum<K>` widens the generated union where a value is displayed, so
the screen has to handle the unknown case, and the known values stay checked. ESLint enforces it
in app code (`mobile/eslint.config.js`): every `switch` has a `default`, that `default` does not
throw, and a `*_LABELS`, `*_ICONS` or `*_COLORS` map is read through `labelFor`, never indexed.

**A new response value is additive.** `scripts/api_breaking.py` keeps oasdiff's warning for
`response-property-enum-value-added` and lets a report pass that contains only that warning. The
change stays listed in the job log. Rejected: lowering the gate to fail on errors only, which
would let every other oasdiff warning pass unseen too.

**Request enums and removed values stay breaking.** Adding a value to a request enum, in a body
property or a parameter, is raised to an error. An app built against the newer value would send it
to an older backend, and that backend rejects it. Removing a response value is an error too, since
a screen built around it loses its meaning.

### Consequences

- A PR may add a response enum value without a declaration. The backend may send it as soon as the
  PR is merged, and older apps show the neutral label for it.
- A screen that needs a value's own label, icon or colour gets it in an app release. Until then the
  neutral label shows.
- A new request enum value, or a removed response value, needs the `api-breaking` label and a note.
- Lint sees display maps only by their name. A map named differently and indexed directly is not
  reported, so name such maps `*_LABELS`, `*_ICONS` or `*_COLORS`.
