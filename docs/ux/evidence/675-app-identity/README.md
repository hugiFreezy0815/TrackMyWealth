# App identity verification for #675

The platform images are the five export frames on the [Figma App identity page](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=177-442) (updated 2026-11-22). Their masters are the original logo artwork at its native 690 px, so the 1024 × 1024 `icon.png` is a 1.48× upscale (the earlier 512 px Figma crop was 2×). A fully sharp App Store icon needs the artwork at 1024 px or more. The opaque launcher and Apple touch icons extend the artwork to the corners for system masking and are saved without an alpha channel. The rounded-tile exports (`splash-icon.png` at 304 px, `app-icon.png` at 144 px, `favicon.png` at 48 px) get transparent corners from a vector corner mask in Figma and are committed as exported, with no cleanup. `splash-icon.png` is 304 px (76 dp × 4, which also covers 228 px at iOS @3x) because the React Native overlay bundles the same file.

## Native resources

Expo SDK 58 generated both native projects with `expo prebuild --no-install` in a temporary copy with local test package identifiers. The generated iOS asset catalog contains the opaque 1024 × 1024 icon and light/dark splash appearances. The Android manifest uses an adaptive icon with foreground and monochrome layers, and the day and night resources contain the same splash artwork. Both platforms use the light canvas (`#F4F7F8`) and dark canvas (`#07111F`).

Figma's component has a single raster image and no separable foreground or monochrome artwork, so [`make_android_adaptive_icon.py`](make_android_adaptive_icon.py) derives both layers from the exported `icon.png`; its speck filter scales with the input size. The foreground scales the launcher artwork to 68 % (the arrow tip stays inside the 66 dp safe circle) over a blurred copy of itself, and the background colour `#022F54` matches its darkest edge. The monochrome layer is the ring, T, W and arrow thresholded from the raster. A local-contrast test keeps the dark-blue lower left of the ring, and the contour is smoothed before downscaling. Replace both with designed layers once design delivers them.

Expo writes the Android 12+ splash logo as a 288 dp canvas with the logo at 76 dp in the centre (`drawable-xxxhdpi/splashscreen_logo.png` is 1152 px with a 304 px logo), so the system shows it at the same size as the React Native overlay.

## Release builds

Date: 2026-11-22. Release builds of `main` at `561dee7e` (PR #678 merged), version 1.0.0, prebuilt with Expo's default identifiers (`com.anonymous.trackmywealth`). iOS: `xcodebuild -configuration Release -sdk iphonesimulator` with `CODE_SIGN_IDENTITY=-` on an iPhone 18 Pro simulator (iOS 27.2). Android: `gradlew assembleRelease` (arm64-v8a) on a Pixel 10 Pro emulator (Android 17, API 37, Pixel Launcher). Each app was uninstalled and freshly installed. No backend is needed: the app opens on Sign in.

| # | Check | iOS | Android |
| --- | --- | --- | --- |
| 1 | Launcher icon is the TrackMyWealth app icon | Pass: `ios-1-home-light.png`, `ios-1-home-dark.png` | Pass: `android-1-launcher.png` (+ `-zoom`), `android-1-app-drawer.png` |
| 1b | Themed icons on: the monochrome layer is used | n/a | Pass: `android-1-launcher-themed.png` (+ `-zoom`), Wallpaper & style → Icons → **Minimal** |
| 2 | Cold start, light mode: logo on `#F4F7F8` | Pass: `ios-2-splash-light.png` | Pass: `android-2-splash-light.png` |
| 2b | Cold start, dark mode: logo on `#07111F` | Pass: `ios-2-splash-dark.png` | Pass: `android-2-splash-dark.png` |
| 3 | Transition from native splash to Sign in: no blank frame, no canvas flash, no logo size jump | Pass: `ios-3-cold-start-{light,dark}-frames.png` | Pass: `android-3-cold-start-{light,dark}-frames.png` |

The `*-3-cold-start-*-frames.png` sheets are every frame at 10 fps of a 4–5 s screen recording (`simctl io recordVideo`, `adb shell screenrecord --size 640x1428`), read left to right: launcher, launch animation, native splash, the overlay fading into Sign in. The logo stays at the same size and position from the native splash through the overlay, and the canvas colour does not change. The single splash screenshots are full-resolution frames from those recordings (iOS) or `adb exec-out screencap` 0.7 s after `am start` (Android), scaled to 50 %.

Android 17's Pixel Launcher applies themed ("Minimal") icons on the home screen only; the app drawer keeps the coloured icons. The emulator was set back to Default afterwards. The Pixel dock's last slot is a suggested-app slot that draws a coloured ring around the suggested app; that ring is not part of the icon.

Not covered: physical devices and other Android launchers (One UI and others mask adaptive icons with their own shapes).

## Web assets

The static web export emitted 17 routes, each with links to the favicon and the Apple touch icon. Expo emitted an ICO containing 16, 32 and 48 px images. `web-favicon-export-48.png` is the 48 px image extracted from that ICO. The Apple touch icon source is 180 × 180 px. The favicon shows in a browser tab: `web-1-favicon-safari-tabs.png` (+ `web-1-favicon-zoom.png`) is Safari's tab overview on the iPhone 18 Pro simulator with the static export of `561dee7e` served locally; the tab carries the TrackMyWealth icon next to its title. A desktop browser screenshot was not possible because screen capture is not permitted for the agent session; the Safari tab uses the same `favicon.ico`.
