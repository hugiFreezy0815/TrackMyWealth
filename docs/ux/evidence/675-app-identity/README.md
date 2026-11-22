# App identity verification for #675

The platform images use the 512 × 512 raster from the [Figma app icon component](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=75-178). The opaque launcher and Apple touch icons extend the artwork to the corners for system masking. The splash and favicon use the component's rounded shape with transparent corners.

## Native resources

Expo SDK 58 generated both native projects with `expo prebuild --no-install` in a temporary copy with local test package identifiers. The generated iOS asset catalog contains the opaque 1024 × 1024 icon and light/dark splash appearances. The Android manifest uses the branded launcher fallback, and the day and night resources contain the same splash artwork. Both platforms use the light canvas (`#F4F7F8`) and dark canvas (`#07111F`).

Figma's component has a single raster image and no separable foreground or monochrome artwork. The Android adaptive and themed icons need the design layers specified in #675. Release-build screenshots of both launchers and the light/dark cold-start splash are pending device or simulator access.

## Web assets

The static web export emitted 17 routes, each with links to the favicon and the Apple touch icon. Expo emitted an ICO containing 16, 32 and 48 px images. `web-favicon-export-48.png` is the 48 px image extracted from that ICO. The Apple touch icon source is 180 × 180 px. A browser tab screenshot is pending because Chromium could not start in the execution sandbox.
