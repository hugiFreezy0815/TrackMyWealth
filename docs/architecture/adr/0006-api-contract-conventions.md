# ADR 0006: API contract, paging and client generation

## Status
Accepted in part. This first version holds only the response-field decision (US-29-07, #451;
product owner decisions of 2026-11-04). US-29-01 (#371) adds the other conventions of the sprint 6
plan (`docs/backlog/SPRINT-6.md`, decisions 1-8): naming, collections, paging, errors,
concurrency.

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
  so `scripts/api_breaking.py` no longer raises `response-optional-property-removed`.
- Making a field nullable later is a breaking change (`response-property-became-nullable`) and needs
  the `api-breaking` label and a note. A new response field is additive.
- A new response record component that can be null must be marked `@Nullable` in the same PR. The
  test suite fails on it as soon as any test sees the null.
- The runtime check sees only what the tests exercise. A null on a path that no test reaches goes
  unnoticed until a test covers it.
