# #649 web check: Update required screen (W14)

Run on 2026-11-21 against the web build (version 1.0.0) from Metro, in Chrome via Playwright.
The API was mocked: every request answered `426 APP_UPDATE_REQUIRED`, so the sign-in raises the
update screen (the real backend answer was checked in `../576-web-check/`).

Checked in EN and DE, light and dark, phone (390 px) and desktop (1280 px), and at real 200 %
Chrome zoom (EN 640 CSS px wide, DE 390 CSS px wide, device pixel ratio 2):

- Heading, web instruction, installed version caption and icon are shown; the sign-in form is
  replaced.
- **Reload page** is 48 px high, inside the viewport without scrolling, full width on phones and
  240 px on wider screens; the page never scrolls horizontally.
- The first Tab focuses **Reload page**; reloading brings the sign-in back.
- No `role=alert` region while there is no error (the native retry error is the only alert).
- axe: 0 violations in all ten states. The first run found `image-alt` (expo-image renders
  `alt` only from `accessibilityLabel`) and `page-has-heading-one`; both were fixed in the PR.

`results.txt` is the script's output. Run it with
`node update-required-649.mjs <webUrl> <apiOrigin> <i18nDir> <outDir>` (playwright-core and
`@axe-core/playwright`, system Chrome). Native retry, its error messages and session recovery
are covered by `update-required-screen.test.tsx`, `retry-after-update-required.test.ts` and
`session.test.ts`; iOS/Android dynamic type was not checked here.
