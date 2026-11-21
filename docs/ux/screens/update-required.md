# Update required — M24 native / W14 web (#648)

## Purpose and trigger

A blocking full-screen system state replaces the app when the API responds with HTTP 426 and code `APP_UPDATE_REQUIRED`. It appears regardless of sign-in status and **keeps the stored session**. It does not provide a close, back, sign-out or store link. It must not be confused with an ordinary recoverable request error. See #576 and implementation follow-up #649.

## Binding Figma frames

Figma file: https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth

| Platform / size | Language | Theme | Node ID | Frame |
|---|---|---|---|---|
| Native M24 | EN | Light | `159:3159` | [Open](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=159-3159) |
| Native M24 | EN | Dark | `159:3168` | [Open](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=159-3168) |
| Native M24 | DE | Light | `159:3177` | [Open](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=159-3177) |
| Native M24 | DE | Dark | `159:3186` | [Open](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=159-3186) |
| Web W14 desktop | EN | Light | `159:5779` | [Open](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=159-5779) |
| Web W14 desktop | EN | Dark | `159:5797` | [Open](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=159-5797) |
| Web W14 phone | EN | Light | `159:5788` | [Open](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=159-5788) |
| Web W14 phone | EN | Dark | `159:5806` | [Open](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=159-5806) |
| Web W14 desktop | DE | Light | `159:5815` | [Open](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=159-5815) |
| Web W14 desktop | DE | Dark | `159:5833` | [Open](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=159-5833) |
| Web W14 phone | DE | Light | `159:5824` | [Open](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=159-5824) |
| Web W14 phone | DE | Dark | `159:5842` | [Open](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=159-5842) |

**M24:** The four mobile frames are on the Mobile EN/DE page, adjacent to the existing M24 system-state references (which remain unchanged). These are full-screen native state references, **not** ordinary content cards. The existing iOS status bar is omitted from the state content; native safe areas apply in implementation.

**W14:** The eight new web frames are on the Web EN/DE page, at 1440 × 1024 desktop and 390 × 844 phone widths. Tablet uses the same responsive centered content pattern; it does not acquire an extra navigation affordance.

## Copy contract (exact existing i18n keys)

| Element | i18n key | Native | Web |
|---|---|---|---|
| Heading | `updateRequired.title` | Yes | Yes |
| Explanation | `updateRequired.body` | Yes | No |
| Explanation | `updateRequired.body.web` | No | Yes |
| Version caption | `updateRequired.version` | When version is known | When version is known |
| Primary action | `error.retry` | Try again / Erneut versuchen | No |
| Primary action | `updateRequired.reload` | No | Reload page / Seite neu laden |

The copy is from `mobile/src/i18n/en.json` and `mobile/src/i18n/de.json`, unchanged; German uses informal **du**. The Figma examples use `1.0.0` as an illustrative version value, **not** a fixed release number. Hide the entire caption if no version is available; do not display `undefined` or a placeholder.

## Layout and visual decisions

- Full viewport canvas, no app shell, sidebar, tabs or background content. Content is centered horizontally in a column; on mobile it respects safe areas and vertical scrolling / dynamic text growth.
- Use the **existing 48 px app icon** (component `75:178`), not a new alert illustration or an enlarged wordmark. The icon establishes app identity without distracting from the instruction.
- One **primary filled** action, not a secondary/ghost action: it is the only available next step. Existing `Heading/M`, `Body/M`, `Caption` and `Label/M` styles; spacing follows the UX-03 16 px token, and button minimum height is 48 px.
- Light variant uses Figma semantic color variables (`bg/canvas`, `text/primary`, `text/secondary`, `brand/primary`, `text/on-brand`). Dark examples use the corresponding **existing** `DesignTokens.dark` values from `mobile/src/constants/theme.ts` because the Figma local collection currently has only a Light mode. Do not introduce new tokens or silently change the shared token collection.
- Content width: 342 px for a 390 px phone viewport (24 px side margins); at desktop max 480 px. A phone-width web action fills the 342 px column; desktop action is 240 px wide. Use responsive layouts, not hard-coded screen-center pixel positions in production.
- Screen readers announce the heading and explanation. There is one button with the visible accessible label; focus order follows icon (decorative), heading, explanation, optional version, action. Contrast and target-size requirements are WCAG 2.2 AA.

## Action, feedback and edge cases

- **Native:** `Try again` invokes the retry handler. If the server still requires an update, the same state remains. If the server now accepts the version, restore the original signed-in/out experience using the retained session. No store deep link is part of this issue.
- **Web:** `Reload page` reloads the browser document, which may load a newer client bundle. If still unsupported, the state returns.
- **Version unknown:** remove only the caption, keeping centered layout. Do not show a fake version.
- **Network interruption / repeated tap:** avoid a duplicate retry while a request is in progress and preserve a clear in-progress state; do not imply update success before the request/reload completes. The implementation behavior is owned by #649.
- **Text resizing:** web at 200% zoom and phone viewports must not clip copy or scroll horizontally; vertical overflow may scroll. Native honors dynamic type and safe areas.

## Differences from Figma / implementation follow-up

No open **design** differences: the Figma frames define the target for #649. Existing implementation may differ (it has no icon and currently uses a generic action button); #649 aligns it and supplies manual and automated verification. Figma uses a representative version value. Any discrepancy in the deployed product must be tracked in #649, not silently resolved by changing the design.

## References

- #648 — Figma design and specification
- #576 and PR #644 — feature behavior and current copy
- #649 — implementation, tests and evidence
- `mobile/src/features/shell/update-required-screen.tsx`
- `docs/ux/evidence/576-web-check/` — historical pre-design screenshots, non-binding
