#!/bin/sh
# Writes the Content-Security-Policy for this export before nginx starts (#536).
# WEB_API_ORIGINS: space-separated API origins members may sign in to, e.g.
# "https://api.example.com https://nas.example.org:8443" (no path; https except on loopback).
set -eu
if [ -z "${WEB_API_ORIGINS:-}" ]; then
    echo 'WEB_API_ORIGINS must name at least one API origin, e.g. https://api.example.com' >&2
    exit 1
fi
set --
for origin in $WEB_API_ORIGINS; do
    set -- "$@" --api-origin "$origin"
done
python3 -I /srv/web/write-csp.py /srv/web/html /srv/web/content-security-policy.conf "$@"
