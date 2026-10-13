#!/usr/bin/env bash
# Smoke test for Mobile Native CI: installs the release build on a running Android emulator or
# iOS simulator, launches it, and fails if the app does not stay up. On Android it also switches
# to the Explore tab (native tabs come from react-native-screens). Screenshots and logs land in
# smoke/ for the workflow to upload.
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

android_tap_text() {
  local text="$1" bounds x1 y1 x2 y2
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  bounds=$(adb exec-out cat /sdcard/ui.xml | tr '>' '\n' | grep "text=\"$text\"" | head -1 |
    sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
  [[ -n "$bounds" && "$bounds" != *bounds* ]] || fail "no '$text' element on screen"
  read -r x1 y1 x2 y2 <<<"$bounds"
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
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

    android_tap_text "Explore"
    sleep 5
    adb exec-out screencap -p >smoke/android-explore.png
    android_assert_running "switching to the Explore tab"

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
