# ADR 0006: API contract, paging and client generation

## Status
Accepted in part. This version holds the response-field decision (US-29-07, #451), the
response-header decision (US-29-09, #455), both product owner decisions of 2026-11-04, and the
enum-value decision (US-29-08, #452).
US-29-01 (#371) adds the other conventions of the sprint 6 plan (`docs/backlog/SPRINT-6.md`,
decisions 1-8): naming, collections, paging, errors, concurrency.

## Context

The committed `api/openapi.yaml` (#372) is what client code is generated from (#375). Until #451
no response schema listed a property as `required`, so a generator would make every response field
optional (`name?: string`), and the app would have to check for a missing key that the backend
always sends. The document also did not say which fields can be `null`.

The backend does say this:

- Jackson writes every component of a response record, and a null one as `null`. Nothing in the
  backend sets `@JsonInclude`, so every key is always present.
- In OpenAPI 3.1, `required` means only that the key is present. Whether its value may be null is a
  separate question: `type: [string, "null"]`.

## Decision

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
record behind it fails the document's generation. The one exception is a schema built by hand,
which declares its own list: `ApiProblem` requires `title`, `status` and `code`. Its `detail`,
`instance` and `errors` are left out when unset, so they are absent then, not null.

**Request schemas are untouched.** Their `required` list is what bean validation demands of the
caller. Seven import-template parts are used by requests and responses alike
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

## Consequences

- Generated client types are exact: `string` for a field that is always filled, `string | null` for
  a nullable one, and no optional response fields.
- Removing a response field is breaking by oasdiff's own rule (`response-required-property-removed`),
  also behind a nullable reference (`anyOf`). `scripts/api_breaking.py` still raises
  `response-optional-property-removed` to an error, for the optional fields of the hand-built
  `ApiProblem` (`detail`, `instance`, `errors`).
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
