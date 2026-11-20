# #576 web check: update screen on 426

Run on 2026-11-20 against a local compose stack with `MINIMUM_APP_VERSION=9.0.0` and the web
build (version 1.0.0) from Metro, in Chrome via Playwright.

- The sign-in sends `X-App-Version: 1.0.0`, and the browser's CORS preflight allows it.
- The backend answers `426` with `APP_UPDATE_REQUIRED`, readable from the web origin.
- The app replaces the sign-in form with the update screen (EN and DE, light and dark, phone and
  desktop width). **Reload page** reloads and shows the sign-in again.
- Without the header (`curl`), the same sign-in is served (401 for unknown credentials).

`results.txt` is the script's output. Run it with
`node update-required-web.mjs <webUrl> <apiUrl> <outDir>` (playwright-core, system Chrome).
iOS and Android were not checked here: the native screen differs only in its text and its
**Try again** button, both covered by `update-required-screen.test.tsx`.
