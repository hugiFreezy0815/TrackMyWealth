# #494 list focus (S3): web check

**Run:** 2026-11-17, branch `feature/494-list-focus` merged with `main` at `42af5529`. Fresh compose
stack, `../494-web-check/seed.mjs` (65 transactions, 4 uncategorised: Coop, Migros, SBB,
Amazon.de), Metro web, system Chrome headless, `locale: 'en-GB'`. A fresh seed for each layout,
because every save changes the data. Synthetic data only.

`list-focus.mjs <accountId> <desktop|phone> <out.json>` exited 0 at both widths. Results:
`results-desktop.json` (1280 × 900, W06 table) and `results-phone.json` (390 × 844, M25 list).

| Step (keyboard only) | Expected focus | Desktop | Phone |
|---|---|---|---|
| "Load more" with Enter (50 → 65 rows) | First new row, scrolled into view | ✓ "Synthetic refund" | ✓ |
| Uncategorised filter, save the middle row (Migros) | Next row (SBB) | ✓ | ✓ |
| Save the last row (Amazon.de) | Previous row (SBB) | ✓ | ✓ |
| Save the first row (Coop) | Next row (SBB) | ✓ | ✓ |
| Save the only row (SBB): count 0, filter falls back to All | Account heading "Girokonto" | ✓ | ✓ |

Focus moves only after the category dialog has closed (`onDismiss`). The dialog never keeps
focus, and focus never falls back to the page body. No page or console errors.
