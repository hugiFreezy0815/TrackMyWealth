# #488 shared components: web check (PR #534)

Date: 2026-11-09. Build: PR #534 branch, Expo dev server (web), local backend
(`docker compose -p tmw-pr534`) with a synthetic member, Google Chrome (headless) via Playwright
1.49.1 and axe-core 4.10. The gallery `/dev/components` shows synthetic values only. The script
is [`gallery-check.mjs`](gallery-check.mjs); run it from a scratch folder with `playwright` and
`@axe-core/playwright` installed (never inside `mobile/`).

## Layout and accessibility (light and dark)

| Case | Layout | Horizontal scroll | Clipped amounts | Console errors | axe (WCAG 2.2 A/AA) |
|---|---|---|---|---|---|
| Phone | 390 px @3x | none | none | none | `document-title` only (app shell, not #488) |
| Desktop | 1280 px | none | none | none | `document-title` only |
| Browser zoom 200 % | 640 px @2x | none | none | none | not run |
| Phone, 200 % text | 195 px @6x | none | none | none | not run |

At 200 % on a phone, amounts and change chips wrap instead of being cut off. The deliberately
extreme 17-digit sample amount breaks inside the number at 195 px; realistic amounts fit.

The first run found two web-only defects, fixed in this PR: react-native-web 0.21 ignores
`accessibilityState`, so radios and checkboxes had no `aria-checked` (axe `aria-required-attr`),
and the loading skeleton had a label without a role (`aria-prohibited-attr`). The shared
components now use `aria-*` props, and the skeleton is a busy `progressbar`.

## Behaviour

| Check | Result |
|---|---|
| `AmountField` de-CH `1'234.5` | `1234.5` |
| `AmountField` de-DE `1.234,5` | `1234.5` |
| `AmountField` de-CH `1.250` | `1.250` (in de-CH "." is only a decimal point) |
| Value-status badge opens and closes its explanation sheet | yes |
| Pressable row label | "Sample account, Cash, 1234.50 Swiss francs, Estimated"; no nested button |
| Conflict dialog (Review / Discard) | shown |
| Optional `DateField`: choose and clear | `2026-11-01`, then empty |
| "Hide amounts" (overview switch) | 13 × "••••", no amount in any accessible label |

## Screenshots

Light and dark at each width (`*-desktop`, `*-phone`, `*-zoom200`, `*-phone-zoom200`), plus the
value-status sheet, the conflict dialog and the gallery with "Hide amounts" on.

## Not covered here

A screen-reader pass (VoiceOver or TalkBack) is not required (PO, 2026-11-10; #539 closed as not
planned); the Jest accessibility tests and the axe check above cover the labels. The web
`aria-*` gap also affects screens outside #488 (shell navigation, the "Hide amounts" switch, sign-in
button); see the follow-up issue.
