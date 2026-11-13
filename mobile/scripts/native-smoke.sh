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

# Verify the actual sign-in UI, not merely a successful am start or a running PID.
# An overloaded Google APIs emulator can show a system "Pixel Launcher isn't responding"
# dialog above a healthy app (as seen in CI native-smoke-android artifacts). Only dismiss
# that *specific* emulator dialog via its "Wait" button; never mask app crashes/other dialogs.
android_assert_signin() {
  local attempt node x y
  for attempt in 1 2 3 4 5 6 7 8; do
    if adb shell uiautomator dump /sdcard/ui.xml >>smoke/android-diagnostics.txt 2>>smoke/android-adb-errors.txt &&
      adb exec-out cat /sdcard/ui.xml >smoke/android-ui.xml 2>>smoke/android-adb-errors.txt; then
      if grep -Fq 'Pixel Launcher' smoke/android-ui.xml &&
        grep -Fq 'responding' smoke/android-ui.xml; then
        echo "Emulator launcher ANR obscures sign-in (attempt $attempt)" >>smoke/android-diagnostics.txt
        node=$(awk -v RS='>' 'index($0, "text=\"Wait\"") { print; exit }' smoke/android-ui.xml)
        if [[ "$node" =~ bounds=\"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]\" ]]; then
          x=$(( ( ${BASH_REMATCH[1]} + ${BASH_REMATCH[3]} ) / 2 ))
          y=$(( ( ${BASH_REMATCH[2]} + ${BASH_REMATCH[4]} ) / 2 ))
          echo "Selecting system Wait button to recover launcher (attempt $attempt)" >>smoke/android-diagnostics.txt
          adb shell input tap "$x" "$y" 2>>smoke/android-adb-errors.txt || true
        fi
      elif grep -Fq 'text="Sign in"' smoke/android-ui.xml; then
        echo "Sign-in screen visible on attempt $attempt" >>smoke/android-diagnostics.txt
        return 0
      fi
    fi
    android_assert_running "UI check attempt $attempt"
    sleep 3
  done
  fail "sign-in screen not accessible after retries (see android-ui.xml and android-home.png)"
}

android_assert_running() {
  local pid
  pid=$(adb shell pidof "$app_id" 2>>smoke/android-adb-errors.txt) ||
    fail "app is not running after $1 (see smoke/android-adb-errors.txt)"
  [[ -n "$pid" ]] || fail "app has no PID after $1"
  printf 'App PID after %s: %s\n' "$1" "$pid" | tee -a smoke/android-diagnostics.txt
}

# A large `adb logcat -d` can disconnect with exit 255 after a successful emulator launch.
# Bound the read, retry transient ADB errors, and never let an optional diagnostic mask
# the actual app-PID / visible-screen assertions. Preserve any error details as artifacts.
android_capture_logcat() {
  local attempt
  for attempt in 1 2 3; do
    if adb shell logcat -d -t 400 >smoke/android-logcat.txt 2>>smoke/android-adb-errors.txt; then
      echo "logcat captured on attempt $attempt" >>smoke/android-diagnostics.txt
      return 0
    fi
    echo "logcat collection failed on attempt $attempt" >>smoke/android-diagnostics.txt
    sleep 2
  done
  echo "::warning::Android logcat collection failed after three attempts; app and UI checks continue"
  echo "logcat unavailable; see android-adb-errors.txt" >smoke/android-logcat.txt
}

android_capture_state() {
  {
    echo "=== ADB devices ==="
    adb devices -l || true
    echo "=== App PID ==="
    adb shell pidof "$app_id" || true
    echo "=== Foreground activity ==="
    adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity' || true
  } >>smoke/android-diagnostics.txt 2>>smoke/android-adb-errors.txt
}

case "$platform" in
  android)
    : >smoke/android-diagnostics.txt
    : >smoke/android-adb-errors.txt
    # Diagnostics survive failures of screenshot, UI dump and the Android emulator runner.
    trap 'android_capture_state; android_capture_logcat' EXIT
    adb install -r android/app/build/outputs/apk/release/app-release.apk
    adb logcat -c 2>>smoke/android-adb-errors.txt ||
      echo "::warning::Cannot clear logcat before launch"
    adb shell am start -W -n "$app_id/.MainActivity"
    sleep "$settle_seconds"
    android_capture_state
    if ! adb exec-out screencap -p >smoke/android-home.png 2>>smoke/android-adb-errors.txt; then
      fail "screenshot failed (see smoke/android-adb-errors.txt)"
    fi
    # Collect diagnostics first, so a subsequent UI or process failure retains actionable logs.
    android_capture_logcat
    android_assert_running "launch"

    # Signed out on a fresh install: the sign-in form, not the tabs, and no crash restoring a session.
    android_assert_signin
    # Capture the unobscured screen after any transient emulator launcher recovery.
    adb exec-out screencap -p >smoke/android-home.png 2>>smoke/android-adb-errors.txt ||
      fail "final screenshot failed (see android-adb-errors.txt)"
    if grep -q "FATAL EXCEPTION" smoke/android-logcat.txt; then
      fail "fatal exception in logcat (see smoke/android-logcat.txt)"
    fi
    trap - EXIT
    ;;
  ios)
    # Prefer a numbered iPhone ("iPhone 17"): the iOS 27 runtime also lists "iPhone Duo", whose
    # two displays make `simctl io screenshot` fail with "Cannot choose between 2 displays".
    # Older runtimes have no such device; any iPhone remains the fallback.
    device=$(xcrun simctl list devices available -j |
      python3 -c 'import json,re,sys
phones = [d for r, ds in json.load(sys.stdin)["devices"].items() if "iOS" in r for d in ds if d["name"].startswith("iPhone")]
print(next((d for d in phones if re.match(r"iPhone \d", d["name"])), phones[0])["udid"])')
    xcrun simctl boot "$device"
    xcrun simctl bootstatus "$device"
    xcrun simctl install "$device" ios/build/Build/Products/Release-iphonesimulator/TrackMyWealth.app
    xcrun simctl launch "$device" "$app_id"
    sleep "$settle_seconds"
    xcrun simctl io "$device" screenshot smoke/ios-home.png
    # Read the whole list before searching it. Piped into `grep -q`, launchctl gets SIGPIPE when
    # grep exits on the match before the rest is written, and pipefail then reports a running app
    # as not running (#529's red iOS run, 2026-11-08).
    services=$(xcrun simctl spawn "$device" launchctl list)
    grep -q "$app_id" <<<"$services" || fail "app is not running after launch"
    xcrun simctl spawn "$device" log show --last 2m --predicate "process == 'TrackMyWealth'" \
      >smoke/ios-log.txt || true
    ;;
  *)
    fail "unknown platform '$platform'"
    ;;
esac

echo "Smoke test passed on $platform"
