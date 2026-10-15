#!/usr/bin/env bash
# Smoke test for Mobile Native CI: installs the release build on a running Android emulator or
# iOS simulator, launches it, and fails if the app does not stay up. CI has no backend to sign in
# against, so on Android it checks that the app opens on the sign-in screen (US-02-06) - the tabs
# are only reachable after sign-in. Screenshots and logs land in smoke/ for the workflow to upload.
#
# Usage (from mobile/): scripts/native-smoke.sh android|ios
set -euo pipefail

platform="${1:?usage: native-smoke.sh android|ios}"
# Expo prebuild's default identifiers while app.json sets none.
app_id="com.anonymous.trackmywealth"
settle_seconds=20
mkdir -p smoke

fail() {
  echo "::error::$*"
  exit 1
}

# Bounds of the first on-screen element with exactly this text, empty if there is none. The grep
# must not end the script under pipefail: a missing element is reported by the caller.
android_find_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb exec-out cat /sdcard/ui.xml | tr '>' '\n' | { grep "text=\"$1\"" || true; } | head -1 |
    sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/'
}

android_assert_text() {
  [[ -n "$(android_find_text "$1")" ]] || fail "no '$1' element on screen"
}

android_assert_running() {
  adb shell pidof "$app_id" >/dev/null || fail "app is not running after $1"
}

case "$platform" in
  android)
    adb install -r android/app/build/outputs/apk/release/app-release.apk
    adb logcat -c
    adb shell am start -W -n "$app_id/.MainActivity"
    sleep "$settle_seconds"
    adb exec-out screencap -p >smoke/android-home.png
    android_assert_running "launch"

    # Signed out on a fresh install: the sign-in form, not the tabs, and no crash restoring a session.
    android_assert_text "Sign in"

    adb logcat -d >smoke/android-logcat.txt
    if grep -q "FATAL EXCEPTION" smoke/android-logcat.txt; then
      fail "fatal exception in logcat (see smoke/android-logcat.txt)"
    fi
    ;;
  ios)
    device=$(xcrun simctl list devices available -j |
      python3 -c 'import json,sys; print(next(d["udid"] for r,ds in json.load(sys.stdin)["devices"].items() if "iOS" in r for d in ds if d["name"].startswith("iPhone")))')
    xcrun simctl boot "$device"
    xcrun simctl bootstatus "$device"
    xcrun simctl install "$device" ios/build/Build/Products/Release-iphonesimulator/TrackMyWealth.app
    xcrun simctl launch "$device" "$app_id"
    sleep "$settle_seconds"
    xcrun simctl io "$device" screenshot smoke/ios-home.png
    xcrun simctl spawn "$device" launchctl list | grep -q "$app_id" ||
      fail "app is not running after launch"
    xcrun simctl spawn "$device" log show --last 2m --predicate "process == 'TrackMyWealth'" \
      >smoke/ios-log.txt || true
    ;;
  *)
    fail "unknown platform '$platform'"
    ;;
esac

echo "Smoke test passed on $platform"
