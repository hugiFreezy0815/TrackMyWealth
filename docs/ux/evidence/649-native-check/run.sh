#!/bin/zsh
# #649 native check of the Update required screen against a real backend whose minimum app
# version is raised, the server stopped, the minimum lowered again. Needs: a compose stack
# (project $PROJECT, API on 8093) started from $REPO with --env-file $ENVFILE and the port
# override $OVERRIDE; a Release build (version 1.0.0, EXPO_PUBLIC_API_URL=http://localhost:8093)
# installed on $DEVICE; EMAIL and PASSWORD of a member without MFA.
# Usage: run.sh ios|android
set -u
PLATFORM=$1
cd "$(dirname "$0")"
OUT=${OUT:-$PWD/out-$PLATFORM}
APP=com.anonymous.trackmywealth
compose() { docker compose -p "$PROJECT" --env-file "$ENVFILE" -f "$REPO/docker-compose.yml" -f "$OVERRIDE" "$@"; }
# Restart the backend with the given minimum version ('' serves every version) and wait for it.
gate() {
  echo "== MINIMUM_APP_VERSION='$1'"
  MINIMUM_APP_VERSION=$1 compose up -d backend >/dev/null 2>&1
  for i in {1..100}; do curl -sf http://localhost:8093/actuator/health >/dev/null && break; sleep 3; done
  # The server's own answer for a 1.0.0 app, as proof of the gate state.
  echo "   GET /users/me with X-App-Version 1.0.0 -> $(curl -s -o /dev/null -w '%{http_code}' -H 'X-App-Version: 1.0.0' http://localhost:8093/api/v1/users/me)"
}
run() { echo "== maestro $1"; maestro --device "$DEVICE" test --test-output-dir "$OUT" "${@:2}" "$1" || { echo "FAILED: $1"; exit 1; }; }

gate ''
run signin-$PLATFORM.yaml -e EMAIL="$EMAIL" -e PASSWORD="$PASSWORD"
gate 9.0.0
run barrier.yaml -e PREFIX=$PLATFORM
echo "== backend stopped"; compose stop backend >/dev/null 2>&1
run offline.yaml -e PREFIX=$PLATFORM
gate ''
run lifted.yaml -e PREFIX=$PLATFORM

gate 9.0.0
echo "== German, largest text size"
if [[ $PLATFORM == ios ]]; then
  xcrun simctl ui "$DEVICE" content_size accessibility-extra-extra-extra-large
  xcrun simctl terminate "$DEVICE" $APP 2>/dev/null
  xcrun simctl launch "$DEVICE" $APP -AppleLanguages '(de)' -AppleLocale de_DE >/dev/null
else
  adb -s "$DEVICE" shell settings put system font_scale 2.0
  adb -s "$DEVICE" shell cmd locale set-app-locales $APP --locales de-DE
  adb -s "$DEVICE" shell am force-stop $APP
  adb -s "$DEVICE" shell am start -n $APP/.MainActivity >/dev/null
fi
# iOS shows English before sign-in whatever the device language is (#674): until that is fixed,
# check the largest text size in English there.
if [[ $PLATFORM == ios ]]; then
  run large-text-en.yaml -e PREFIX=$PLATFORM
else
  run large-text-de.yaml -e PREFIX=$PLATFORM
fi
if [[ $PLATFORM == ios ]]; then
  xcrun simctl ui "$DEVICE" content_size large
else
  adb -s "$DEVICE" shell settings put system font_scale 1.0
  adb -s "$DEVICE" shell cmd locale set-app-locales $APP --locales en-GB
fi
gate ''
echo ALL-FLOWS-PASSED
