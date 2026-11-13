# #554 sign-in with the keyboard open: iOS and web checks (PR #558)

Date: 2026-11-13. Follow-up to PR #558 (merged at `61199f8`): checks ran on the review fixes in
`fde175c9`, built on `main` at `63dad693` (#559, merged since, only removed unused dependencies).
No backend: `mock-api.mjs` answers `POST /api/v1/auth/login` with an MFA challenge after 1.5 s
and everything else with 401. It logs each login body with the password replaced by its length.

## iOS Simulator (native release build)

Xcode 27.2, iOS 27.2 simulator runtime, Maestro 2.12.1. Release build (`expo prebuild --platform ios`,
`xcodebuild -configuration Release -sdk iphonesimulator CODE_SIGN_IDENTITY=-`,
`EXPO_PUBLIC_API_URL=http://localhost:8099`). Flow: `auth-ios.yaml`.

| Device | Text size | Result | Screenshots |
|---|---|---|---|
| iPhone 17 | default (Large) | Pass | `ios17-*.png` |
| iPhone SE (3rd gen), 375 pt wide | accessibility-large (AX2, about 194 %) | Pass | `se200-*.png` |

What the screenshots show, per device:

1. `1-server-focused`: keyboard open on Server.
2. `2-server-focused-scrolled`: with the keyboard still open, the form scrolls up to Password and "Sign in".
3. `3-email-after-return`: Return in Server moved the focus to Email (the typed address lands there);
   the Return key reads "next" and "Sign in" stays above the keyboard.
4. `4-mfa-numberpad`: MFA step with the number pad open (no Return key on iOS).
5. `5-mfa-scrolled`: scrolled with the number pad open: "Verify code" and "Start over" are reachable.
6. `6-after-start-over`: "Start over" returns to the sign-in step, scrolled to the top.

Return in Email → Password and Return in Password → submit: each run sent exactly one login with
the typed email and a 15-character password (`secret-password`). iOS leaves secure fields and their
keyboard out of screenshots, so this step is proven by the request, not by a picture. The flow
dismisses iOS's own "Save Password?" sheet.

Not covered: Android (no Android SDK on the check Mac) and a physical device. Android must be
checked by hand: with Server, Email, Password and the MFA code focused, the field and its button
must stay above the keyboard (the app draws edge-to-edge, so the window no longer resizes for the
keyboard; `KeyboardAvoidingView` now uses `padding` on Android too).

## Web (Metro dev server)

Google Chrome 157, headless, locale en-GB, API mocked with Playwright routes.
Script: `auth-web.mjs` (`APP=http://localhost:<metro port> OUT=<dir> node auth-web.mjs`, with
`playwright-core` and `@axe-core/playwright` installed in a scratch folder). 24 of 24 checks pass:

- Return in Server → Email, Email → Password, Password submits exactly once (a second Return while
  busy is ignored); Enter in the authenticator code submits once.
- axe: no violations on the sign-in and MFA steps (before this PR: `autocomplete-valid`,
  `landmark-one-main`, `page-has-heading-one`, `region`).
- At 375 × 300 (keyboard open) and 188 px wide (375 pt at 200 % zoom): no horizontal scrolling,
  every field and "Sign in" reachable by scrolling, and after "Start over" the card starts at the top.
