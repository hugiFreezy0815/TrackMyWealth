#!/usr/bin/env bash
# US-28-05 (#331): the postgres image runs this once, on a fresh (empty) data volume only. With
# DB_MIGRATION_PASSWORD set in .env it creates the split roles and the database
# (role-split.sh); without it the stack stays a single-role installation and the application
# creates its own database on first start, as before.
#
# The image sources a script without the executable bit instead of running it, so this file avoids
# `exit` and shell options, which would then apply to the image's own entrypoint.
if [ -n "${TMW_MIGRATION_PASSWORD:-}" ]; then
  bash /trackmywealth/role-split.sh
else
  echo "TrackMyWealth: DB_MIGRATION_PASSWORD is not set - single-role setup, no roles created."
fi
