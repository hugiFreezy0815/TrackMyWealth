# #489 Overview (S1): web check

Date: 2026-11-09. Branch `feat/issue-489-overview-main` (PR #538) with its review fixes.

**Setup.** Local stack (`docker compose -p tmw-pr538 up -d --build`) and
`expo start --web` against it. Chrome via Playwright, headless. The data is synthetic: a member
in workspace "Family Hug" (CHF) with two portfolios. PostFinance (CHF) holds cash, savings, a
mortgage and a car loan. DKB (EUR) holds a current account and a credit card. The local stack
has no FX rates, so the EUR accounts have an unknown value. That covers the "Estimated",
"Not all values are included" and "Value unknown" states, and the explanation sheet's list.

| Screenshot | Viewport |
|---|---|
| `en-light-phone.png`, `en-dark-phone.png` | 390 × 844: phone order, M03 two-column section tiles, the tab strip scrolls |
| `en-light-desktop.png`, `en-dark-desktop.png` | 1280 × 900: main column plus the 360 px right column |
| `en-light-desktop-explanation.png` | The net-worth explanation sheet, with accounts that have an unknown value |
| `en-light-desktop-hidden.png` | "Hide amounts" on: every amount shows `••••` |

**Results**

- No horizontal scroll and no console or page errors in any case.
- No amount wraps onto a second line at either width. The check measures every element whose
  spoken label ends in a currency name.
- One page heading ("Overview", from the shell). The first tab has `role=tab` and
  `aria-selected=true`. "+ Add portfolio" is an unselected tab.
- With amounts hidden, no digit group with decimals appears in the page text or in any
  `aria-label`.
- axe (WCAG 2.0/2.1/2.2 A and AA): one remaining finding, `document-title`. It is app-wide:
  the static web shell has no `<title>`, and the overview does not set one. It is not part
  of #489.
- Spoken net worth: "Net worth, minus 480179.60 Swiss francs, Estimated". The digits are
  ungrouped, as UX-02 section 3 requires for spoken labels.

**Not covered here**

- German copy: the app language comes from the member's profile, and a second member cannot
  be created in the MVP. The Jest tests cover the DE catalogue.
- Native screen reader (VoiceOver/TalkBack): not required (PO, 2026-11-10); the Jest
  accessibility tests and the web axe check cover the labels.
