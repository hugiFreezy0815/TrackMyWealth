# Sprint 6 - Everything a frontend developer needs to start in sprint 7

Sprints are the GitHub label `sprint-6` plus Iteration 6 (2026-10-27 to 2026-11-09) on Project #1.
This file is the overview; the issues (#371-#387, #389, #390) hold the full story text, design and Definition of
Done. The PO approved the scope on 2026-10-20. The draft names UX-01..04 and SPRINT-7 used below map
to the issue ids in the tables.

**Goal:** on 2026-11-10 a frontend developer can start building screens without waiting for
anyone. The API contract is published, stable and typed; a stack full of synthetic data runs with
one command; and every screen of the first frontend sprint has an approved specification that says
what it shows, how it looks, and which endpoint feeds it.

Sprint 6 builds no product screens. The first screens (home, portfolios, account detail) are the
frontend developer's sprint 7 stories, written in this sprint.

## Starting point (2026-10-20)

Sprint 5 is closed with no open issues. The three issues labelled `sprint-6` in October (#269,
#270, #277) were pulled forward and are done. Latest migration is V107; the next free one is V108.

### The API

The domain backend is far ahead of the app. What blocks frontend work is the API's shape:

| Finding | Effect on a frontend developer |
|---|---|
| There is no collection endpoint for accounts, the workspace's portfolios or workspace members. Only "get one by id" and "create" exist. | The app cannot build its navigation: there is no way to find out which accounts exist. |
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

### The UI

The specification describes what the app must do and which rules its screens follow, but not what
the screens look like:

| Available | Where |
|---|---|
| Navigation tree: Overview with the workspace tab and one tab per portfolio, Investments, Cash Flow, Budget, Goals, Workspace, Settings | Spec section 27, FR-NAV-01 |
| UX rules: progressive disclosure, consistent terminology, explainable figures, a visual treatment for estimated, stale and unreconciled values, time to first insight under ten minutes | Spec section 32, NFR-UX-001..005 |
| Scope always visible, aggregates within the authorised scope, negative totals shown as negative | FR-CON-006, FR-ALL-009, FR-TEN-011, FR-NAV-15 |
| EN/DE, locale formatting (CHF `1'234.56`, EUR `1.234,56`), WCAG 2.2 AA, screen protection on balance screens | NFR-I18N-001..005, NFR-USE-03, FR-CLI-005 |
| Responses shaped for the screens that consume them, no figures computed on the client | FR-API-010 |

| Missing | Effect |
|---|---|
| No wireframes or screen specifications, no user flows | The developer would design the product while building it, and the PO reviews it only in code. |
| No visual foundation: colours, type, spacing, amount formatting, the estimated/stale treatment | Every screen invents its own; NFR-UX-004 cannot be met consistently. `mobile/src/constants/theme.ts` is the Expo template. |
| No component, chart or table library chosen | The first screens would make that choice implicitly. |
| No EN/DE glossary of the domain terms | NFR-UX-002 and NFR-I18N-005 have nothing to check against. |
| **OPEN-023 is undecided:** Expo web via react-native-web, or a separate React web client. RISK-010 asks for a transaction-list spike first. | This decides the frontend developer's whole web stack and must be answered before they start. |

So the UI is **not** specified enough for a frontend developer to start. This sprint adds a UX
track (Track C) that closes these gaps for the screens of sprint 7.

## Architecture decisions (PO-confirmed 2026-10-20 unless marked)

1. **Code-first contract with a committed spec.** The Spring controllers stay the source of truth.
   The build writes `api/openapi.yaml`, the file is committed, and CI fails when the committed file
   differs from what the code generates.
2. **Additive changes only inside `/api/v1`.** CI runs a breaking-change diff (oasdiff) of the
   PR's spec against `main`. A breaking change needs the label `api-breaking` and a note in the
   PR; this sprint's page-envelope change is the one planned exception.
3. **Paging, final design (PO decision 2026-10-20: implement the final solution now).**
   - Every paged endpoint returns our own `PageResponse<T>` record, not a Spring type:
     `{ "content": [...], "page": { "number": 0, "size": 50, "totalElements": 1234, "totalPages": 25 } }`.
     The shape matches Spring Data's `PagedModel`, but owning the record keeps the contract
     independent of Spring upgrades and appears in the spec as one schema per item type.
   - Query parameters: `page` (zero-based), `size` (default 50, maximum 200, larger values are
     capped), `sort=<field>,<asc|desc>`, repeatable. Each endpoint documents the fields it allows to
     sort by. An unknown sort field is a 400 with code `INVALID_SORT`, never ignored.
   - Every endpoint has a documented default order with a unique tie-breaker (for transactions:
     booking date descending, then id), so pages never overlap or skip rows on a stable data set.
   - Offset paging, not cursors. A workspace holds tens of thousands of rows, not millions, and the
     app needs "page n of m" and totals. The one known effect is accepted and documented: when an
     import adds rows while the user scrolls, the app reloads the list from the first page.
   - Filters are plain query parameters named after the response field (`accountId`,
     `bookingDateFrom`, `bookingDateTo`, `categoryId`, ...); several values mean "any of".
   - Small, bounded lists (categories, portfolios, accounts) are not paged and return a plain
     array, each with a documented upper bound.
   - Enforced in code: an ArchUnit rule forbids `Page<...>` as a controller return type, and the
     contract test checks that every operation with a `page` parameter returns a `PageResponse`.
4. **Stable operation ids.** Every operation gets an explicit, unique `operationId`
   (`listAccounts`, `getAccount`, ...), and every controller a tag. The build fails on a missing or
   duplicated id.
5. **Types for the app are generated, the transport stays ours.** `openapi-typescript` generates
   `mobile/src/api/schema.d.ts` from the committed spec. The existing wrapper in `client.ts` is
   typed with the generated paths, so token refresh, timeouts and `If-Match` stay as they are.
   Decimal fields (`format: decimal`) map to `DecimalString`.
6. **Collection endpoints return the whole workspace.** Every member has full access (#389), so a
   list returns everything in the caller's workspace; row-level security keeps workspaces apart. A
   foreign id in a filter behaves like an unknown one, so a list never reveals that something exists.
7. **Demo data is synthetic and goes through the public API.** A seed script creates it through
   HTTP calls, never with SQL, so it also works as an end-to-end smoke test of the contract. It
   never reads `docs/import-samples/`. The demo login with its documented TOTP secret exists only
   when the seed script creates it, never in the default configuration (PO decision 2026-10-20).
8. **Screen specifications come before response shapes** (FR-API-010). The read endpoints of
   Track B are confirmed against the approved screen specifications of Track C before they are
   merged, so each screen needs one call, or a small fixed number of calls, per view.

Decisions 1-8 are recorded as ADR 0006 "API contract, paging and client generation" by US-29-01.

## Committed

Capacity assumption: one backend track (the usual agent sessions) for the full 10 working days,
plus the PO's time to review and approve the UX documents (about half a day in each week).
Sizes: S up to 1 day, M 2-3 days, L 4-5 days.

### Track A - Contract and tooling (week 1, blocks Track B)

| Issue | Story | Pri | Size | Blocked by |
|---|---|---|---|---|
| #371 US-29-01 | ADR 0006 and `docs/api/conventions.md`: naming, collections, paging (decision 3), filters, sorting, errors, concurrency, decimals, dates, uploads, retryable 503 | MUST | S | - |
| #372 US-29-02 | Publish the contract: explicit unique `operationId` and a tag for every operation, `api/openapi.yaml` generated by the build and committed, CI drift check and oasdiff breaking-change check | MUST | M | US-29-01 |
| #373 US-29-03 | Paging, final design: `PageResponse<T>`, size cap, sort whitelist with `INVALID_SORT`, unique tie-breakers, ArchUnit rule; migrate transactions, import batches, reconciliations and the institution catalogue | MUST | M | US-29-01 |
| #374 US-29-04 | Error-code catalogue: every problem `code` the API can return listed in the spec and in `docs/api/error-codes.md`, with EN/DE message keys for the app | MUST | S | US-29-02 |
| #375 US-29-05 | Generated types in `mobile/`: `npm run api:types` generates them from `api/openapi.yaml`, CI fails when they are stale, the existing auth, reference-data and account-proposal code moves to them | MUST | M | US-29-02 |

### Track B - The read endpoints the first screens need (from day 3)

| Issue | Story | Pri | Size | Blocked by |
|---|---|---|---|---|
| #380 US-05-06 | `GET /api/v1/accounts`: all accounts of the workspace, filterable by portfolio, account type and archived; each row carries currency, `valueKnown`, the value status (estimated, stale, unreconciled) and the current balance in account and workspace currency | MUST | M | US-29-03, UX-02 draft |
| #381 US-04-05 | `GET /api/v1/portfolios`: the workspace's portfolios with type, account count, total in workspace currency and value status, for the portfolio tabs | MUST | S | US-29-03, UX-02 draft |
| #382 US-07-35 | `GET /api/v1/transactions`: workspace-wide list over every account, filtered by accounts, date range, category, uncategorised, free text and amount range; paged per decision 3; index and explain-plan check on a large generated data set | MUST | L | US-29-03 |
| #383 US-03-05 | `GET` collection for workspace members (the settings screens) | SHOULD | S | US-29-03 |
| #389 US-03-06 | Every member has full access: remove account ownership and sharing grants; every member is a user with a login | MUST | L | — |
| #390 US-04-06 | Portfolios: rename institutions to user-named portfolios, suggested portfolio type, per-account-type Portfolio View for the tabs | MUST | L | US-29-03 |

### Track C - UI specification (PO and UX work, days 1-8)

All UX documents live in `docs/ux/`, in Markdown with low-fidelity wireframes as images or an
HTML page, reviewed and approved by the PO.

| Issue | Story | Pri | Size | Blocked by |
|---|---|---|---|---|
| #384 SPIKE OPEN-023 | Web client approach (RISK-010): build a throwaway transaction list on react-native-web against the demo data (sorting, filters, multi-column layout, keyboard navigation, 1,000+ rows). Decide Expo web or a separate React web client; record the decision as ADR 0007. Time box 2 days. | MUST | M | US-07-35 draft endpoint or a JSON fixture |
| #376 US-01-08 (UX-01) | Information architecture and flows: the navigation tree of section 27 cut to the MVP, the layout per form factor (phone tabs, tablet and web sidebar), and the first-run journey from sign-up to the first overview in under ten minutes (NFR-UX-005) | MUST | S | - |
| #377 US-01-09 (UX-02) | Screen specifications for sprint 7: Overview (net worth, cash-flow teaser), portfolio tabs and Portfolio View, account detail with transactions, transaction detail with category change. Each spec gives the purpose, every field with its source endpoint and field name, the states (loading, empty, error, offline, estimated, stale, unreconciled, archived, no permission), actions, the beginner and advanced views (NFR-UX-001), and accessibility notes | MUST | M | UX-01 |
| #378 US-01-10 (UX-03) | Visual foundations and component inventory: colour, type and spacing tokens for light and dark mode; amount formatting per locale and currency, including negative and estimated values; the one visual treatment for estimated, stale and unreconciled values (NFR-UX-004); the scope bar (FR-CON-006); the list of shared components (amount, value-status badge, scope bar, data table, empty state, error banner); the chosen chart library | MUST | M | UX-01 |
| #379 US-21-01 (UX-04) | Domain glossary and UX copy EN/DE: the NFR-UX-002 terms with their Swiss and German variants (NFR-I18N-005), and the copy for the sprint-7 screens' empty states and error messages, keyed to the error-code catalogue | SHOULD | S | US-29-04 |

### Track D - Developer onboarding (week 2)

| Issue | Story | Pri | Size | Blocked by |
|---|---|---|---|---|
| #385 US-01-06 | Demo workspace: `scripts/seed_demo.py` builds a synthetic workspace through the API (invented portfolios, CHF and EUR current accounts, a card, a savings account, a mortgage, about six months of categorised transactions, one import batch, a snapshot, one estimated and one stale value), idempotent, documented test login with its TOTP secret for local use only; CI runs it against the compose stack | MUST | M | US-05-06 |
| #386 US-01-07 | `docs/frontend/README.md`: run the stack, seed it, sign in and refresh, call the API with generated types, decimals, `If-Match`, errors, paging, and the screen-to-endpoint map from UX-02 | MUST | S | US-29-05, UX-02 |
| #387 US-01-11 sprint-7 stories | Write the frontend developer's first stories (home, portfolio tabs, account detail, transaction detail and categorise) in full, each linking its UX-02 spec, and put them on Iteration 7 | MUST | S | UX-02, UX-03 |

## Suggested order

- **Days 1-2:** US-29-01, then US-29-02 and US-29-03, which touch every controller; other backend
  PRs wait or rebase right after. UX-01 in parallel; the PO approves it by day 2.
- **Days 3-5:** UX-02 and UX-03 drafts. US-05-06 and US-04-05 are built against the UX-02 draft.
  US-29-05 and US-29-04. The spike starts on day 4 against the demo data or a fixture.
- **Days 6-8:** US-07-35, US-01-06. The spike's decision (ADR 0007). The PO approves UX-02 and
  UX-03; any field they add to a screen goes into the matching endpoint before it merges.
- **Days 9-10:** US-03-05, UX-04, US-01-07, the sprint-7 stories. A dry run: someone who has not
  worked on the backend follows `docs/frontend/README.md` from a clean checkout to a signed-in
  call that lists the demo accounts with generated types. Every gap becomes a fix or a story.

## Stretch (priority order, unlabelled until pulled in)

1. #363 [SPIKE] cross-format duplicate detection (priority-must for EPIC 07, the PO rule "nothing
   imported twice"). It does not touch the contract.
2. Mock server: `npm run api:mock` (Prism on `api/openapi.yaml`) so the app runs without a backend.
   Needs response examples in the spec.
3. UX-02 for the import flow (upload, preview, commit), so #367 and #365 can follow in sprint 8.
4. US-11-02 historical net worth endpoint, for the Overview chart.

## Not in this sprint, and why

- **Product screens:** sprint 7, by the frontend developer, against the specs written here.
- **#367 and #265** (holdings import and bank-sync screens): import flows are the hardest screens
  and follow the simple ones.
- **EPIC 24 bank APIs (#259-#264):** still waiting for the FinTS product registration.
- **#233 opening holdings:** needs EPIC 15.
- **#357-#361, #274, #276:** import refinements without a frontend dependency.
- **OPEN-024** (over-the-air update policy): needed before the first store release, not before
  development starts.

## Definition of Done additions for this sprint

- A PR that changes an endpoint commits the regenerated `api/openapi.yaml`; CI checks it.
- A new operation has an `operationId`, a tag, a one-line summary and its error codes.
- A new list endpoint follows decision 3, or explains in the conventions doc why not.
- A screen-facing endpoint names the UX-02 screen it serves in its story.
- No real sample data in seeds, fixtures, wireframes, screenshots or docs (see SPRINT-5.md).

## Risks

- **US-29-02 and US-29-03 touch every controller.** Merge them early and fast; announce a short
  freeze on controller changes while they are open.
- **The page envelope is a breaking change.** Only backend tests read the old shape today; after
  sprint 7 it would cost real migration work.
- **PO review time for Track C.** If UX-02 is not approved by day 8, the read endpoints merge
  against the draft and sprint 7 starts with a short spec review.
- **The spike may choose a separate React web client.** Then UX-03's component inventory is split
  into shared tokens and per-platform components, and sprint 7 starts with the web client's
  scaffold. The backend work of this sprint is unaffected either way.
- **US-07-35 performance.** It needs the right indexes (account, date, category) and an
  explain-plan check against a generated large data set.

## Decisions taken (2026-10-20, product owner)

- Paging: implement the final design now (decision 3), including the envelope break.
- Sprint 6 prepares; the frontend developer starts in sprint 7. The UI specification is part of
  sprint 6.
- The demo login may ship with a documented TOTP secret, created only by the seed script.

- Scope approved; the 17 stories are filed as #371-#387 with the `sprint-6` label and Iteration 6.
- Later the same day: no ownership or sharing grants within a workspace, and every member is a
  user with a login (#389). The level below the workspace is the user-named **Portfolio**, shown
  as tabs (#390, PO input on #377). #380-#383 and #385 were adjusted; both new stories are in
  sprint 6.
