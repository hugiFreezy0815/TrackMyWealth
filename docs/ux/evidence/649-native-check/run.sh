#!/usr/bin/env bash
# #649 native check of the Update required screen against a real backend whose minimum app
# version is raised, the server stopped, the minimum lowered again. Needs: a compose stack
# (project $PROJECT, API on 8093) started from $REPO with --env-file $ENVFILE and the port
# override $OVERRIDE; a Release build (version 1.0.0, EXPO_PUBLIC_API_URL=http://localhost:8093)
# installed on $DEVICE; EMAIL and PASSWORD of a member without MFA.
# Usage: run.sh ios|android [de-DE|en-GB]
# The optional locale check (#674) needs a fresh Release install and no demo credentials.
set -eu
PLATFORM=${1:-}
LOCALE=${2:-}
case "$PLATFORM:$LOCALE" in
  ios:|android:|ios:de-DE|ios:en-GB|android:de-DE|android:en-GB) ;;
  *) echo "Usage: $0 ios|android [de-DE|en-GB]" >&2; exit 2 ;;
esac
cd "$(dirname "$0")"
PREFIX=$PLATFORM${LOCALE:+-$LOCALE}
OUT=${OUT:-$PWD/out-$PREFIX}
APP=com.anonymous.trackmywealth
compose() { docker compose -p "$PROJECT" --env-file "$ENVFILE" -f "$REPO/docker-compose.yml" -f "$OVERRIDE" "$@"; }
# Restart the backend with the given minimum version ('' serves every version) and wait for it.
gate() {
  local expected=401 status
  if [[ -n $1 ]]; then expected=426; fi
  echo "== MINIMUM_APP_VERSION='$1'"
  MINIMUM_APP_VERSION=$1 compose up -d backend >/dev/null 2>&1
  for _ in {1..100}; do
    status=$(curl -s --max-time 3 -o /dev/null -w '%{http_code}' -H 'X-App-Version: 1.0.0' \
      http://localhost:8093/api/v1/users/me) || status=000
    if [[ $status == "$expected" ]]; then
      echo "   GET /users/me with X-App-Version 1.0.0 -> $status"
      return
    fi
    sleep 3
  done
  echo "Expected HTTP $expected from the test backend, got $status" >&2
  return 1
}
run() {
  local flow=$1
  shift
  echo "== maestro $flow"
  maestro --device "$DEVICE" test --test-output-dir "$OUT" "$@" "$flow"
}
stop_backend() {
  echo "== backend stopped"
  compose stop backend >/dev/null 2>&1
}

if [[ -n $LOCALE ]]; then
  if [[ $PLATFORM == android ]]; then
    adb -s "$DEVICE" shell cmd locale set-app-locales "$APP" --locales "$LOCALE"
  fi
  if [[ $LOCALE == de-DE ]]; then
    labels=(
      -e 'SIGN_IN=Anmelden' -e 'EMAIL_LABEL=E-Mail'
      -e 'UPDATE_TITLE=Update erforderlich' -e 'VERSION_LABEL=Installierte Version: 1[.]0[.]0'
      -e 'RETRY=Erneut versuchen' -e 'STILL_REQUIRED=Diese Version wird weiterhin nicht unterstützt.*'
      -e 'OFFLINE=Der Server ist nicht erreichbar[.] Prüfe deine Verbindung.*'
    )
  else
    labels=(
      -e 'SIGN_IN=Sign in' -e 'EMAIL_LABEL=Email'
      -e 'UPDATE_TITLE=Update required' -e 'VERSION_LABEL=Installed version: 1[.]0[.]0'
      -e 'RETRY=Try again' -e 'STILL_REQUIRED=This version is still not supported.*'
      -e 'OFFLINE=Could not reach the server[.] Check your connection.*'
    )
  fi
  # Restore the local backend even if an assertion fails.
  trap 'gate ""' EXIT
  gate 9.0.0
  run locale-barrier.yaml -e PREFIX="$PREFIX" "${labels[@]}"
  stop_backend
  run locale-offline.yaml -e PREFIX="$PREFIX" "${labels[@]}"
  gate ''
  trap - EXIT
  echo ALL-FLOWS-PASSED
  exit 0
fi

gate ''
run "signin-$PLATFORM.yaml" -e EMAIL="$EMAIL" -e PASSWORD="$PASSWORD"
gate 9.0.0
run barrier.yaml -e PREFIX="$PREFIX"
stop_backend
run offline.yaml -e PREFIX="$PREFIX"
gate ''
run lifted.yaml -e PREFIX="$PREFIX"

gate 9.0.0
echo "== German, largest text size"
if [[ $PLATFORM == ios ]]; then
  xcrun simctl ui "$DEVICE" content_size accessibility-extra-extra-extra-large
  xcrun simctl terminate "$DEVICE" "$APP" 2>/dev/null || true
  xcrun simctl launch "$DEVICE" $APP -AppleLanguages '(de)' -AppleLocale de_DE >/dev/null
else
  adb -s "$DEVICE" shell settings put system font_scale 2.0
  adb -s "$DEVICE" shell cmd locale set-app-locales $APP --locales de-DE
  adb -s "$DEVICE" shell am force-stop $APP
  adb -s "$DEVICE" shell am start -n $APP/.MainActivity >/dev/null
fi
run large-text-de.yaml -e PREFIX="$PREFIX"
if [[ $PLATFORM == ios ]]; then
  xcrun simctl ui "$DEVICE" content_size large
else
  adb -s "$DEVICE" shell settings put system font_scale 1.0
  adb -s "$DEVICE" shell cmd locale set-app-locales $APP --locales en-GB
fi
gate ''
echo ALL-FLOWS-PASSED
