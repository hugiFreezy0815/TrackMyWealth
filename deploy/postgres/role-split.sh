#!/usr/bin/env bash
# US-28-05 (#331, ADR 0005): splits the TrackMyWealth database into a migration role that owns it
# and an RLS-bound runtime role for the application. Idempotent: safe to run again, e.g. to change
# the passwords.
#
# Runs inside the postgres container as its superuser (POSTGRES_USER), with the two passwords in
# the environment - the compose file passes them from .env:
#   - on a fresh volume, initdb/10-trackmywealth-roles.sh runs it once, automatically;
#   - for an existing installation, the README's "Database roles" section: back up, then
#       docker compose exec postgres bash /trackmywealth/role-split.sh
#
#   TMW_MIGRATION_PASSWORD  password of trackmywealth_migration (DB_MIGRATION_PASSWORD in .env)
#   TMW_RUNTIME_PASSWORD    password of trackmywealth_runtime (DB_PASSWORD in .env)
#   TMW_DATABASE            the application's database (default trackmywealth)
set -euo pipefail

: "${TMW_MIGRATION_PASSWORD:?TMW_MIGRATION_PASSWORD is not set (DB_MIGRATION_PASSWORD in .env)}"
: "${TMW_RUNTIME_PASSWORD:?TMW_RUNTIME_PASSWORD is not set (DB_PASSWORD in .env)}"
database="${TMW_DATABASE:-trackmywealth}"
admin="${POSTGRES_USER:-postgres}"
here="$(cd "$(dirname "$0")" && pwd)"

# The name is spliced into SQL below (CREATE/ALTER DATABASE take no bind parameters).
if [[ ! "$database" =~ ^[a-z_][a-z0-9_]*$ ]]; then
  echo "TMW_DATABASE must be a plain lower-case identifier, not '$database'." >&2
  exit 1
fi

psql_as_admin() {
  psql --no-psqlrc --set ON_ERROR_STOP=1 --username "$admin" "$@"
}

psql_as_admin --dbname postgres --file "$here/role-split-cluster.sql"

exists="$(psql_as_admin --dbname postgres --tuples-only --no-align \
  --command "SELECT 1 FROM pg_database WHERE datname = '$database'")"
if [ "$exists" = "1" ]; then
  psql_as_admin --dbname postgres \
    --command "ALTER DATABASE \"$database\" OWNER TO trackmywealth_migration"
else
  psql_as_admin --dbname postgres \
    --command "CREATE DATABASE \"$database\" OWNER trackmywealth_migration ENCODING 'UTF8'"
fi

psql_as_admin --dbname "$database" --file "$here/role-split-database.sql"
echo "TrackMyWealth: database '$database' now belongs to trackmywealth_migration;" \
  "the application connects as trackmywealth_runtime."
