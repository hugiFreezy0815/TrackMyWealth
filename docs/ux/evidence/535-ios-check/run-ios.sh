#!/usr/bin/env bash
# #535 iOS Simulator checks: deep link while signed out, and the app-switcher snapshot.
# Needs Xcode with an iOS Simulator runtime, Maestro, Java 17 and a local stack seeded with
# ../494-web-check/seed.mjs whose member is enrolled in MFA (enroll-mfa.mjs). See
# mobile/docs/native-checks-setup.md for the installation.
#
# Usage: TMW_EMAIL=... TMW_PASSWORD=... TMW_TOTP_SECRET=... \
#   ./run-ios.sh <simulator-udid> <accountId> <TrackMyWealth.app> <outDir>
# The app must be signed (CODE_SIGN_IDENTITY=-): CI's unsigned build cannot write the keychain,
# so every sign-in fails in it.
set -euo pipefail
device="$1" account="$2" app="$3" out="$4"
here="$(cd "$(dirname "$0")" && pwd)"
bundle=com.anonymous.trackmywealth
mkdir -p "$out"

xcrun simctl bootstatus "$device" -b
xcrun simctl install "$device" "$app"
node "$here/totp-server.mjs" &
trap 'kill %1' EXIT

# 1. Signed out, app not running: the link leads through sign-in and MFA.
maestro --device "$device" test "$here/00-reset-signed-out.yaml"
xcrun simctl terminate "$device" "$bundle"
xcrun simctl openurl "$device" "trackmywealth://accounts/$account"
maestro --device "$device" test -e TMW_EMAIL="$TMW_EMAIL" -e TMW_PASSWORD="$TMW_PASSWORD" \
  "$here/01-deep-link-sign-in.yaml" "$here/02-mfa-opens-account.yaml" || echo "deep link: FAILED"
xcrun simctl io "$device" screenshot "$out/after-mfa.png"

# 2. Signed in on a screen with amounts, then Home: what iOS keeps for the app switcher.
xcrun simctl openurl "$device" "trackmywealth://accounts/$account"
sleep 5
maestro --device "$device" test "$here/03-app-switcher.yaml"
data="$(xcrun simctl get_app_container "$device" "$bundle" data)"
snapshot="$(ls -t "$data/Library/SplashBoard/Snapshots/sceneID:$bundle-default/"*.ktx | head -1)"
sips -s format png "$snapshot" --out "$out/app-switcher-snapshot.png"
