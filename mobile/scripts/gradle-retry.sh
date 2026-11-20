#!/bin/sh
# #643: run the Android Gradle build, retrying it up to three times when it failed on a download.
# Usage (from mobile/android): ../scripts/gradle-retry.sh assembleRelease --no-daemon ...
#
# Maven Central and the Gradle plugin portal sometimes answer a single request with a rate limit
# (429), a "not found" or a broken body; Gradle then fails the build ("Could not resolve all
# artifacts", "Could not find org.jetbrains.kotlin:kotlin-stdlib:2.3.0"). Gradle retries a request
# itself only for some of these. A retry of the whole build is cheap: everything already downloaded
# stays in ~/.gradle and finished tasks are up to date, so only the missing downloads repeat. A
# failure that is not a download (a compile error, a failing task) is reported at once, unretried.
#
# GRADLEW names the Gradle command (tests use a stand-in), RETRY_PAUSE the seconds per attempt.
set -u

gradlew=${GRADLEW:-./gradlew}
pause_unit=${RETRY_PAUSE:-30}
attempts=3
log=$(mktemp)
status_file=$(mktemp)
trap 'rm -f "$log" "$status_file"' EXIT

# What Gradle prints when a dependency, plugin or the Gradle distribution could not be downloaded.
download_failure='Could not (resolve|find|download|GET|HEAD|parse POM|get resource)|Received status code (429|5[0-9][0-9])|Read timed out|Connection reset|Premature end of|services\.gradle\.org'

attempt=1
while :; do
    # The build's output stays in the job log; the copy is only for the check below. POSIX sh has
    # no PIPESTATUS, so Gradle's exit status goes through a file.
    { "$gradlew" "$@" 2>&1; echo $? > "$status_file"; } | tee "$log"
    status=$(cat "$status_file")
    [ "$status" -eq 0 ] && exit 0
    if ! grep -Eq "$download_failure" "$log"; then
        echo "Gradle failed (exit $status), not on a download: not retrying" >&2
        exit "$status"
    fi
    if [ "$attempt" -ge "$attempts" ]; then
        echo "Gradle failed on a download $attempts times, giving up" >&2
        exit "$status"
    fi
    pause=$((attempt * pause_unit))
    echo "Gradle failed on a download (attempt $attempt of $attempts), retrying in ${pause}s" >&2
    sleep "$pause"
    attempt=$((attempt + 1))
done
