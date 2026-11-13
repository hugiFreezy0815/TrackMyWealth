#!/usr/bin/env bash
set -euo pipefail

base_url=${1:?Usage: check-headers.sh BASE_URL EXPORT_DIR [API_ORIGIN ...]}
export_dir=${2:?Usage: check-headers.sh BASE_URL EXPORT_DIR [API_ORIGIN ...]}
shift 2
expected_sources=$(printf '%s\n' "'self'" "$@" | LC_ALL=C sort -u)
headers=$(mktemp)
trap 'rm -f "$headers"' EXIT

require_header() {
    if ! grep -Fxiq "$1" "$headers"; then
        printf '%s\n' "Expected header on $path: $1" >&2
        cat "$headers" >&2
        exit 1
    fi
}

check_response() {
    local path=$1 expected_status=$2 cache_control=$3 content_type=$4 status policy actual_sources
    status=$(curl -gsS --max-time 10 -D - -o /dev/null "$base_url$path" |
        tr -d '\r' | tee "$headers" | awk 'NR == 1 { print $2 }')
    if [[ $status != "$expected_status" ]]; then
        printf '%s\n' "$path returned $status, expected $expected_status" >&2
        exit 1
    fi
    require_header "Cache-Control: $cache_control"
    require_header 'Referrer-Policy: no-referrer'
    require_header 'Strict-Transport-Security: max-age=31536000; includeSubDomains'
    require_header 'X-Content-Type-Options: nosniff'
    require_header 'Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=()'
    require_header 'X-Frame-Options: DENY'
    if [[ $content_type == application/javascript ]] && grep -Fxiq 'Content-Type: text/javascript' "$headers"; then
        content_type=text/javascript
    fi
    require_header "Content-Type: $content_type"
    policy=$(sed -n 's/^[Cc]ontent-[Ss]ecurity-[Pp]olicy: //p' "$headers")
    for directive in "default-src 'self'" "frame-ancestors 'none'" "base-uri 'self'" \
        "form-action 'self'" "object-src 'none'"; do
        if [[ "; $policy;" != *"; $directive;"* ]]; then
            printf '%s\n' "Expected CSP directive on $path: $directive" >&2
            exit 1
        fi
    done
    actual_sources=$(awk -F';' '{
        for (i = 1; i <= NF; i++) {
            count = split($i, tokens, /[[:space:]]+/)
            for (j = 1; j <= count && tokens[j] == ""; j++) {}
            if (tokens[j] == "connect-src") {
                for (j++; j <= count; j++) {
                    if (tokens[j] != "") print tokens[j]
                }
            }
        }
    }' <<< "$policy" | LC_ALL=C sort)
    if [[ $actual_sources != "$expected_sources" ]]; then
        printf '%s\n' "Unexpected connect-src on $path" "Expected:" "$expected_sources" \
            "Received:" "$actual_sources" >&2
        exit 1
    fi
    # Expo's runtime styles need the exception on style elements only.
    policy=${policy//"style-src-elem 'self' 'unsafe-inline'"/}
    if [[ $policy != *"script-src 'self' 'sha256-"* ||
        $policy == *"'unsafe-inline'"* || $policy == *"'unsafe-eval'"* ]]; then
        printf '%s\n' "Unexpected Content-Security-Policy on $path: $policy" >&2
        exit 1
    fi
}

check_response / 200 no-store text/html
check_response /overview 200 no-store text/html
check_response /accounts/example/transactions/example 200 no-store text/html
check_response /_expo/static/js/web/missing-00000000000000000000000000000000.js 404 no-store text/html
check_response /missing-page 404 no-store text/html
for extension in js css; do
    asset=$(find "$export_dir/_expo/static" -name "*.$extension" -type f -print -quit)
    [[ -n $asset ]]
    case $extension in
        js) mime=application/javascript ;;
        css) mime=text/css ;;
    esac
    check_response "/${asset#"$export_dir/"}" 200 'public, max-age=31536000, immutable' "$mime"
done
