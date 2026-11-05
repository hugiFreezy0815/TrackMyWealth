# API conventions

This page documents the **paging contract** implemented in
[US-29-03 (#373)](https://github.com/hugiFreezy0815/TrackMyWealth/issues/373).
The other conventions (resource naming, errors, concurrency, decimals, dates, uploads and
authentication) belong to [US-29-01 (#371)](https://github.com/hugiFreezy0815/TrackMyWealth/issues/371),
which will extend this page. Until then, the committed [OpenAPI contract](../../api/openapi.yaml),
the [error codes](error-codes.md) and the
[development standards](../architecture/development-standards.md) are the reference for those
topics. The paging design itself is recorded in
[ADR 0006](../architecture/adr/0006-api-contract-conventions.md). Do not infer rules for other
endpoints from this section.

## Paging

### Which collections are paged?

Large collections expose `page`, `size` and `sort` query parameters and return the same
`PageResponse<T>` envelope; the six paged operations are listed under [Sorting](#sorting). The
committed OpenAPI contract identifies which operation is paged and defines its response schema.

Every other collection is a **bounded list**: it returns an ordinary JSON array, ignores `page`,
`size` and `sort`, and is always complete. Its upper bound comes from the data it holds, not
from a server cap, except where the table says so:

| Bounded list | What bounds it | Server cap |
| --- | --- | --- |
| `GET /api/v1/accounts` | The workspace's accounts: tens, not thousands | None |
| `GET /api/v1/portfolios` | The workspace's active portfolios: a handful | None |
| `GET /api/v1/categories` | The workspace's category tree, at most 3 levels deep | None |
| `GET /api/v1/categorization-rules` | The workspace's own rules, maintained by hand | None |
| `GET /api/v1/import-templates` | Shipped templates plus the workspace's own, current versions only | None |
| `GET /api/v1/workspace-members` | Members of the workspace: 1 in the MVP | None |
| `GET /api/v1/sessions` | The caller's own active sessions | None |
| `GET /api/v1/accounts/{accountId}/transactions/deleted` | Removals of the last 30 days that can still be restored | None |
| `GET /api/v1/settlement-matches` | Matches of the requested `status` (default `PROPOSED`), newest first | **200** |
| `GET /api/v1/accounts/{accountId}/snapshots` | Manually recorded snapshots of one account | None |
| `GET /api/v1/accounts/{accountId}/valuations` | Manually recorded valuations of one asset | None |
| `GET /api/v1/accounts/{accountId}/repayments` | Recorded repayments of one loan, about one a month | None |
| `GET /api/v1/accounts/{accountId}/amortisation-schedule` | One loan's schedule, one entry per instalment | None |

The two `POST` operations that answer with an array are bounded the same way:
`POST /api/v1/import-templates/detect` returns at most one candidate per available template, and
`POST /api/v1/accounts/{cardAccountId}/settlement-matches/run` returns the matches of one card.

**Rule:** a collection stays a plain array only when the domain bounds it. Any collection that
grows over time is paged, and a plain array is never cut at a server cap, because a client cannot
tell a cut list from a complete one. This follows Google AIP-158 (list methods are paged from
the start, since adding paging later breaks clients) and the Zalando guidelines.

**Known exceptions**, to be paged by
[US-29-13 (#473)](https://github.com/hugiFreezy0815/TrackMyWealth/issues/473):

- Settlement matches are cut at the newest 200. Until #473, a client showing exactly 200 matches
  must not present them as all of them.
- Snapshots, valuations, repayments and the amortisation schedule grow with the age of an
  account and have no cap.

Do not build list screens on these five arrays; #473 changes their response to `PageResponse`.

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

### Sorting

Each paged endpoint declares a whitelist of **response field names**. Use `sort=<field>`,
`sort=<field>,asc` or `sort=<field>,desc`, with lower-case direction and no extra spaces.
Omitting the direction means ascending; omitting `sort` entirely uses the endpoint default.
Multiple `sort` parameters are allowed and applied in request order, but each field may appear
only once. The field names and spellings must match the published OpenAPI `sort` parameter enum.

| Paged operation | Allowed `sort` fields | Default | Unique tie-breaker |
| --- | --- | --- | --- |
| `GET /api/v1/accounts/{accountId}/transactions` | `bookingDate`, `amount` | `bookingDate,desc` | `id` |
| `GET /api/v1/transactions` | `bookingDate`, `amount` | `bookingDate,desc` | `id` |
| `GET /api/v1/accounts/{accountId}/imports` | `uploadedAt` | `uploadedAt,desc` | `id` |
| `GET /api/v1/accounts/{accountId}/imports/{batchId}/rows` | `rowNumber` | `rowNumber,asc` | `rowNumber` |
| `GET /api/v1/accounts/{accountId}/reconciliations` | `createdAt` | `createdAt,desc` | `id` |
| `GET /api/v1/institutions/catalogue` | `name` | `name,asc` | `id` |

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
