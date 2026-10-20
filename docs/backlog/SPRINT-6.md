# Sprint 6 - A backend a frontend developer can build against

Sprints are the GitHub label `sprint-6` plus Iteration 6 (2026-10-27 to 2026-11-09) on Project #1.
This file is the overview and the proposal for the product owner. The stories below are drafts:
they become issues (full section-40 template, layout of bug #153) once the PO approves the scope.

**Goal:** a frontend developer can build the app's main screens from the published API contract
alone, with typed client code, a running stack full of synthetic data, and without reading Java.
At the end of the sprint the app shows the net worth, the accounts and an account's transactions
from a real backend, built on generated types.

## Starting point (2026-10-20)

Sprint 5 is closed with no open issues. The three issues labelled `sprint-6` in October (#269,
#270, #277) were pulled forward and are done. Latest migration is V107; the next free one is V108.

The domain backend is far ahead of the app. What blocks parallel frontend work is the API's shape,
not missing business logic:

| Finding | Effect on a frontend developer |
|---|---|
| There is no collection endpoint for accounts, the workspace's institutions, workspace members or sharing grants. Only "get one by id" and "create" exist. | The app cannot build its navigation: there is no way to find out which accounts exist. |
| Transactions are only listed per account. | No "all transactions", no search, no uncategorised inbox across accounts. |
| Paged endpoints return Spring Data's `Page` serialised as is. | The JSON envelope is a framework detail, not a decision, and can change with a Spring upgrade. |
| Controller methods share generic names such as `get`, `list`, `create`, `update`, `delete` across many controllers. | springdoc numbers the clashes (`get_1`, `get_2`) by scan order, so generated client function names change between builds. |
| No `@Operation`/`@Tag` documentation and no operation tags. | The Swagger UI is one long, unlabelled list of 119 operations. |
| The OpenAPI document exists only at runtime, behind sign-in. Nothing in CI notices a contract change. | A frontend developer learns about a breaking change when the app breaks. |
| The app's client types are hand-written (`mobile/src/api/client.ts`). | Every new screen copies DTOs by hand and drifts from the backend. |
| No demo data. A fresh stack is an empty workspace after first-run setup. | Every frontend developer rebuilds test data by hand; real import samples must never be used. |

What is already good and stays: the RFC 9457 error envelope with a `code`, decimals as strings,
`If-Match` optimistic concurrency, CORS for the web build, the correlation-id header, and
`ApiDocumentationConfig`, which already teaches springdoc these conventions.

## Architecture decisions proposed for this sprint (PO to confirm)

1. **Code-first contract with a committed spec.** The Spring controllers stay the source of truth.
   The build writes `api/openapi.yaml`, the file is committed, and CI fails when the committed file
   differs from what the code generates. Contract-first (hand-written YAML, generated server stubs)
   was considered and rejected: 119 operations already exist and springdoc is tuned to the wire
   conventions.
2. **Additive changes only inside `/api/v1`.** CI runs a breaking-change diff (oasdiff) of the
   PR's spec against `main`. A breaking change needs the label `api-breaking` and a note in the
   PR; this sprint's page-envelope change (decision 3) is the one planned exception, made now
   while the app has no paged screens.
3. **One page envelope, decided once.** Every paged endpoint returns
   `{ "content": [...], "page": { "number", "size", "totalElements", "totalPages" } }`
   (Spring Data's `PagedModel` via `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)`).
   Offset paging stays; cursor paging is not needed at the expected data volumes.
4. **Stable operation ids.** Every operation gets an explicit, unique `operationId`
   (`listAccounts`, `getAccount`, ...), and every controller a tag. An ArchUnit or contract test
   fails the build on a missing or duplicated id.
5. **Types for the app are generated, the transport stays ours.** `openapi-typescript` generates
   `mobile/src/api/schema.d.ts` from the committed spec; `openapi-fetch` (or the existing wrapper,
   typed with the generated paths) does the calls, so token refresh, timeouts and `If-Match` from
   `client.ts` are kept. Decimal fields (`format: decimal`) map to `DecimalString`.
6. **Collection endpoints respect the existing authorization.** A list returns only what the
   caller may VIEW (row-level security plus the sharing grants), exactly like "get one". A foreign
   id in a filter behaves like an unknown one (no existence leak, as in
   `ApiConventionsIntegrationTest`).
7. **Demo data is synthetic and goes through the public API.** A seed script creates it through
   HTTP calls, never with SQL, so it also works as an end-to-end smoke test of the contract. It
   never reads `docs/import-samples/`.

These are recorded as ADR 0006 "API contract and client generation" by US-29-01.

## Committed

Capacity assumption: one backend track (the usual agent sessions) and one frontend developer,
for the full 10 working days. Sizes: S up to 1 day, M 2-3 days, L 4-5 days.

### Track A - Contract and tooling (backend, week 1, blocks everything else)

| Draft id | Story | Pri | Size | Blocked by |
|---|---|---|---|---|
| US-29-01 | ADR 0006 and `docs/api/conventions.md`: naming, collections, paging, filters, sorting, errors, concurrency, decimals, dates, uploads, retryable 503 | MUST | S | - |
| US-29-02 | Publish the contract: explicit unique `operationId` and a tag for every operation, `api/openapi.yaml` generated by the build and committed, CI drift check and oasdiff breaking-change check | MUST | M | US-29-01 |
| US-29-03 | One page envelope for every paged endpoint (decision 3), migrating transactions, import batches, reconciliations and the institution catalogue | MUST | S | US-29-01 |
| US-29-04 | Error-code catalogue: every problem `code` the API can return listed in the spec and in `docs/api/error-codes.md`, with the message keys the app translates (EN/DE) | SHOULD | S | US-29-02 |

### Track B - The read endpoints screens need (backend, from day 3)

| Draft id | Story | Pri | Size | Blocked by |
|---|---|---|---|---|
| US-05-06 | `GET /api/v1/accounts`: the accounts the member may view, filterable by institution, account type, owner and archived; each row carries currency, `valueKnown` and the current balance in account and workspace currency | MUST | M | US-29-03 |
| US-04-05 | `GET /api/v1/institutions`: the workspace's institutions with account count and total, for the institution list | MUST | S | US-29-03 |
| US-03-05 | `GET` collections for workspace members and sharing grants (the settings screens) | SHOULD | S | US-29-03 |
| US-07-35 | `GET /api/v1/transactions`: workspace-wide list over every viewable account, filtered by accounts, date range, category, uncategorised, free text and amount range; newest first; paged | MUST | L | US-29-03 |

### Track C - Frontend developer experience (backend, parallel to B)

| Draft id | Story | Pri | Size | Blocked by |
|---|---|---|---|---|
| US-01-06 | Demo workspace: `scripts/seed_demo.py` builds a synthetic workspace through the API (two invented institutions, CHF and EUR current accounts, a card, a savings account, a mortgage, about six months of categorised transactions, one import batch, a snapshot), idempotent, documented test login incl. its TOTP secret for local use only; CI runs it against the compose stack | MUST | M | US-05-06 (for its own verification) |
| US-01-07 | `docs/frontend/README.md`: run the stack, sign in and refresh, call the API with generated types, decimals, `If-Match`, errors, paging, and a screen-to-endpoint map for the main screens | MUST | S | US-29-02 |

### Track D - Frontend slice that proves the contract (frontend developer)

| Draft id | Story | Pri | Size | Blocked by |
|---|---|---|---|---|
| US-29-05 | Generated client in `mobile/`: `npm run api:types` generates the types from `api/openapi.yaml`, CI fails when they are stale, the existing auth, reference-data and account-proposal code moves to them | MUST | M | US-29-02 |
| US-11-05 | App home: net worth with its warnings and the account list grouped by institution, with loading, empty and error states (iOS, Android, web) | MUST | M | US-29-05, US-05-06, US-04-05 |
| US-07-36 | App account detail: an account's transactions, paged, with the uncategorised filter and the "assign category" action (`If-Match`) | SHOULD | M | US-29-05, US-29-03 |

The frontend developer starts on day 1 without waiting for the backend: US-29-05 can begin
against the runtime `/api-docs` output and switches to the committed file once US-29-02 merges.
Screens can be laid out against the drafted responses from US-29-01 and wired up as the endpoints
land.

## Suggested order

- **Days 1-2:** US-29-01 (decisions on paper), then US-29-02 and US-29-03. These touch every
  controller, so other backend PRs wait for them or rebase right after. Frontend: US-29-05 and the
  home screen's layout.
- **Days 3-6:** US-05-06 and US-04-05 first (they unblock the home screen), then US-07-35. In
  parallel US-01-06 and US-01-07. Frontend: US-11-05 against the real endpoints.
- **Days 7-10:** US-03-05, US-29-04, finishing US-07-35. Frontend: US-07-36, then a contract
  review: every place the frontend developer had to guess becomes a doc fix or a follow-up story.

## Stretch (priority order, unlabelled until pulled in)

1. #363 [SPIKE] cross-format duplicate detection (priority-must for EPIC 07, the PO rule "nothing
   imported twice"). First in line if the backend track has room; it does not touch the contract.
2. Mock server: `npm run api:mock` (Prism on `api/openapi.yaml`) so the app runs without a backend.
   Needs response examples in the spec.
3. US-11-02 historical net worth endpoint (the home screen's chart), once the home screen exists.
4. #364 / #365 import entry point improvements, as the first import screens' backend.

## Not in this sprint, and why

- **#367 and #265** (holdings import and bank-sync screens): import flows are the hardest screens;
  they follow once the contract, the client and the simple screens are proven.
- **EPIC 24 bank APIs (#259-#264):** still waiting for the FinTS product registration.
- **#233 opening holdings:** needs EPIC 15.
- **#357-#361, #274, #276:** import refinements; no frontend dependency, keep for sprint 7.

## Definition of Done additions for this sprint

- A PR that changes an endpoint commits the regenerated `api/openapi.yaml`; CI checks it.
- A new operation has an `operationId`, a tag, a one-line summary and its error codes.
- A new list endpoint is paged with the agreed envelope, or explains in its docs why not.
- No real sample data in seeds, fixtures, screenshots or docs (see SPRINT-5.md).

## Risks

- **US-29-02 and US-29-03 touch every controller.** Merge them early and fast; announce a short
  freeze on controller changes while they are open.
- **The page envelope is a breaking change.** Only backend tests read the old shape today; the app
  has no paged screen yet. After this sprint it would cost real migration work.
- **US-07-35 performance.** A workspace-wide filtered list needs the right indexes (account, date,
  category) and an explain-plan check against the demo data plus a generated large data set.
- **One frontend developer:** if they start later, Track D slips but Tracks A-C still leave the
  backend ready for them.

## Decisions needed from the PO

1. Approve decisions 1-7 above, in particular the page-envelope break.
2. Approve the committed scope, then the stories get filed with the `sprint-6` label and
   Iteration 6.
3. Confirm the demo login may ship with a documented TOTP secret, active only with the `dev`
   profile or the seed script, never in the default configuration.
