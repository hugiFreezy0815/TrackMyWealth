# #493 Portfolio View (S2): web check

Date: 2026-11-09. Branch `feat/issue-493-portfolio-view` (PR #545) with its review fixes.

**Setup.** Local stack (`docker compose -p tmw-pr545 up -d --build`) and `expo start --web`
against it. Chrome via Playwright, headless (`portfolio-view.mjs`). The data is synthetic
(`seed.mjs`): a member in workspace "Family Example" (EUR) with two portfolios.

- "Bank Example" (EUR): a current account, savings, a credit card and a depot whose holdings
  snapshot has seven positions. The local stack has no security prices, so the positions, the
  depot and the portfolio total have an unknown value ("–", "Value unknown", "Not all values
  are included"). That still shows units, the table structure and "No cost basis yet".
- "Home" (EUR): a property valued at EUR 400'000 and a EUR 450'000 mortgage, so the total is
  negative (FR-NAV-15).

| Screenshot | Viewport |
|---|---|
| `en-light-phone-bank.png` | 390 × 844: phone order, depot positions as the `PositionRow` list |
| `en-light-phone-home-negative.png` | 390 × 844: negative total with a minus sign |
| `en-light-phone-explanation.png` | The total's explanation sheet |
| `en-light-phone-hidden.png` | "Hide amounts" on: amounts, units and percentages show `••••` |
| `en-light-desktop-bank.png`, `en-dark-desktop-bank.png` | 1280 × 900: main column plus the 320 px right column (details, data source), positions as a table |

**Results**

- No horizontal scroll and no console or page errors in any case.
- The selected portfolio tab has `aria-selected=true`.
- Desktop positions are a `<table>` with the caption "Positions in Depot", four
  `<th scope="col">` headers (Position, Units, Value, Unrealised gain), five rows (the top five
  of seven, then "Show all 7 positions") and one focusable link per row. On phone (below 600
  px) there is no table but the list.
- The Home total is read as "Home total, minus 50000.00 euros. Show details".
- With amounts hidden, no amount, unit count or percentage appears in the page text or in any
  `aria-label`.
- The right column shows "Mixed (suggested)" (`portfolioTypeOverridden=false`).
- axe (WCAG 2.0/2.1/2.2 A and AA): one finding, `document-title`. It is app-wide and was
  already reported for #489; it is not part of #493.

**Not covered here**

- Gains and the FX hint need security prices and FX rates, which the local stack does not
  have. Jest covers them (`portfolio-screen.test.tsx`, `position-view.web.test.tsx`).
- Card due dates and statement dates come from card statement imports; Jest covers the chip.
- German copy: the app language comes from the member's profile; Jest covers the DE catalogue.
- Native screen reader (VoiceOver/TalkBack): not required (PO, 2026-11-10); the Jest
  accessibility tests and the web axe check cover the labels.
