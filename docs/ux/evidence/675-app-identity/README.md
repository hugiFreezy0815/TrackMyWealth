# App identity verification for #675

The platform images use the 512 × 512 raster from the [Figma app icon component](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=75-178). The opaque launcher and Apple touch icons extend the artwork to the corners for system masking. The splash and favicon use the component's rounded shape with transparent corners.

## Native resources

Expo SDK 58 generated both native projects with `expo prebuild --no-install` in a temporary copy with local test package identifiers. The generated iOS asset catalog contains the opaque 1024 × 1024 icon and light/dark splash appearances. The Android manifest uses the branded launcher fallback, and the day and night resources contain the same splash artwork. Both platforms use the light canvas (`#F4F7F8`) and dark canvas (`#07111F`).

Figma's component has a single raster image and no separable foreground or monochrome artwork. Issue #675 still needs the approved transparent foreground, background, and monochrome layers.

The [PR review](https://github.com/hugiFreezy0815/TrackMyWealth/pull/678#issuecomment-7083324426) reports a passing Android release smoke test after startup. The remaining visual checks need iOS and Android release builds.

| Capture | iOS | Android |
| --- | --- | --- |
| Launcher with the app installed | Home screen | Home screen with themed icons off and on, using a launcher with themed-icon support |
| Cold start in system light mode | Launch screen and transition recording | Launch screen and transition recording |
| Cold start in system dark mode | Launch screen and transition recording | Launch screen and transition recording |

Start each recording with the app fully stopped. Inspect the frames from the native splash through the React Native fade for a blank frame, canvas color flash, or logo size jump. The overlay now waits for the logo to display and fades the same mounted image. The test covers the handoff trigger, duplicate events, and both image failure paths. The device recordings are pending.

## Web assets

The static web export emitted 17 routes, each with links to the favicon and the Apple touch icon. Expo emitted an ICO containing 16, 32 and 48 px images. `web-favicon-export-48.png` is the 48 px image extracted from that ICO. The Apple touch icon source is 180 × 180 px. A browser tab screenshot showing the TrackMyWealth favicon is pending. Chromium could not start in the execution sandbox, and the cloud browser could not open the local export.
