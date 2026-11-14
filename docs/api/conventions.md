# API conventions

Use the committed [OpenAPI 3.1 contract](../../api/openapi.yaml) for operation-specific
paths, parameters, schemas, headers and statuses.
[ADR 0006](../architecture/adr/0006-api-contract-conventions.md) records the design decisions
and deviations from the
[Zalando RESTful API Guidelines](https://opensource.zalando.com/restful-api-guidelines/).
New endpoints that need a deviation require an ADR amendment.

## Resources and requests

- Use `/api/v1`, plural lower-case nouns for collections, and kebab-case for multiword
  segments. Address an item by its ID and nest resources scoped to a parent, for example
  `/api/v1/accounts/{accountId}/transactions/{transactionId}`. Singletons such as
  `/api/v1/workspace` use a singular name. Use the documented action path when a domain
  transition has its own operation, such as `POST /api/v1/accounts/{accountId}/archive`.
  One published path ends in a file name: the CSV download
  `GET /api/v1/accounts/{accountId}/imports/{batchId}/errors.csv`. Do not add another.
- Use GET to read, POST to create or perform a documented action, PUT to replace or set the
  addressed state, and DELETE to remove it, following
  [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110).
- A create answers 201 with the new resource in the body. Only two creates also send `Location`
  today: the statement upload and creating an import template. Read the `id` from the body for
  the others until [#459](https://github.com/hugiFreezy0815/TrackMyWealth/issues/459)
  adds the header to every 201.
- Use lower camel case for JSON properties and query parameter names. Treat IDs as opaque.
  Response properties are present and may be null where the schema allows it. Handle new
  response enum values with a neutral fallback.
- Use stable, explicit OpenAPI operation IDs. Regenerate the mobile types with `npm run api:types`
  from `mobile/` after changing the committed contract.

## Authentication and visibility

Use HTTPS for deployed servers. Send the access token in `Authorization: Bearer <access-token>`
on protected requests. `POST /api/v1/auth/login` returns tokens directly or sets
`mfaRequired: true`. In that case, send the returned `mfaChallengeToken` as `challengeToken`
along with the six-digit `code` to `POST /api/v1/auth/mfa/verify`.

Keep tokens out of URLs, diagnostics and logs.

### Renewing the session

An expired access token has no code of its own: the request answers the generic
`401 UNAUTHENTICATED`. Renew the session with `POST /api/v1/auth/refresh` and the current
`refreshToken`. The answer carries a new access token and a new refresh token; replace both.

Follow these rules, as the app's transport (`mobile/src/api/client.ts`) does:

- On a 401, send one refresh. Requests that fail at the same time share it.
- After a successful refresh, replay each affected request once with the new access token. A
  request whose token another request has already replaced is replayed with the newer token
  without a refresh of its own.
- Never renew for a request to `/api/v1/auth/*`, the refresh request included.

React to the refresh answer as follows:

| Refresh answers | Meaning | Reaction |
| --- | --- | --- |
| 200 | New tokens | Replace both tokens and replay the waiting requests. |
| `401 UNAUTHENTICATED` | The refresh token is expired, reused or revoked. | Clear the session and sign in again. |
| `400 VALIDATION_FAILED` | The refresh token is malformed. | Clear the session and sign in again. |
| `429 RATE_LIMITED`, a 5xx, a timeout or no connection | The server could not answer now. | Keep the session. Wait before the next refresh, for `Retry-After` seconds when the header is sent. |

After a temporary failure, waiting requests get a retryable error and the app keeps the session.
The app uses a five-second delay when `Retry-After` is absent or unreadable. A 401 during this
window gets the same retryable error with the remaining delay and makes no refresh request.
The first 401 at or after the deadline can start a new shared refresh.

Workspace-scoped lists derive their workspace from the authenticated session. Members see all
records in that workspace that match the operation's filters. Personal endpoints, such as
`/api/v1/sessions`, use the caller's own scope. Foreign IDs in filters behave like unknown IDs.
A direct lookup for an inaccessible workspace resource returns the same 404 as a missing one.

## Decimal values, dates and currencies

Send an exact decimal as a plain JSON string, for example `"amount": "1234.56"`. Use a dot
as the decimal separator, without grouping or an exponent. The OpenAPI field is a
`string` with `format: decimal`, mapped to `DecimalString` in mobile (#176).
Some requests accept JSON numbers for compatibility. Preserve the string across round trips
to avoid floating-point rounding. Apply localized formatting at display time.

Send ISO 4217 currency codes such as `CHF` and `EUR`. Use `YYYY-MM-DD` for calendar dates
such as `bookingDate` and keep them as dates when displaying them. Use
[RFC 3339](https://www.rfc-editor.org/rfc/rfc3339) timestamps with an explicit offset,
for example `2026-11-13T09:30:00Z`, for instants. Convert instants to the chosen display
time zone only at the presentation boundary.

## Updating with If-Match

Read the resource's strong `ETag` and send it verbatim in `If-Match` on the documented
read-modify-write operation. When editing from a list item that supplies `version`, form the
tag by surrounding the version with double quotes. Follow
[RFC 9110](https://www.rfc-editor.org/rfc/rfc9110),
[RFC 6585](https://www.rfc-editor.org/rfc/rfc6585) and
[ADR 0004](../architecture/adr/0004-http-optimistic-concurrency.md).

This example renames a top-level workspace category.

```http
PUT /api/v1/categories/{id}
Authorization: Bearer <access-token>
Content-Type: application/json
If-Match: "7"

{"nameEn":"Groceries","nameDe":"Lebensmittel"}
```

On category creation and update, omit `parentId` or send JSON `null` for a top-level
workspace category. Send a UUID to select a parent. For a shipped category, omission, null,
or its current parent UUID keeps its shipped position. A different parent returns 422.

Missing preconditions return `428 VERSION_REQUIRED`. Stale tags return `412 VERSION_CONFLICT`.
A malformed tag returns `400 VALIDATION_FAILED`. On a conflict, reload the resource, let the
member review the new state, and retry with the new tag only after confirming the change. Use
the new response ETag for subsequent edits. An optimistic-lock race on an operation without a
precondition can return `409 VERSION_CONFLICT`.

## Problems, retries and support IDs

Read errors as [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) `application/problem+json`.
Use the stable `code` to choose the client reaction and localized message, and fall back to
the HTTP status for unknown codes. `title` and `status` are required alongside `code`.
A validation failure can include `errors` with a `field` and `message` per rejected input.
`detail` is diagnostic prose. See the [error code catalogue](error-codes.md) for reactions
and `tmw.error.*` message keys.

```json
{
  "title": "Precondition Failed",
  "status": 412,
  "code": "VERSION_CONFLICT",
  "correlationId": "mobile-7f3a9c21"
}
```

The server returns `X-Correlation-Id` on responses and normally copies it to the problem body's
`correlationId`. Quote the assigned ID when reporting an error. You may supply an opaque ID
matching `[A-Za-z0-9._-]{8,64}`; the server generates a UUID when it is absent or invalid.

Three kinds of response can carry `Retry-After`; honor it when present:

- `429 RATE_LIMITED` ([RFC 6585](https://www.rfc-editor.org/rfc/rfc6585)), on any operation;
- a retryable 503 such as `IMPORT_PDF_BUSY`;
- `423 LOCKED` on sign-in (`POST /api/v1/auth/login`, `POST /api/v1/auth/mfa/verify`) and on
  the MFA settings operations under `/api/v1/users/me/mfa/`, while the user account is locked
  after failed sign-ins.

TrackMyWealth emits delay seconds. RFC 9110 also permits an HTTP date. Use the code's
documented reaction when no header is present.

A timeout on a creating POST can leave a resource committed even though the response was lost.
Check the resulting state before resubmitting. ADR 0006 records the future `Idempotency-Key`
contract; [#574](https://github.com/hugiFreezy0815/TrackMyWealth/issues/574) implements it.

## Uploads

Five operations take a file as `multipart/form-data`, each in a part named `file`:

| Operation | Other inputs |
| --- | --- |
| `POST /api/v1/accounts/{accountId}/imports` (statement upload) | Query: `templateId`, `snapshotDate` |
| `POST /api/v1/import-templates/detect` | None |
| `POST /api/v1/import-templates/test` | Part `template`; query: `accountId` |
| `POST /api/v1/import-templates/{id}/test` | Query: `accountId` |
| `POST /api/v1/import-templates/{templateId}/account-proposal` | None |

Let the HTTP client set the content type with its generated multipart boundary. The default
servlet limits are 5 MB per file and 6 MB for the entire request (`IMPORT_MAX_FILE_SIZE` and
`IMPORT_MAX_REQUEST_SIZE`). An oversized request returns `413 IMPORT_FILE_TOO_LARGE`.
Format-specific limits can also reject a file that fits the byte limit. See the import codes in
the [error catalogue](error-codes.md).

## Paging

### Which collections are paged?

Paged collections expose `page` and `size`, with `sort` where supported, and return the same
`PageResponse<T>` envelope; the thirteen paged operations are listed under [Sorting](#sorting).
The committed OpenAPI contract identifies which operation is paged and defines its response
schema.

A collection stays a plain array only when the domain bounds its total size. Document that
bound and return the complete result. A collection that can grow without a domain bound is
paged. This follows the Zalando guidelines and [Google AIP-158](https://google.aip.dev/158).

The rule covers operations whose response body is the collection. An array inside a single
resource or report, such as an account's `positions` or the `accounts` of the net-worth report,
is not assessed here.

The following operations currently return arrays and do not offer paging parameters.

| Operation | Current scope or limit | Follow-up |
| --- | --- | --- |
| `GET /api/v1/accounts` | Workspace accounts. No published hard count bound. | [#570](https://github.com/hugiFreezy0815/TrackMyWealth/issues/570) |
| `GET /api/v1/portfolios` | Workspace portfolios. No published hard count bound. | [#570](https://github.com/hugiFreezy0815/TrackMyWealth/issues/570) |
| `GET /api/v1/categories` | Workspace categories. Depth is capped at three; breadth has no published count bound. | [#570](https://github.com/hugiFreezy0815/TrackMyWealth/issues/570) |
| `GET /api/v1/categorization-rules` | Workspace rules. No published hard count bound. | [#570](https://github.com/hugiFreezy0815/TrackMyWealth/issues/570) |
| `GET /api/v1/import-templates` | Shipped and workspace templates. No published hard count bound. | [#570](https://github.com/hugiFreezy0815/TrackMyWealth/issues/570) |
| `GET /api/v1/workspace-members` | Returns the complete list up to 200 rows. An over-limit workspace gets 422. | [#570](https://github.com/hugiFreezy0815/TrackMyWealth/issues/570) |
| `GET /api/v1/sessions` | The caller's own active sessions. No published hard count bound. | [#570](https://github.com/hugiFreezy0815/TrackMyWealth/issues/570) |
| `POST /api/v1/import-templates/detect` | At most one candidate per available template, with no published bound on the number of templates. | [#570](https://github.com/hugiFreezy0815/TrackMyWealth/issues/570) |

These are current exceptions: each returns the complete result today, but no count bound is
enforced or published. The Sprint 6 plan treated accounts, portfolios and categories as small
bounded lists; [#570](https://github.com/hugiFreezy0815/TrackMyWealth/issues/570)
decides for each of the eight operations between paging and an enforced, documented bound
([ADR 0006](../architecture/adr/0006-api-contract-conventions.md#collection-paging)). Any
migration of their response shapes must follow the breaking-change policy.
[#473](https://github.com/hugiFreezy0815/TrackMyWealth/issues/473) migrated the
historical collections below.

### Pagination of formerly unbounded lists (#473)

Six historical collections now return `PageResponse<T>` instead of arrays. The settlement-match
queue is no longer cut at 200; filter by `status` (default `PROPOSED`), optionally `kind`
and `cardAccountId`, then follow `page.totalPages` to read the entire visible result.
After confirming, rejecting, importing or rerunning matching, refresh from page 0.

`POST /api/v1/accounts/{cardAccountId}/settlement-matches/run` is an action, **not a paged
list**. It answers what this run changed, never the card's match history:
`{ "newlyProposed": 2, "newlyConfirmed": 1 }` counts the proposals it added and the exact,
unambiguous pairs it confirmed itself. Matches that already existed are not counted, so running
it again answers `0` for both. Read the matches through the paged GET endpoint
(`?cardAccountId=…&status=…`).

The server owns the envelope rather than serializing Spring Data's `Page`. An empty first page is:

```json
{
  "content": [],
  "page": {
    "number": 0,
    "size": 50,
    "totalElements": 0,
    "totalPages": 0
  }
}
```

| Field | Meaning |
| --- | --- |
| `content` | Items on this page, in the requested order |
| `page.number` | Zero-based page number requested |
| `page.size` | Effective page size, **not** the number of items in `content` |
| `page.totalElements` | Total matching items visible to the caller, before paging |
| `page.totalPages` | Total number of pages at the effective size |

For example, given 120 matching transactions, `?page=1&size=50` returns the second set of
up to 50 rows and `page: { "number": 1, "size": 50, "totalElements": 120, "totalPages": 3 }`.
An out-of-range page returns an empty `content` with the requested `page.number` and the
unchanged totals. Totals only include data the caller may access.

### Page and size parameters

| Parameter | Accepted values | Default / behavior |
| --- | --- | --- |
| `page` | A whole number at least `0` | `0`; pages are **zero-based** |
| `size` | A whole number at least `1` | `50`; values above `200` are **capped at 200** |
| `sort` | See the per-operation whitelist below | Operation-specific default; repeatable |

A blank `page=` or `size=` is treated as absent. Sending `page` or `size` more than once,
or sending a negative, non-numeric or otherwise invalid value, returns HTTP 400 with
`code: "VALIDATION_FAILED"` in the standard problem response. A large valid size is capped,
not rejected: `?size=1000` applies a size of `200`, which is also reflected in `page.size`.

For example:

```http
GET /api/v1/accounts/{accountId}/transactions?page=1&size=50&sort=bookingDate,desc
```

### Filtering

Use the filter names and multiplicity in the operation's schema. Filter names normally match
response fields, with suffixes for ranges. For example, `bookingDateFrom` and `bookingDateTo`
limit booking dates. Repeat an array-valued filter for any-of matching within that field, such as
`?categoryId=<first-id>&categoryId=<second-id>` on `GET /api/v1/transactions`. Distinct filters
narrow the results together. Operation-specific filters such as `q` and `uncategorized` have
their own documented behavior.

### Sorting

Each paged endpoint except the deleted-transactions restore list declares a whitelist of
**response field names**. Use `sort=<field>`, `sort=<field>,asc` or `sort=<field>,desc`, with
lower-case direction and no extra spaces.
Omitting the direction means ascending; omitting `sort` entirely uses the endpoint default.
Multiple `sort` parameters are allowed and applied in request order, but each field may appear
only once. The field names and spellings must match the published OpenAPI `sort` parameter enum.

| Paged operation | Allowed `sort` fields | Default | Unique tie-breaker |
| --- | --- | --- | --- |
| `GET /api/v1/accounts/{accountId}/transactions` | `bookingDate`, `amount` | `bookingDate,desc` | `id` |
| `GET /api/v1/accounts/{accountId}/transactions/{transactionId}/history` | `occurredAt` | `occurredAt,desc` | event id (internal) |
| `GET /api/v1/transactions` | `bookingDate`, `amount` | `bookingDate,desc` | `id` |
| `GET /api/v1/accounts/{accountId}/imports` | `uploadedAt` | `uploadedAt,desc` | `id` |
| `GET /api/v1/accounts/{accountId}/imports/{batchId}/rows` | `rowNumber` | `rowNumber,asc` | `rowNumber` |
| `GET /api/v1/accounts/{accountId}/reconciliations` | `createdAt` | `createdAt,desc` | `id` |
| `GET /api/v1/institutions/catalogue` | `name` | `name,asc` | `id` |
| `GET /api/v1/accounts/{accountId}/snapshots` | `snapshotDate` | `snapshotDate,desc` | `id` |
| `GET /api/v1/accounts/{accountId}/valuations` | `valuationDate` | `valuationDate,desc` | `id` |
| `GET /api/v1/accounts/{accountId}/repayments` | `bookingDate` | `bookingDate,desc` | `id` |
| `GET /api/v1/accounts/{accountId}/amortisation-schedule` | `dueDate` | `dueDate,asc` | `id` |
| `GET /api/v1/settlement-matches` | `createdAt` | `createdAt,desc` | `id` |
| `GET /api/v1/accounts/{accountId}/transactions/deleted` | **None: fixed order** | Most recently removed first (`deletedAt`, or `voidedAt` for a voided row, descending) | `id`, descending |

`bookingDate` sorts first by booking date and then by creation time before the final `id`
tie-breaker. `amount` sorts by the transaction's value in the **account currency**, not
necessarily the original booking currency. The server always ends the effective order with
a unique tie-breaker, so equal visible sort values have a deterministic order. The tie-breaker
follows the direction of the first requested sort field unless it is itself the sortable field
(`rowNumber` for import rows). Clients do not specify or override that tie-breaker.

Unlike a blank `page=` or `size=`, a blank `sort=` is **not** treated as absent: it names no
field and is refused. To get the default order, leave `sort` out.

An unknown field (including an entity property not present in the response whitelist), duplicate
field, blank, malformed direction or otherwise malformed `sort` returns HTTP 400 with
`code: "INVALID_SORT"`; it is **not silently ignored**. The problem `detail` names the allowed
fields. Clients should handle the stable `code`, not parse the human-readable `detail`.
The deleted-transactions restore list has a **fixed order**: its OpenAPI operation documents no
`sort` parameter, and any `sort` value returns `INVALID_SORT` with a `detail` naming that order.
The order runs over two fields (a deleted row's `deletedAt`, a voided row's `voidedAt`), so no
single response field could be offered as a sort field.

### Offset paging and refresh after imports

**Offset paging is the final contract; there are no cursors.** The page offset is
`page * page.size`. With an unchanged set of matching rows and a fixed sort, the server's unique
tie-breakers make page boundaries deterministic. They cannot prevent rows from moving between
offsets when data is inserted, deleted, or updated between requests.

**When an import adds rows while the user is scrolling a paged list, the client must discard its
loaded pages and reload from page 0.** Do not append the next offset page to the previously loaded
items after such a change: that can duplicate rows or skip them. The same page-0 reset applies
when the user changes filters or sort order, or performs another action known to change the
matching result set. Preserve the chosen filters and sort when restarting. If a request fails,
keep the error visible and allow a retry; do not silently advance to another page.

The client may use `page.totalElements` and `page.totalPages` for navigation, but should treat
them as a snapshot of the individual response, not a promise that subsequent pages will have the
same totals. This is why the refresh rule above is necessary.

### Verification and implementation references

- `backend/src/main/java/com/trackmywealth/backend/dto/PageResponse.java` owns the response.
- `backend/src/main/java/com/trackmywealth/backend/dto/PagingValues.java` owns size limits.
- `backend/src/main/java/com/trackmywealth/backend/web/Paged.java` and
  `PagedArgumentResolver.java` define and enforce page, size and sort parsing.
- `backend/src/main/java/com/trackmywealth/backend/web/SortWhitelist.java` enforces
  the per-endpoint fields and deterministic order.
- `PagingIntegrationTest`, `PagedArgumentResolverTest`, `SortWhitelistTest` and
  `ApiConventionsIntegrationTest` test the behavior and the published schema.

The backend's ArchUnit rules reject `Page` or `Slice` return values in controllers, and a
controller takes paging only as a `@Paged Pageable`: a bare `Pageable`, or any `Sort` parameter
(annotated or not), fails the build. When introducing a new paged
endpoint, declare its whitelist, default sort and tie-breaker, update its OpenAPI contract and
generated app types, and update this table in the same change.
