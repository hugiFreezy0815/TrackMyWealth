# #535 app shell (US-01-13): iOS Simulator checks

Date: 2026-11-10. `main` at `92958b07`, release build, ad-hoc signed
(`CODE_SIGN_IDENTITY=-`). iPhone 17 simulator, iOS 27.2, Xcode 27.2, Maestro 2.12.1. Local stack
with synthetic data (`../494-web-check/seed.mjs`); the member is enrolled in MFA
(`enroll-mfa.mjs`). Steps: `run-ios.sh` with the Maestro flows in this folder.

A simulator is not a physical device. #535 asks for a device; the PO decides whether this is
enough.

## Results

| Check | Result |
|---|---|
| Signed out, deep link `trackmywealth://accounts/{id}`: the sign-in form comes first | Pass (`01-deep-link-shows-sign-in.png`) |
| Sign-in is followed by the MFA step | Pass (`02-mfa-step.png`) |
| After the one-time code the account from the link opens | **Fail**: Overview opens (`03-after-mfa-overview-not-account.png`) |
| The same link while signed in, also on a cold start, opens the account | Pass (`04-same-link-while-signed-in.png`) |
| App-switcher snapshot of a signed-in screen with amounts is blurred | Pass (`05-signed-in-before-background.png` → `06-app-switcher-snapshot.png`) |

## Finding: the deep link is lost after sign-in

Same result when the app was not running and when it was running signed out, and the same on web
(production export, Chrome 157: `/accounts/{id}` → sign-in → MFA → `/overview`).

Cause: after sign-in the session is announced before the identity is loaded. `AppShell` first
renders `<Slot />` directly, then moves it under `OverviewSetupGate`, which shows a skeleton
first. The router's `<Slot />` is unmounted and mounted again and the route falls back to the
start route. Confirmed on web: with the shell rendering nothing until the identity is known, the
same steps end on the account screen. `deep-link.test.tsx` mocks the gate away and so passes.

## How the snapshot was read

Maestro cannot open the app switcher on the simulator. After Home, iOS stores the picture it
shows there in the app's data container (`Library/SplashBoard/Snapshots/sceneID:…/*.ktx`);
`06-app-switcher-snapshot.png` is that file converted with `sips`. No text or amount is readable
in it.

## Notes

- CI's simulator build uses `CODE_SIGNING_ALLOWED=NO`. In that build the keychain write of the
  refresh token fails, so every sign-in ends in "Could not sign in". CI only launches the app
  signed out, so it does not notice.
- On the sign-in screen the keyboard covers the Password field and the Sign in button; Return
  closes it. On the MFA step the number pad has no Return key and covers "Start over"; "Verify
  code" stays visible.
- Not covered: Android (blank card in recents, blocked screenshots, deep link), and anything
  that needs a physical device.
