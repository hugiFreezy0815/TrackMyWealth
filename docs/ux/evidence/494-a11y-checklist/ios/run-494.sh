#!/bin/bash
# #494 native check on the iOS Simulator: run-494.sh <device-udid> <account-id> <app> <out> <total> <uncat>
# Needs TMW_EMAIL/TMW_PASSWORD (demo partner), DEVELOPER_DIR, JAVA_HOME, and the compose project
# tmw-pr604 (its backend is paused for the refresh-error step).
set -euo pipefail
device="$1" account="$2" app="$3" out="$4" total="$5" uncat="$6"
here="$(cd "$(dirname "$0")" && pwd)"
bundle=com.anonymous.trackmywealth
mkdir -p "$out"
xcrun simctl boot "$device" 2>/dev/null || true
xcrun simctl bootstatus "$device" -b
xcrun simctl install "$device" "$app"
m() { maestro --device "$device" test --test-output-dir "$out/maestro" -e TMW_EMAIL="$TMW_EMAIL" -e TMW_PASSWORD="$TMW_PASSWORD" -e TOTAL="$total" -e UNCAT="$uncat" "$@"; }
m "$here/01-sign-in.yaml"
xcrun simctl openurl "$device" "trackmywealth://accounts/$account"
m "$here/02-scroll-paging.yaml" "$here/03-filter-refresh.yaml"
docker pause tmw-pr604-backend-1 >/dev/null
trap 'docker unpause tmw-pr604-backend-1 >/dev/null 2>&1 || true' EXIT
m "$here/04-refresh-error.yaml"
docker unpause tmw-pr604-backend-1 >/dev/null
m "$here/05-retry.yaml"
find "$out/maestro" -name 'ios-494-*.png' -exec cp {} "$out/" \;
echo "native check passed"
