#!/bin/sh
# #633: download the Maven distribution and every dependency and plugin of the build, retrying the
# whole resolution up to three times. Used by the Docker build and Backend CI before the build.
#
# Maven retries a single request itself (.mvn/maven.config), but two failures it cannot retry:
# the wrapper fetches the Maven distribution with one curl call, and a download that breaks off
# mid-body ("Premature end of Content-Length delimited message body") fails at once. Files already
# downloaded stay in the local repository, so a retry only fetches what is still missing.
set -u
cd "$(dirname "$0")/.." || exit 1

attempts=3
attempt=1
until ./mvnw -B dependency:go-offline "$@"; do
    if [ "$attempt" -ge "$attempts" ]; then
        echo "Resolving the Maven dependencies failed $attempts times, giving up" >&2
        exit 1
    fi
    pause=$((attempt * 20))
    echo "Resolving the Maven dependencies failed (attempt $attempt of $attempts), retrying in ${pause}s" >&2
    sleep "$pause"
    attempt=$((attempt + 1))
done
