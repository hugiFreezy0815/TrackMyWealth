# #649 native check: Update required screen on iOS Simulator and Android emulator

Date: 2026-11-22. Release builds (version 1.0.0) of `main` at `97dee898`, which contains PR #654. The app icon
screenshots (`ios-8-*`) are from `main` at `283a2597`, after PR #672.
iPhone 18 Pro simulator; Pixel 10 Pro emulator, Android API 37.

**Setup.** An own local compose stack (backend on 8093, single database role), seeded with
`scripts/seed_demo.py`, signed in as the demo partner (no MFA). `run.sh ios|android` drives
the app with Maestro and changes the server between flows: it restarts the backend with
`MINIMUM_APP_VERSION=9.0.0` (the server answers a 1.0.0 app with 426) or empty (served), or
stops it. Each gate change is confirmed with the server's own answer to
`GET /users/me` with `X-App-Version: 1.0.0` (426 or 401). Credentials come from the environment. Run it as `run.sh ios|android` with the variables listed
in its header; Maestro output goes to `out-<platform>/` (gitignored). The large-text step expects
German on both platforms, so on iOS it needs a Release build that contains the #674 fix
(`CFBundleLocalizations`); an older build such as `97dee898` fails that step on iOS. If a step
fails, the script restores the text size, the Android app locale and the backend before it exits.

| # | Check | iOS | Android |
|---|---|---|---|
| 1 | Signed in, then the minimum is raised: a cold start opens on **Update required** with the native instruction and "Installed version: 1.0.0"; neither sign-in nor the app is shown | Pass | Pass |
| 2 | **Try again** while the gate is up: "This version is still not supported…" | Pass | Pass |
| 3 | **Try again** with the server stopped: "Could not reach the server…"; the barrier stays | Pass | Pass |
| 4 | Minimum lowered, **Try again**: the app opens signed in (stored session resumed, no sign-in); still signed in after another cold start | Pass | Pass |
| 5 | Largest text size (iOS accessibility XXXL, Android font scale 2.0): the screen scrolls, **Try again** is reachable and works | Pass (German since #674, see below) | Pass |
| 6 | German device language: German screen and messages | Pass after PR #677 (was **Fail: English shown, #674**) | Pass |
| 7 | The screen shows the TrackMyWealth app icon (Figma `75:178`), light and dark | Pass after PR #672 (#673) | not rebuilt |

## Found by this check

**iOS ignores the device language before sign-in (#674).** With the simulator's system language set to
de-DE (and also with `-AppleLanguages (de)`), the app showed English. Before sign-in the app's
language comes from `Intl.DateTimeFormat().resolvedOptions().locale` (`src/i18n/device-intl.ts`).
The iOS build declares only an `en` localization (no `CFBundleLocalizations`, only `en.lproj`),
so iOS resolves the app language to English. This affects every screen shown before the
member's language is known (sign-in, restoring, Update required), not only #649. Android
resolves the per-app/device locale and shows German.

**Wrong logo (#673, fixed by PR #672).** Screenshots 1–7 still show Expo's placeholder icon (blue
square, white chevron) that the screen used then. `ios-8-app-icon-light.png` and
`ios-8-app-icon-dark.png` show the TrackMyWealth app icon after the fix. They were taken with
`xcrun simctl io booted screenshot` after a cold start: the stored session's refresh got 426 from a
backend with `MINIMUM_APP_VERSION=9.0.0` (Maestro's iOS driver did not start in that run). The launcher icon, splash and favicon are still placeholders
(#675).

`android-font-scale-1.0.png` / `-2.0.png` compare Android's default and largest font scale: Android
14+ scales large text non-linearly, so 2.0 does not double the size.

Screenshots (`ios-*`, `android-*`) show steps 1–5. Android blocks screenshots of signed-in
screens (#487), so `android-4-signed-in.png` is black; the Maestro assertions are the evidence
there.

**Not covered.** A physical device; a 426 that arrives while the app is already running (only
the cold-start path was driven).

## Rechecking the pre-sign-in language for #674

Build the Release app with the fix and point it at the local Compose backend described above.
The runner clears the app's data and any saved session itself. On iOS, set the simulator's
preferred system language and region in Settings before each run; the runner stops with a message
if the simulator language does not match the locale argument. Check German (Germany) first, then
English (United Kingdom) as the control.

On Android the runner sets a per-app locale (`cmd locale set-app-locales`) and resets it to en-GB
afterwards. That run checks that the app follows Android's app language, not the device
language, so it is not evidence for the device-language path that #674 is about. The runner has
no Android check for that path.

```sh
./run.sh ios de-DE
./run.sh ios en-GB
./run.sh android de-DE
```

Each run verifies sign-in, the HTTP 426 screen and continued-426 retry feedback, then stops the
local backend and verifies offline retry feedback. The flow uses synthetic sign-in input that
the server's version gate rejects. Screenshots go to `out-<platform>-<locale>/` by default.
Record the build commit and simulator or emulator version when adding the new evidence.

### iOS results, 2026-11-22

Release build of PR #677 at `605745c2` (`CFBundleLocalizations` = en, de in the built
`Info.plist`), iPhone 18 Pro simulator, iOS 27.2, local Compose backend seeded with
`scripts/seed_demo.py`.

| Run | Result |
|---|---|
| `./run.sh ios de-DE` (simulator language de-DE) | Pass: "Anmelden", "Update erforderlich", "Installierte Version: 1.0.0", German retry and offline messages (`ios-de-DE-1…4`) |
| `./run.sh ios en-GB` (simulator language en-GB, control) | Pass: English copy throughout (`ios-en-GB-1…4`) |
| `./run.sh ios` (simulator en-GB, demo partner) | Pass: checks 1–5, with the large-text step in German (`ios-5…7-de-large-text-*`, replacing the English shots) |

iOS shows a "Save password?" sheet after the sign-in attempt, sometimes several seconds later,
in the system language. `dismiss-save-password.yaml` waits for it and taps "Später" or
"Not Now". `ios-1…4` are still from the `97dee898` build.

### Android result, 2026-11-22

Release APK of the same commit, Pixel 10 Pro emulator, Android API 37:
`./run.sh android de-DE` passes with German copy throughout (`android-de-DE-1…4`). The runner
cleared the app data, set the per-app locale to de-DE and set it back to en-GB afterwards. This
checks the per-app locale only, not the device language (see above).
