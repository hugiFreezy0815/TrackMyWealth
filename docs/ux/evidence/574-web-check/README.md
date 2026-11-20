# #574 Idempotent statement upload (PR #630): signed-in web check

Date: 2026-11-20. `main` at `c7ba8ca3` (PR #630 merged as `371dfa88`) plus the connection copy
of this branch.

**Setup.** An own local stack (`docker compose -p tmw-check630 --profile web up -d --build`, API
on 8093, web export served by the #536 nginx image on 8094) with the production database role
split from `.env.example` (`DB_REQUIRE_RLS_BOUND_ROLE=true`): the backend runs as the RLS-bound
runtime role. Synthetic data from `scripts/seed_demo.py`; signed in as the demo **partner** (no
MFA). The statement is the committed synthetic fixture
`backend/src/test/resources/import/dkb-girokonto-synthetic.csv`, imported into the EUR account
"Demo · Paper Moon Current". Google Chrome headless via `playwright-core`, locale en-GB, 1280 ×
900. Credentials come from the environment, never from the script.

**Lost response.** The browser's own upload reaches the server, which creates the batch (201);
Chrome DevTools `Fetch` (response stage) then fails the answer with a connection reset, as on a
dropped connection. Playwright's `route.fetch()` cannot do this for a multipart upload: it resends
the request without the browser's file, and the server answers 422.

## Results (`idempotent-upload.mjs`, `results.json`)

| # | Check | Result |
|---|---|---|
| A1–A2 | The upload sends `Idempotency-Key`, a version 4 UUID | Pass |
| A3 | Chrome's CORS preflight asks for `authorization,idempotency-key`; the backend answers 200 and allows it | Pass |
| B1 | The lost attempt was committed on the server (201) | Pass |
| B2 | The app says "The connection dropped before the server answered. Try again: your statement won't be imported twice." and offers **Try again** | Pass |
| B3–B4 | **Try again** resends the same key; it differs from scenario A's | Pass |
| B5–B6 | Exactly one batch exists for the lost attempt and its retry, and the review shows that batch | Pass |
| C1 | The next upload is a new create with a new key | Pass |
| D1 | Three uploads made exactly three batches | Pass |
| D2 | No page errors besides the deliberate `net::ERR_CONNECTION_RESET` | Pass |

Database afterwards: every key `COMPLETED` with status 201 under `createImportBatch`; the
runtime role may execute `purge_expired_idempotency_keys`, `PUBLIC` may not; `idempotency_key`
has forced row-level security.

| Screenshot | What it shows |
|---|---|
| `a-review.png` | Scenario A: the review after a normal upload |
| `b-lost-response.png` | Scenario B: the dropped-connection message with **Try again**, file and account kept |
| `b-after-retry.png` | Scenario B after **Try again**: the review of the batch the lost attempt created |

**Not covered here.** The 409 `IDEMPOTENCY_KEY_IN_USE` path cannot be produced from one browser
tab (`ImportBatchIdempotencyTest` and `upload-retry.test.ts` cover it). The native retry on a
phone needs a new build with `expo-crypto`.

**Found by this check.** Before this branch, the lost response showed only the generic "Something
went wrong."; the import screen now explains a dropped connection (EN and DE).
