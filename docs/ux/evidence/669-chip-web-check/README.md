# #669 change chip: web check at 320–1280 px and axe

Date: 2026-11-21, branch `feature/669-change-chip-wrap`.

**Setup.** An own local stack built from this branch
(`docker compose -p tmw-pr671 --profile web up -d --build`, API on 8108, web export on 8109,
`WEB_API_ORIGINS=http://localhost:8108`), seeded with the synthetic demo data from
`scripts/seed_demo.py`. Signed in as the demo partner (no MFA). Google Chrome headless.
`chip-web.mjs` rewrites the net-worth and portfolio history responses so `returnFrom` lies three
days after `from` and the return is −12.34 %, which shows the "since …" label. DE comes from the
member's `/users/me` language (locale de-CH), EN uses locale en-GB.

Widths 320 and 640 px equal 200 % browser zoom on 640 and 1280 px windows (react-native-web sizes
text in CSS px).

## What a run checks

Each language × width × period (1Y, Max) passes only if:

- the chip row and the page have no horizontal overflow, and the chip and label end inside the row;
- the "since …" / "seit …" label stays on one line;
- the label sits below the chip or beside it on the same line, never on top of it.

axe (WCAG 2.0/2.1 A and AA, 2.2 AA) runs on 1Y at 320 and 1280 px; any violation fails the run.

## Results (`results.json`)

All 16 layout checks and all 4 axe checks pass (0 violations).

| Width | Chip | Label | Screenshots |
|---|---|---|---|
| 320 (200 % zoom on 640) | wraps inside the pill (60 px high); "· Return −12.3 %" moves to the second line together | one line, below the chip | `EN-320-*.png`, `DE-320-*.png` |
| 390 (phone) | one line (36 px) | one line, below the chip | `EN-390-*.png`, `DE-390-*.png` |
| 640 (200 % zoom on 1280) | one line | one line, beside the chip | `EN-640-*.png`, `DE-640-*.png` |
| 1280 (desktop) | one line | one line, beside the chip | `EN-1280-*.png`, `DE-1280-*.png` |

## Rerun

Start the stack as above with your own `-p` name and ports, then seed it with
`python3 scripts/seed_demo.py --base-url http://127.0.0.1:<api> --credentials-file <scratch>/creds.json`.
From a scratch folder with `playwright-core` and `@axe-core/playwright`, run:

```
CREDS=<scratch>/creds.json APP=http://localhost:<web> API=http://localhost:<api> OUT=<dir> \
  node chip-web.mjs
```

The run exits 1 if any check fails. Credentials come from the seed file, never from the script.
