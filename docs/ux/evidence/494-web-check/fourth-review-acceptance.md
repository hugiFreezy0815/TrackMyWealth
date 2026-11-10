# PR #546 — fourth-review acceptance and S4 integration boundary

**Date:** 2026-11-10  
**Review:** [Fourth PR #546 review](https://github.com/hugiFreezy0815/TrackMyWealth/pull/546#issuecomment-6770819589) (REV-008, REV-016).  
**Status:** **Release acceptance incomplete — keep both PRs draft.** This is an execution checklist, **not** a claim that device/browser checks passed. Test the final PR commit, not an earlier green CI commit.

## REV-016 — stacked S4 dependency

PR #546 already includes the functional S4 transaction detail feature: the nested phone deep link, the account-context-preserving 400-px desktop panel, detail/history reads, category override and reset with versioned writes, lifecycle-sensitive removal, localization, privacy masking, and error recovery. The account row and routes directly use these implementations. They do **not** import any code from PR #548 at runtime.

The shared S4 implementation, modal save-locking, translations, and regression tests have been synchronized into #546 from the current #548 branch for the previously reviewed cases: missing/archived resources, failed authoritative ETag refresh, warning localization, pending picker saves, and accessible focus. These files are present **in #546**, not merely in the stacked PR. S3's native last-ten-rows pagination and its predicate tests also belong to #546.

**What stays in #548:** separate adjustments to the native smoke script and workflow, and a SectionList callback-identity refactor. Those changes are not part of #546's S3/S4 runtime module dependencies. They require their own CI and review; passing #546 does not approve them. The full branch comparison must be rechecked after #546 changes, and the integration owner must sign off.

**Sequencing:** Review/merge #546 first **only after** the runtime acceptance and reviewer approvals below. Keep #548 based on `feat/issue-494-account-detail` until #546 merges. Then update/rebase #548 onto the new main head, inspect its **remaining diff** (especially native smoke and any duplicated S4 edits), rerun its CI and accept its independent review before merging #548. Do not force-push, auto-merge, or treat this document as owner approval.

## REV-008 — required authenticated acceptance matrix

Use a disposable local stack with synthetic data and test accounts only. Never use production accounts or capture credentials, access tokens, balances, or other member data in evidence. Store credentials outside the repo in a restricted scratch file. For each run record: **commit SHA, UTC timestamp, OS, device/browser version, viewport/zoom setting, synthetic fixture, result (PASS/FAIL/BLOCKED), screenshot/log path, tester**, and issue for any failure. Do not mark an unexecuted check PASS.

| Platform | Scenario / expected result | Status |
|---|---|---|
| iOS simulator or device, authenticated | S3 load and S4 navigation/back; selected transaction belongs to account | NOT RUN |
| Android emulator or device, authenticated | S3 load and S4 navigation/back; selected transaction belongs to account | NOT RUN |
| iOS + Android, synthetic 1,250+ rows | Scroll smoothly, trigger page requests at last-ten visible rows, no duplicates, reach last page, pull-to-refresh starts at page 0 | NOT RUN |
| iOS + Android | Sort, uncategorized filter/count, category mutation, 412 review/retry, and 428 authoritative retry | NOT RUN |
| iOS + Android | Archive/restore, offline and 5xx retry, 401/403/404 cached-data protection | NOT RUN |
| Desktop browser, authenticated | S3 row opens S4 400-px panel; close restores keyboard focus and preserves account context | NOT RUN |
| Web, authenticated | Category picker search/Save/Cancel; 412 recovery; no console errors/overflow; automated axe scan | NOT RUN |
| Actual Chrome/Firefox browser | Browser **zoom** set to 200% (not merely DPR=2), 320-CSS-pixel equivalent, no hidden controls or horizontal page scrolling | NOT RUN |
| VoiceOver iOS and/or TalkBack Android | Masked quantities/amounts are not spoken; dialog/picker focus, status/error announcements, Retry and Dynamic Type | NOT REQUIRED (waived by the PO, 2026-11-10) |

### Reproducible web procedure

The existing [account-detail browser check](account-detail.mjs) and [synthetic seeder](seed.mjs) are the baseline. They require a local Docker backend, Expo web app, Chrome, and scratch-installed Playwright/axe. The original screenshot set in [README](README.md) was generated on 2026-11-09 **before** the latest S4/pagination work and must not be reused as current evidence.

1. Start a **fresh, isolated** synthetic database and API as described in README; supply generated deployment secrets through local environment only, never commit them. Launch Expo web against that API. Use a temporary scratch output directory.
2. Run the existing seed script to create the test member, portfolio, account and synthetic transactions. Run `account-detail.mjs <accountId> <outDir>` with scratch credentials.
3. Preserve raw command exit status, JSON output, browser-console errors, accessibility findings and screenshots. The script enforces axe findings, unexpected overflow, and desktop panel focus conditions. A Playwright context with `deviceScaleFactor: 2` **does not test actual browser zoom**. Perform a separate true 200% browser-zoom check using browser controls; document the method and capture evidence.
4. Supplement the scripted flow with S4 picker Save/Cancel, 412 recovery, and native accessibility/device checks from the matrix. The seeded 64-row account is **insufficient** to establish 1,250-row native responsiveness: create a separate disposable 1,250+ row fixture and log actual paging/scrolling on both platforms.
5. Record findings against the **final** commit, attach artifacts to the PR, and obtain explicit release-owner waivers for any blocked acceptance scenario. **No waiver is assumed.**

### Existing automated evidence and its limits

The fourth reviewer reports green CI at `1c4e5fd` (842 mobile Jest tests, 2,865 backend tests, Android/iOS release builds and signed-out launch smoke). Those results establish build/test health **only for that commit** and do not establish the authenticated matrix. A new CI run is needed for any later changes. No current authenticated native/video/browser/200%-zoom/VoiceOver/TalkBack evidence has been generated in this review pass.

**Release sign-off:** PENDING authenticated UI evidence, accessible interaction evidence, integration-owner confirmation, required code reviews, and final green CI. Keep #546 draft; do not merge automatically.
