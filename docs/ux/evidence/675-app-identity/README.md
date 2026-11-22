# App identity verification for #675

The platform images are the five export frames on the [Figma App identity page](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=177-442) (updated 2026-11-22). Their masters are the original logo artwork at its native 690 px, so the 1024 × 1024 `icon.png` and `splash-icon.png` are 1.48× upscales (the earlier 512 px Figma crop was 2×). A fully sharp App Store icon needs the artwork at 1024 px or more. The opaque launcher and Apple touch icons extend the artwork to the corners for system masking and are saved without an alpha channel. The rounded-tile exports (`splash-icon.png`, `app-icon.png`, `favicon.png`) come out of Figma with an opaque white matte in the corners; it is made transparent before commit, un-blending the anti-aliased rim, so the corners do not show on the dark canvas `#07111F` or a dark browser tab.

## Native resources

Expo SDK 58 generated both native projects with `expo prebuild --no-install` in a temporary copy with local test package identifiers. The generated iOS asset catalog contains the opaque 1024 × 1024 icon and light/dark splash appearances. The Android manifest uses an adaptive icon with foreground and monochrome layers, and the day and night resources contain the same splash artwork. Both platforms use the light canvas (`#F4F7F8`) and dark canvas (`#07111F`).

Figma's component has a single raster image and no separable foreground or monochrome artwork, so [`make_android_adaptive_icon.py`](make_android_adaptive_icon.py) derives both layers from the exported `icon.png`; its speck filter scales with the input size. The foreground scales the launcher artwork to 68 % (the arrow tip stays inside the 66 dp safe circle) over a blurred copy of itself, and the background colour `#022F54` matches its darkest edge. The monochrome layer is the ring, T, W and arrow thresholded from the raster. A local-contrast test keeps the dark-blue lower left of the ring, and the contour is smoothed before downscaling. Replace both with designed layers once design delivers them.

Expo writes the Android 12+ splash logo as a 288 dp canvas with the logo at 76 dp in the centre (`drawable-xxxhdpi/splashscreen_logo.png` is 1152 px with a 304 px logo), so the system shows it at the same size as the React Native overlay.

The [PR review](https://github.com/hugiFreezy0815/TrackMyWealth/pull/678#issuecomment-7083324426) reports a passing Android release smoke test after startup. The remaining visual checks need iOS and Android release builds.

| Capture | iOS | Android |
| --- | --- | --- |
| Launcher with the app installed | Home screen | Home screen with themed icons off and on, using a launcher with themed-icon support |
| Cold start in system light mode | Launch screen and transition recording | Launch screen and transition recording |
| Cold start in system dark mode | Launch screen and transition recording | Launch screen and transition recording |

Start each recording with the app fully stopped. Inspect the frames from the native splash through the React Native fade for a blank frame, canvas color flash, or logo size jump. The overlay waits for the logo to display and fades the same mounted image. If the logo has not displayed after 2 s, the native splash is released and the overlay keeps waiting; after 4 s it fades regardless. The overlay never takes touches and unmounts even when the fade is interrupted. The tests cover the handoff trigger, duplicate events, both timeouts, the image error path, and the interrupted fade. The device recordings are pending.

## Web assets

The static web export emitted 17 routes, each with links to the favicon and the Apple touch icon. Expo emitted an ICO containing 16, 32 and 48 px images. `web-favicon-export-48.png` is the 48 px image extracted from that ICO. The Apple touch icon source is 180 × 180 px. A browser tab screenshot showing the TrackMyWealth favicon is pending. Chromium could not start in the execution sandbox, and the cloud browser could not open the local export.
