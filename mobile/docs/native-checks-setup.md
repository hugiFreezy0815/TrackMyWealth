# Setting up a Mac for native checks (iOS Simulator, Android emulator, Maestro)

This sets up an Apple-silicon Mac so the app can be built and driven on the iOS Simulator and an
Android emulator, by hand or by a script. Web checks need none of this (see the evidence folders
under `docs/ux/evidence/`).

Status on 2026-11-10: the iOS and Maestro steps were done on the project Mac, and the iOS checks
of #535 ran with them (`docs/ux/evidence/535-ios-check/`). The Android steps
are written from the CI workflow (`.github/workflows/mobile-native-ci.yml`) and are not yet
tried on that Mac.

## What you need

| Tool                               | For                                                   | Size                              |
| ---------------------------------- | ----------------------------------------------------- | --------------------------------- |
| Homebrew                           | installing the rest                                   | small                             |
| Node.js 22 or newer                | the Expo toolchain (CI uses Node 22)                  | small                             |
| JDK 17 (`brew install openjdk@17`) | Gradle and Maestro                                    | about 300 MB                      |
| Xcode 26 or newer                  | iOS build and Simulator (Expo SDK 58 needs Swift 6.2) | about 15 GB                       |
| iOS Simulator runtime              | the simulated iPhone                                  | about 8 GB                        |
| CocoaPods                          | iOS native dependencies                               | small                             |
| Android Studio                     | Android SDK, emulator, `adb`                          | about 10 GB with one system image |
| Maestro                            | scripted taps and typing on both platforms            | small                             |

## 1. iOS

1. **Install Xcode** from the App Store and open it once. Accept the licence and let it install
   its components.
2. **Point the command line at Xcode.** After installing, the command-line tools often still
   point at the old "Command Line Tools" package, and `xcodebuild` and `simctl` fail:

   ```sh
   xcode-select -p        # should print /Applications/Xcode.app/Contents/Developer
   sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
   ```

   Without `sudo`, set it per shell instead:
   `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`.

3. **Install a Simulator runtime.** Xcode does not ship one. Either Xcode → Settings →
   Components → iOS → Get, or:

   ```sh
   xcodebuild -downloadPlatform iOS
   xcrun simctl list devices available   # should list iPhones
   ```

4. **Install CocoaPods:** `brew install cocoapods`, then check with `pod --version`.

## 2. Android

1. **Install Android Studio** (`brew install --cask android-studio` or from
   developer.android.com) and **open it once**. The setup wizard installs the SDK into
   `~/Library/Android/sdk`; until it has run, that folder does not exist and there is no `adb`.
   Choose the "Standard" setup.
2. **Add the SDK parts** in Android Studio → Settings → Languages & Frameworks → Android SDK:
   - SDK Platforms: Android 15 (API 35), the level CI uses.
   - SDK Tools: Android SDK Build-Tools, Platform-Tools, Android Emulator, Command-line Tools.
3. **Create an emulator** in Tools → Device Manager → Add: a Pixel phone with the API 35
   "Google APIs" image for **arm64-v8a** (CI uses x86_64; Apple silicon needs arm64).
4. **Set the environment** in `~/.zshrc`, then open a new terminal:

   ```sh
   export JAVA_HOME=/opt/homebrew/opt/openjdk@17
   export ANDROID_HOME=$HOME/Library/Android/sdk
   export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
   ```

5. **Check:** `adb version` and `emulator -list-avds` (should print the emulator's name).

## 3. Maestro

```sh
brew tap mobile-dev-inc/tap
brew install mobile-dev-inc/tap/maestro
maestro --version
```

Maestro needs Java on the `PATH` (the `JAVA_HOME` lines above). Wait for one `brew install` to
finish before starting another; a second one fails with "has already locked".

## 4. Check the whole setup

```sh
xcodebuild -version                      # Xcode 26 or newer
xcrun simctl list devices available      # at least one iPhone
pod --version
adb version
emulator -list-avds                      # at least one emulator
java -version                            # 17
maestro --version
```

## 5. Build and start the app

From `mobile/`, after `npm ci`. The generated `ios/` and `android/` folders are not committed.
`EXPO_PUBLIC_API_URL` prefills the server field; a plain `http://` address is accepted for
`localhost` only.

**iOS Simulator.** Sign the app ad hoc (`CODE_SIGN_IDENTITY=-`), as Mobile Native CI does since
#555. An unsigned build (`CODE_SIGNING_ALLOWED=NO`) cannot write the keychain, so every sign-in
fails in it. The API address is built into the release app, so set `EXPO_PUBLIC_API_URL` here:

```sh
npx expo prebuild --platform ios
EXPO_PUBLIC_API_URL=http://localhost:8080 xcodebuild \
  -workspace ios/TrackMyWealth.xcworkspace -scheme TrackMyWealth \
  -configuration Release -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath ios/build \
  CODE_SIGN_IDENTITY=- build
xcrun simctl boot "iPhone 17"            # any name from the device list
xcrun simctl spawn booted defaults write "Apple Global Domain" AppleLanguages -array en-GB  # English system dialogs; reboot the simulator
xcrun simctl install booted ios/build/Build/Products/Release-iphonesimulator/TrackMyWealth.app
xcrun simctl launch booted com.anonymous.trackmywealth
```

**Android emulator.** The emulator reaches the Mac's `localhost` only after `adb reverse`:

```sh
emulator -avd <name> &
npx expo prebuild --platform android --no-install
(cd android && EXPO_PUBLIC_API_URL=http://localhost:8080 ./gradlew assembleRelease -PreactNativeArchitectures=arm64-v8a)
adb reverse tcp:8080 tcp:8080
adb install -r android/app/build/outputs/apk/release/app-release.apk
adb shell am start -W -n com.anonymous.trackmywealth/.MainActivity
```

**Useful for checks**

```sh
xcrun simctl openurl booted "trackmywealth://accounts/<id>"      # iOS deep link
adb shell am start -a android.intent.action.VIEW -d "trackmywealth://accounts/<id>"
xcrun simctl io booted screenshot shot.png
adb exec-out screencap -p > shot.png
maestro test flow.yaml
```

## Limits

- A simulator or emulator is not a physical device. The app-switcher blur and Android's
  screenshot block can behave differently on real hardware.
- Use synthetic data only on a local stack; never real statements or accounts.
