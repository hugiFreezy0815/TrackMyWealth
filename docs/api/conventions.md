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
`PageResponse<T>` envelope; the twelve paged operations are listed under [Sorting](#sorting). The
committed OpenAPI contract identifies which operation is paged and defines its response schema.

Every other collection returns an ordinary JSON array and ignores `page`, `size` and `sort`.
These are **bounded lists**: always complete, with an upper bound from their domain rather than a server cap.

| Bounded list | What bounds it |
| --- | --- |
| `GET /api/v1/accounts` | The workspace's accounts: tens, not thousands |
| `GET /api/v1/portfolios` | The workspace's active portfolios: a handful |
| `GET /api/v1/categories` | The workspace's category tree, at most 3 levels deep |
| `GET /api/v1/categorization-rules` | The workspace's own rules, maintained by hand |
| `GET /api/v1/import-templates` | Shipped templates plus the workspace's own, current versions only |
| `GET /api/v1/workspace-members` | Members of the workspace: 1 in the MVP |
| `GET /api/v1/sessions` | The caller's own active sessions |
| `POST /api/v1/import-templates/detect` | At most one candidate per available template |

**Rule:** a collection stays a plain array only when the domain bounds it. Any collection that
grows over time is paged, and a plain array is never cut at a server cap, because a client cannot
tell a cut list from a complete one. This follows Google AIP-158 (list methods are paged from
the start, since adding paging later breaks clients) and the Zalando guidelines.

#### Pagination of formerly unbounded lists (#473)

Six historical collections now return `PageResponse<T>` instead of arrays. The settlement-match
queue is no longer cut at 200; filter by `status` (default `PROPOSED`), optionally `kind`
and `cardAccountId`, then follow `page.totalPages` to read the entire visible result.
After confirming, rejecting, importing or rerunning matching, refresh from page 0.

`POST /api/v1/accounts/{cardAccountId}/settlement-matches/run` is an action, **not a paged
list**. It returns `{ "newlyProposed": 0 }` (the number of proposals inserted by this run),
never historical matches. Read newly found or confirmed matches through the paged GET endpoint.

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

Each paged endpoint except the deleted-transactions restore list declares a whitelist of **response field names**. Use `sort=<field>`,
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
| `GET /api/v1/accounts/{accountId}/snapshots` | `snapshotDate` | `snapshotDate,desc` | `id` |
| `GET /api/v1/accounts/{accountId}/valuations` | `valuationDate` | `valuationDate,desc` | `id` |
| `GET /api/v1/accounts/{accountId}/repayments` | `bookingDate` | `bookingDate,desc` | `id` |
| `GET /api/v1/accounts/{accountId}/amortisation-schedule` | `dueDate` | `dueDate,asc` | `id` |
| `GET /api/v1/settlement-matches` | `createdAt` | `createdAt,desc` | `id` |
| `GET /api/v1/accounts/{accountId}/transactions/deleted` | **No client sort** | Most recently removed first: `COALESCE(deleted_at, voided_at) DESC` | `id DESC` |

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
For the deleted-transactions restore list, any supplied `sort` returns `INVALID_SORT`:
the removal timestamp is not exposed as a sortable response field; the SQL order is fixed.

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
