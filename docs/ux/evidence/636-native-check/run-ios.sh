#!/bin/zsh
# #574 / #636 native check on the iOS Simulator. Needs: the stack on 8093, lossy-proxy.mjs on 8095,
# a Release build with EXPO_PUBLIC_API_URL=http://localhost:8095 installed, the synthetic CSV in
# Files > On My iPhone, and EMAIL, PASSWORD, ACCOUNT_ID, DEVICE in the environment.
set -u
cd "$(dirname "$0")"
OUT=${OUT:-$PWD/out-final}
run() { maestro --device "$DEVICE" test --test-output-dir "$OUT" "$@" || { echo "FAILED: $*"; exit 1; }; }
run -e EMAIL="$EMAIL" -e PASSWORD="$PASSWORD" signin.yaml
run -e ACCOUNT_ID="$ACCOUNT_ID" -e PREFIX=ios-a -e LOST=false upload.yaml
run discard.yaml
touch DROP
run -e ACCOUNT_ID="$ACCOUNT_ID" -e PREFIX=ios-b -e LOST=true upload.yaml
run discard.yaml
run -e ACCOUNT_ID="$ACCOUNT_ID" -e PREFIX=ios-c -e LOST=false upload.yaml
run discard.yaml
echo ALL-FLOWS-PASSED
