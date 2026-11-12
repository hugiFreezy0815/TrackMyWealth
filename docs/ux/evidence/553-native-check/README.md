# #553 deep link after sign-in: native and web checks (PR #556)

Date: 2026-11-12. App code at PR head `44a32c58` (branch `fix/issue-553-preserve-auth-deep-links`).
Synthetic data only, on an own local stack (`docker compose -p tmw-pr556`, backend on port 8090),
seeded with `../494-web-check/seed.mjs`, `seed-extra.mjs` and, for the member with MFA,
`../535-ios-check/enroll-mfa.mjs`.

## iOS Simulator (native release build)

iPhone 17e simulator, iOS 27.2, Xcode 27.2, Maestro 2.12.1. Release build of `44a32c58`
(`expo prebuild --platform ios`, `xcodebuild -configuration Release -sdk iphonesimulator
CODE_SIGN_IDENTITY=-`, `EXPO_PUBLIC_API_URL=http://localhost:8090`). Flows: `00`–`02` from
`../535-ios-check/` (with its `totp-server.mjs`), plus `03-signed-in-link-opens-account.yaml`
here. The link is `trackmywealth://accounts/{id}` for the member's synthetic "Girokonto".

| Case | Result | Screenshots |
|---|---|---|
| A. App not running, signed out: link → sign-in → MFA → account | Pass | `a-cold-ios-01…03-*.png` |
| B. Signed in: the same link opens the account | Pass | `b-signed-in-ios-04-signed-in-link.png` |
| C. App running signed out: link → sign-in → MFA → account | Pass | `c-running-ios-01…03-*.png` |

Case A failed on `main` at `92958b07` in the #535 check (Overview opened instead).

Not covered: Android (no Android SDK on the check Mac), a physical device.

## Web (production export)

Google Chrome 157, headless, locale en-GB. `expo export -p web` of `44a32c58`, served statically
with rewrites for the dynamic routes. Script: `deep-link-web.mjs` (credentials from the seed's env
file). Each flow opens `/accounts/{id}` in a fresh browser context, signs in and checks that the
linked screen opens and the address stays `/accounts/{id}`. With a delay (1.5 s on
`GET /users/me`), it also checks halfway through that nothing of the linked screen is visible
before the identity is known.

| Flow | No delay | `/users/me` delayed 1.5 s |
|---|---|---|
| Member with MFA | Pass | Pass |
| Member without MFA | Pass | Pass |
| First-run member, Continue | Pass | Pass |
| First-run member, Skip for now | Pass | Pass |

The first-run flows ran against a second stack whose workspace holds only the automatic
Personal Assets portfolio (`seed-first-run.mjs`; all members of a stack share one workspace, so the
first-run step shows only there). That member has no account, so the link points at an unknown
id: the account screen then says "This account no longer exists or is not available to you",
and the address keeps the id.

For comparison, an export with `main`'s `app-shell.tsx` (before the fix) and the same script:
member without MFA 5 of 5 ended on `/overview`; both first-run flows lost the route to `/`.
