#!/bin/zsh
# #574 / #636 native check on the Android emulator. Needs: the stack on 8093, lossy-proxy.mjs on 8095,
# a Release build with EXPO_PUBLIC_API_URL=http://localhost:8095 installed, the synthetic CSV in
# /sdcard/Download (adb push), adb reverse tcp:8095, and EMAIL, PASSWORD, ACCOUNT_ID, DEVICE in the environment.
set -u
cd "$(dirname "$0")"
OUT=${OUT:-$PWD/out-android}
run() { maestro --device "$DEVICE" test --test-output-dir "$OUT" "$@" || { echo "FAILED: $*"; exit 1; }; }
run -e EMAIL="$EMAIL" -e PASSWORD="$PASSWORD" signin-android.yaml
run -e ACCOUNT_ID="$ACCOUNT_ID" -e PREFIX=android-a -e LOST=false upload-android.yaml
run discard.yaml
touch DROP
run -e ACCOUNT_ID="$ACCOUNT_ID" -e PREFIX=android-b -e LOST=true upload-android.yaml
run discard.yaml
run -e ACCOUNT_ID="$ACCOUNT_ID" -e PREFIX=android-c -e LOST=false upload-android.yaml
run discard.yaml
echo ALL-FLOWS-PASSED
