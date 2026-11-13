# #554 sign-in with the keyboard open: iOS, Android and web checks (PRs #558, #564)

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

Not covered on iOS: a physical device.

## Android emulator (native release build)

Date: 2026-11-13. `main` at `ee084e19` (PR #564 merged). Android emulator 37.2.13, Pixel 10 Pro
system image API 37 (Google Play, arm64-v8a), Maestro 2.12.1. Release build
(`expo prebuild --platform android`, `./gradlew assembleRelease -PreactNativeArchitectures=arm64-v8a`,
`EXPO_PUBLIC_API_URL=http://localhost:8099`), `adb reverse tcp:8099 tcp:8099`, the same
`mock-api.mjs`. Flow: `auth-android.yaml`.

| Device | Text size | Result | Screenshots |
|---|---|---|---|
| Pixel 10 Pro, about 427 dp wide | default (font scale 1.0) | Pass | `android-pixel10-*.png` |
| Pixel 10 Pro set to 375 dp wide (`adb shell wm density 546`) | 200 % (`font_scale 2.0`) | Pass | `android-small200-*.png` |

What the screenshots show, per run:

1. `1-server-focused`: keyboard open on Server; the card moves up out of the keyboard's way.
   At default size Email, Password and "Sign in" stay visible; at 200 % the form continues below.
2. `2-server-focused-scrolled`: with the keyboard still open, the form scrolls to Password and "Sign in".
3. `3-email-after-return`: Return in Server moved the focus to Email (the typed address lands there).
4. `4-password-focused`: Return in Email moved the focus to Password; the field stays above the
   keyboard. At default size "Sign in" is visible too; at 200 % it is reached by scrolling (step 2).
5. `5-mfa-keyboard`: MFA step with the number pad open: the code field, "Verify code" and
   "Start over" are all above the keyboard, also at 200 %.
6. `6-mfa-scrolled`: after a scroll gesture (the keyboard closes on drag).
7. `7-mfa-after-done`: Done (✓) on the number pad submitted the code; the mock answers 401, so the
   app shows "Could not sign in…".
8. `8-after-start-over`: "Start over" returns to the sign-in step, scrolled to the top.

The mock received exactly one login per run, with the typed email and a 15-character password,
and one `POST /api/v1/auth/mfa/verify` from the Done key. No "save password" prompt appeared.

Not covered on Android: a physical device, and API 35 (the level CI uses); the emulator runs API 37.

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
