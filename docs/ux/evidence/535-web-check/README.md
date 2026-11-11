# #535 app shell (US-01-13): web checks deferred from PR #533

Date: 2026-11-10. `main` at `92958b07`.

**Setup.** Production web export (`expo export -p web`, served statically with rewrites for the
dynamic routes) against an own local stack (`docker compose -p tmw-535`), seeded with synthetic
data by `../494-web-check/seed.mjs`. Script: `shell-web.mjs` (credentials from `TMW_EMAIL` /
`TMW_PASSWORD`), headless, locale en-GB. Browsers: Google Chrome 157 with its back-forward
cache switched on (Playwright switches it off by default), and Playwright's WebKit 27.2 build as
a stand-in for Safari. Raw results: `results-chrome.json`, `results-webkit.json`.

## Screenshots (Chrome)

| Screen | 390 px (phone) | 800 px (tablet) | 1280 px (desktop) |
|---|---|---|---|
| Sign-in | `sign-in-phone-390.png` | `sign-in-tablet-800.png` | `sign-in-desktop-1280.png` |
| Overview | `overview-phone-390.png` | `overview-tablet-800.png` | `overview-desktop-1280.png` |
| Settings | `settings-phone-390.png` | `settings-tablet-800.png` | `settings-desktop-1280.png` |

Phone and tablet show the bottom tabs Overview + More, desktop the sidebar Overview + Settings.
No horizontal page scroll and no page or console error at any width, in both browsers.

## Web sign-out

The member signs in, opens an account screen, goes to Settings and signs out. "Session data"
below means the seeded names, the greeting, the member's email or any amount in the page text.

| Check | Chrome 157 | WebKit 27.2 (Playwright) |
|---|---|---|
| Sign-out loads a fresh document on `/overview` that shows the sign-in form | Pass | Pass |
| Back, repeated to the start of the history (`/accounts/{id}`, `/overview`): sign-in form, no session data | Pass | Pass |
| Signed out, leave the site, Back: the page restored from the back-forward cache (`pageshow persisted=true`) reloads to sign-in; further Back steps show no session data | Pass | Pass, but no page was restored from the cache (see below) |
| Still signed in, leave the site, Back: the member stays signed in and the account screen is back (`back-while-signed-in-desktop-1280.png`) | Pass (restored from the cache) | **Not verified** |

## Not covered here

- **Safari, "Back while still signed in".** Playwright's WebKit build never restored a page from
  the back-forward cache (`persisted` was always false), so Back loaded the page again. The web
  session lives in memory only, so the member saw the sign-in form: no data shown, but not the
  behaviour to check. Real Safari keeps a back-forward cache; this row needs one manual try in
  Safari (sign in, open an account, visit another site, press Back). `safaridriver` could not be
  started from the automation session.
- The other #535 items need a device: app-switcher snapshot (iOS blur, Android blank card and
  blocked screenshots) and the deep link through sign-in and MFA.
