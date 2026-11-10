# #490 Overview name (first-run step and Settings): web check

Date: 2026-11-09. Branch `feat/issue-490-overview-name-setup-settings` (PR #541) with its review
fixes.

**Setup.** Local stack (`docker compose -p tmw-pr541 up -d --build`) and `expo start --web`
against it. Chrome via Playwright, headless (`overview-name.mjs`, credentials from
`TMW_EMAIL` / `TMW_PASSWORD`). The data is synthetic: a fresh workspace without a name (CHF) and
one standard member with only the automatic Personal assets portfolio, so the first-run step
shows. Each browser context starts without the device marker.

| Screenshot | What it shows |
|---|---|
| `setup-light-phone.png`, `setup-dark-phone.png` | 390 × 844: the step with "Family Hug" typed and the live first-tab preview |
| `setup-light-desktop.png`, `setup-dark-desktop.png` | 1280 × 900: the same in the desktop shell |
| `setup-light-phone-too-long.png` | 61 characters: "Use at most 60 characters." and Continue disabled |
| `overview-after-continue-phone.png` | After Continue: the first tab reads "Family Hug" |
| `settings-sheet-phone.png` | Settings → "Name of your overview" edit sheet with Save and Clear name |
| `settings-conflict-phone.png` | Another session renamed it to "Their name" meanwhile: the conflict dialog |

**Results**

- First run: the preview follows the typing ("All portfolios" while empty). 61 characters set
  `aria-disabled=true` on Continue; 60 emoji show "60 of 60 characters" and keep it enabled,
  as the server counts code points. Continue saves the name and opens the overview, whose
  first tab reads "Family Hug". Signing in again in the same browser skips the step.
- Settings: the row shows the saved name. A rename by another session in between makes Save
  fail with 412; the dialog shows "Another member changed the name to: Their name" and
  "Your unsaved name: My name", and the field keeps "My name". "Clear name" then sends
  `null`: `GET /api/v1/workspace` returns `name: null`, the row and the first tab read
  "All portfolios".
- The word "workspace" appears in no page text. No horizontal scroll. No page errors; the only
  console error is the browser's log line for the expected 412.
- axe (WCAG 2.0/2.1/2.2 A and AA) on the step and the sheet: nothing from #490. The preview tab
  sits in its own `tablist` (axe `aria-required-parent` before the fix). Remaining, not part
  of #490: `document-title` (app-wide, see 489-web-check) and, on the Settings screen with or
  without the sheet, `aria-required-attr` + `nested-interactive` on the "Hide amounts" switch
  from #487 (react-native-web `Switch` without `aria-checked`).

**Not covered here**

- German copy: the member's language is EN; the Jest tests cover the DE catalogue.
- Native screen reader (VoiceOver/TalkBack): not required (PO, 2026-11-10); the Jest
  accessibility tests and the web axe check cover the labels.
