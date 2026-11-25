# #713 web check: "Interest paid in {year}" on the loan detail

Field 7 of [S10 loan detail](../../screens/loan-detail.md) is compared with the #712 frames in
the web export, with every API call mocked in Playwright (`page.route`) using synthetic data only.

- **Matrix:** EN (en-GB) and DE (de-CH) × 320 px and 1280 px. Light mode covers all six states
  (amount, estimated, unknown, error 403 → "–", hidden, partial); dark mode covers the estimated
  state.
- **Checks per run:**
  - field 7 is present, or absent in the hidden state;
  - it sits between the panel title row and the first repayment row, and is the panel's
    second child (the block right after the title row);
  - "See all" is in the title row;
  - nothing overflows horizontally.
- **axe** (WCAG 2.1 AA): runs at 320 px on the error, partial and dark runs.
- **Result:** 138 of 138 checks pass ([results.json](results.json)). The screenshots
  (`<lang>-<scheme>-<width>-<state>.png`) show the Repayments panel.

Run it again:

1. Export: `EXPO_PUBLIC_API_URL=http://localhost:8099 npx expo export -p web --output-dir <dist>`.
2. Serve: `npx serve -s <dist> -l <port>`.
3. Check: `APP=http://localhost:<port> OUT=<dir> node interest-web.mjs`.

Needs `playwright-core` and `@axe-core/playwright` in a scratch folder, and the system Chrome
(`channel: 'chrome'`).
