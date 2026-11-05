# Sprint 7 - The first app screens: from nothing to real figures

Sprints are the GitHub label `sprint-7` plus Iteration 7 (2026-11-10 to 2026-11-23) on Project #1.
This file is the overview; the issues hold the full story text and Definition of Done. Written for
#387 (US-01-11) on 2026-11-05 from the approved screen specs in `docs/ux/screens/` (#377, PRs #483
and #485).

**Goal:** a new member signs in, names their overview, adds a portfolio and an account, imports a
bank statement (or enters an opening balance) and sees correct figures on the overview, on phone
and in the browser.

## PO decisions (2026-11-05)

- **Capacity:** two frontend developers (about 20 developer-days) plus backend developers for the
  API stories.
- **Order:** the first-run path first; browsing screens (Portfolio View, Account detail,
  Transaction detail) are stretch.
- **Libraries:** TanStack Query, FormatJS/react-intl and expo-document-picker
  ([ADR 0008](../architecture/adr/0008-app-data-and-i18n-stack.md)). The chart library stays
  with #378, the web-client approach with #384.
- **German copy uses "du".**
- All API gaps found by the specs are in this sprint (#477–#482, #484).

## Frontend stories

Committed (order = start order):

| # | Issue | Story | Spec | Size |
|---|---|---|---|---|
| 1 | #486 | US-01-12 App foundation: data cache, ICU messages, formatting | ADR 0008 | L ≈4 d |
| 2 | #487 | US-01-13 App shell: navigation per device, routes, sign-in design, screen protection | README | M ≈3 d |
| 3 | #488 | US-01-14 Shared UI components and tokens | README | M ≈3 d |
| 4 | #489 | US-01-15 Overview with portfolio tabs and the first-run empty state | S1 | M ≈3 d |
| 5 | #490 | US-01-16 Name the overview in setup and Settings | S1 | S ≈1 d |
| 6 | #491 | US-04-11 Add portfolio and accounts with a starting value | S5, S6 | M ≈3 d |
| 7 | #492 | US-07-39 Import a bank statement | S7 | L ≈5 d |

Committed total ≈ 22 developer-days against ≈ 20. **#492 is the first story to slip** if the
sprint runs late; everything before it still gives a working first run with opening balances.

Stretch, in this order:

| # | Issue | Story | Spec | Size |
|---|---|---|---|---|
| S1 | #493 | US-04-12 Portfolio View | S2 | M ≈3 d |
| S2 | #494 | US-05-08 Account detail with transactions | S3 | M ≈3 d |
| S3 | #495 | US-07-40 Transaction detail with category change | S4 | S ≈2 d |
| S4 | #496 | US-01-17 Show the sprint-7 API additions | all | S per backend story |
| - | #466 | US-07-36 Link a savings pocket (already in sprint 7) | - | S |

### Suggested tracks for two developers

| Days | Developer A | Developer B |
|---|---|---|
| 1-4 | #486 foundation | #488 components (tokens, Amount, badges on fixture data) |
| 5-7 | #487 shell | #488 finish, then #490 overview name |
| 8-10 | #489 overview | #491 add portfolio and account |
| 11+ | #492 import (with B from day 11) | #492 import, then stretch #493 |

The tracks only suggest who starts what; any story may be picked by whoever is free. Every UI
story renders only what the API on `main` sends: an element whose backend story hasn't merged is
not built until #496.

## Backend stories (API gaps from the specs)

| Issue | Story | Needed by | Priority |
|---|---|---|---|
| #477 | US-11-06 Value history and period change | #489, #493 (via #496) | MUST |
| #478 | US-15-05 Depot positions from the newest holdings snapshot | #493, #494 | MUST |
| #479 | US-07-37 Read one transaction (+ portfolio, counter-account, bank code, import) | #494, #495 | MUST |
| #480 | US-07-38 Transaction change history | #495 | MUST |
| #481 | US-11-05 Net worth by section | #489 | SHOULD |
| #482 | US-04-09 Portfolio View details (masked IBAN, reconciled, statement date, FX, last import) | #493 | SHOULD |
| #484 | US-04-10 Catalogue import formats and country filter | #491 | SHOULD |

No UI story waits for them: each screen works without them and gains the element through #496.

## Open sprint-6 items that affect this plan

| Issue | Effect if not done by 2026-11-10 |
|---|---|
| #376 Information architecture | #487 builds the navigation the specs assume (Figma). A later change touches only #487. |
| #378 Visual foundations | #486/#488 use the formatting strings in #486 and the Figma variables; align when #378 merges. |
| #379 Glossary and copy (incl. "du" error texts) | Screens use the specs' draft texts; the catalogue is updated when #379 merges. |
| #384 Web client approach | react-native-web is assumed. A separate web client would change #487 and the web parts of each story. |
| #385 Demo workspace seed | #492's end-to-end check needs its synthetic statement files. Never use real samples. |
| #386 Onboarding guide | Developers rely on `mobile/README.md` and the specs' README. |

## Risks

- Import (#492) is the largest and most error-prone story: many problem codes and paged rows with
  concurrent `PATCH` calls. Mitigation: two developers on it from day 11; holdings statements
  stay out (#367).
- Hermes `Intl` support for ICU plurals and de-CH grouping (#486): polyfills are part of the
  foundation story.
- Seven backend stories in parallel: #477 (L) may not finish. The overview then ships without the
  chart, and #496 moves its rows to sprint 8.
