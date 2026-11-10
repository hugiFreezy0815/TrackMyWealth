# PR #546 fourth-review acceptance and S4 integration boundary

**Review:** 2026-11-10, fourth review of PR #546 at `1c4e5fd28079c2327251fbf1bdd3bb7e76d9ea0f`.
**Status:** Implementation reviewed; **release acceptance is incomplete**. This checklist is not a waiver or evidence of a test run. Record the exact head SHA when executing each check.

## REV-016 — Integration and stacked-PR sequencing

PR #546 provides the S3 account screen and the following working S4 entry points **in this branch**:

- Native account transaction selection pushes `/accounts/[accountId]/transactions/[transactionId]`, with a Back action to the account.
- Desktop web account selection shows `TransactionDetail` in a 400px shell side panel, preserving account context and returning focus to the originating row on close.
- Deep-linked desktop transaction detail also renders alongside the account; standard members are required and administrators are redirected.
- The same S4 component supports category override/reset with versioned writes and conflict recovery, transaction removal, change history, trade fields and privacy-aware values. This branch also includes the later S4 corrections for a pending picker dismissal, 428 stale-version retry, terminal/not-found handling, security lookup Retry, localized warnings, and focus stability.

Source boundaries examined: `mobile/src/app/accounts/[accountId]/index.tsx`, `mobile/src/app/accounts/[accountId]/transactions/[transactionId].tsx`, `mobile/src/features/transaction-detail/` and `mobile/src/features/account-detail/`. **Both routes and their S4 component exist in #546 itself**; they do not import modules exclusively from #548.

**Merge sequence:** #548 currently targets `feat/issue-494-account-detail` and remains a separate draft. Merge #546 into `main` first **only after** the acceptance gates and required reviewers approve. Then update #548's base to `main`, rebase/synchronize its head as appropriate without discarding its work, review its remaining diff and rerun CI. **Do not merge #548 first or assume its extra work is approved by #546.** The merge/rebase, independent integration-owner sign-off, and approval are still pending.

At the time of the fourth-review analysis, #548 also contains native smoke-test workflow/script changes and an additional native SectionList callback refactor. Those are **not** part of this #546 S4 functionality acceptance; evaluate them in #548, not by silently copying its workflow changes here.

## REV-008 — Reproducible acceptance matrix

**Environment:** use a fresh synthetic-only backend/database and test accounts. Never use a production API, real financial data, or commit access tokens, passwords, or environment files. Preserve dated, redacted logs/screenshots in a review attachment with the executed SHA and device/browser version. A green Jest test or signed-out startup smoke does not establish any row below.

| Required scenario | Platform and evidence to capture | Status |
| --- | --- | --- |
| Account detail, 50-row paging, next-page trigger inside last 10 visible rows | Android and iOS; record device/OS, screen recording and request/page logs | **Not yet verified on an authenticated device** |
| >=1,250 transactions, varied row heights, scrolling and responsiveness | Android and iOS; record device/OS and observed responsiveness (no fabricated performance figures) | **Not yet verified on an authenticated device** |
| Uncategorised filter, sorting, pull-to-refresh, cache recovery, offline and API errors | Android and iOS; screenshots and synthetic requests/errors | **Not yet verified on an authenticated device** |
| Category Save, two-user 412 review, 428 retry, archive and restore | Android and iOS; synthetic backend request/response evidence, redact credentials | **Not yet verified on an authenticated device** |
| S4 selection, Back, desktop panel close, focus return, picker Save/Cancel | Native iOS/Android and desktop web; screen recording and keyboard focus trace | **Not yet verified end to end** |
| Responsive 320px, desktop 400px panel, no horizontal scrolling or browser errors | Browser at executed SHA; Playwright JSON, console output and screenshots | **Requires fresh browser run** |
| Browser **actual** 200% zoom (not `deviceScaleFactor: 2`) | Chrome or Firefox zoom control at 200%; record browser/version, effective viewport, screenshot and overflow result | **Requires manual browser check** |
| Accessible masked values, error announcements, modal/picker focus, enlarged text | VoiceOver iOS and/or TalkBack Android plus browser keyboard; accessibility recording and Dynamic Type setting | **Requires real assistive-technology check** |

### Browser rerun

1. Start an isolated local backend with synthetic data (see [the original runbook](README.md)); retain generated scratch credentials outside git. Run `seed.mjs` against that stack. The existing seed produces approximately 64 transactions, **not** the required native 1,250-row stress case.
2. Start the Expo web app against the local API, then run `account-detail.mjs` with `TMW_API`, `TMW_APP`, `TMW_EMAIL`, `TMW_PASSWORD`, the seeded account ID and a fresh output directory. The script covers S3, an S4 panel open/close check, accessibility scan and screenshots. Inspect its JSON, captured console errors and page-overflow checks; preserve a date/SHA manifest.
3. Separately exercise the S4 category-picker Save/Cancel, actual browser 200% zoom, and any focus/screen-reader behaviors the existing script does not assert. Do **not** label its `account-zoom-200` screenshot as genuine browser zoom: it changes viewport and device pixel ratio only.
4. Use a **distinct** synthetic 1,250-row dataset for native performance and paging verification, so the browser seed and results are not misrepresented as a stress run.

### Required release sign-offs (not granted here)

- [ ] Authenticated Android scenarios, with device, SHA, screenshots/logs
- [ ] Authenticated iOS scenarios, with device, SHA, screenshots/logs
- [ ] Updated browser/S4 results and real 200% zoom evidence
- [ ] VoiceOver or TalkBack, keyboard focus and large-text evidence
- [ ] Integration owner confirms #546 is independently safe to merge
- [ ] Reviewer approvals; no unresolved release exceptions without explicit release-owner waiver
- [ ] All required CI green on the **final** commit
- [ ] Change PR from draft to ready only after the above gates; merge remains a separate approved action

**No manual acceptance test or waiver is claimed by creating this file.**
