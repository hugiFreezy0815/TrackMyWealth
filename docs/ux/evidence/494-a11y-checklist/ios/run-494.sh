#!/bin/bash
# #494 native check on the iOS Simulator: run-494.sh <device-udid> <account-id> <app> <out> <total> <uncat>
# Needs TMW_EMAIL/TMW_PASSWORD (demo partner), DEVELOPER_DIR, JAVA_HOME, and the compose project
# tmw-pr604 (its backend is stopped for the refresh-error step). Stop, not `docker pause`: after a
# pause the iOS networking stack keeps reusing the frozen keep-alive connections, so requests
# still time out once the backend is back and no retry can recover (web and curl are fine).
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
trap 'docker start tmw-pr604-backend-1 >/dev/null 2>&1 || true' EXIT
docker stop tmw-pr604-backend-1 >/dev/null
m "$here/04-refresh-error.yaml"
docker start tmw-pr604-backend-1 >/dev/null
until curl -sf http://127.0.0.1:8090/actuator/health >/dev/null; do sleep 2; done
m "$here/05-retry.yaml"
find "$out/maestro" -name 'ios-494-*.png' -exec cp {} "$out/" \;
echo "native check passed"
