# App identity verification for #675

The platform images use the 512 × 512 raster from the [Figma app icon component](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=75-178). The opaque launcher and Apple touch icons extend the artwork to the corners for system masking. The 1024 × 1024 iOS icon is therefore a 2× upscale and looks soft at large sizes; a 1024 px or vector export from design is needed to fix that. The splash and favicon use the component's rounded shape with transparent corners.

## Native resources

Expo SDK 58 generated both native projects with `expo prebuild --no-install` in a temporary copy with local test package identifiers. The generated iOS asset catalog contains the opaque 1024 × 1024 icon and light/dark splash appearances. The Android manifest uses an adaptive icon with foreground and monochrome layers, and the day and night resources contain the same splash artwork. Both platforms use the light canvas (`#F4F7F8`) and dark canvas (`#07111F`).

Figma's component has a single raster image and no separable foreground or monochrome artwork, so [`make_android_adaptive_icon.py`](make_android_adaptive_icon.py) derives both layers from it. The foreground scales the launcher artwork to 68 % (the arrow tip stays inside the 66 dp safe circle) over a blurred copy of itself, and the background colour `#022F54` matches its darkest edge. The monochrome layer is the ring, T, W and arrow thresholded from the raster. Replace both with designed layers once design delivers them.

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
