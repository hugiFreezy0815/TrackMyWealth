# #636 native statement upload and #574 replay: iOS Simulator and Android emulator

Date: 2026-11-20. Release builds of `main` at `c7ba8ca3` plus PR #634 (connection copy) plus this
branch's fix. The iOS build has the upload fix; the Android build also has the picker type.

**Setup.** An own local stack (`docker compose -p tmw-check630 up -d --build backend`, API on
8093) with the production database role split from `.env.example`, seeded with
`scripts/seed_demo.py`, signed in as the demo **partner** (no MFA). The apps talk to
`lossy-proxy.mjs` on 8095 (`EXPO_PUBLIC_API_URL=http://localhost:8095`; Android via
`adb reverse tcp:8095 tcp:8095`). The proxy passes every request through, records each upload's
`Idempotency-Key`, and while the file `DROP` exists lets the next upload reach the server and then
drops its answer, so the server has committed and the app sees a broken connection. The statement
is the committed synthetic fixture `backend/src/test/resources/import/dkb-girokonto-synthetic.csv`:
iOS reads it from Files › On My iPhone, Android from Downloads (`adb push`). Maestro drives both:
`run-ios.sh` and `run-android.sh` (sign-in, then A, B, C, each followed by Discard). Credentials
come from the environment.

Start the proxy from this folder before a run script, so that the scripts' `touch DROP` reaches it:

```sh
node lossy-proxy.mjs 8095 8093 proxy.log DROP
```

`DROP`, `proxy.log` and the Maestro output folders `out-*/` are run outputs and are gitignored here.

## Found by this check

1. **No native upload at all (#636).** Expo SDK 58 replaces the global `fetch` on iOS and Android.
   Its multipart encoder rejected the React Native `{ uri, name, type }` file part with
   `Error: Unsupported FormDataPart implementation` before anything was sent: the proxy and the
   backend saw no upload, and the app showed a connection error. Found with a temporary debug build
   that printed the original error.
2. **Android greys out CSV files.** Android labels a `.csv` in Downloads `text/comma-separated-values`
   (media store), which the picker filter did not list, so the statement could not be chosen.

## Results with the fix (`results.txt`, from the proxy log)

| # | Check | iOS | Android |
|---|---|---|---|
| A | The upload leaves the phone with a UUID `Idempotency-Key`; 201; the review opens; the server stores the statement's own name `dkb-girokonto-synthetic.csv` | Pass | Pass |
| B | The server commits; the answer is dropped; the app says "The connection dropped before the server answered. Try again: your statement won't be imported twice."; **Try again** resends the same key and gets the same batch back | Pass | Pass |
| C | The next upload gets a new key and a new batch | Pass | Pass |

Database afterwards: one `idempotency_key` row per key, each `COMPLETED` with 201, and exactly
one batch per key.

| Screenshot (iPhone 17, iOS 27.2) | What it shows |
|---|---|
| `ios-a-2-review.png` | A: the review after a normal upload |
| `ios-b-1-lost-response.png` | B: the dropped-connection message with **Try again** |
| `ios-b-2-review.png` | B after **Try again**: the review of the batch the lost attempt created |
| `ios-c-2-review.png` | C: the review of the next upload |

Android's signed-in screens are protected with FLAG_SECURE (#487) and show black in screenshots;
there the Maestro assertions and the proxy log are the evidence.

**Not covered.** A physical device; the 409 `IDEMPOTENCY_KEY_IN_USE` path (one app cannot send
two uploads at once).
