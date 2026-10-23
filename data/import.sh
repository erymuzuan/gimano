#!/usr/bin/env bash
# Imports POIs + road segments from the OSM extract into Postgres and OpenSearch.
#   data/import.sh                 # run the importer with the local .NET SDK (uses backend appsettings / env)
#   data/import.sh --docker        # run it as a container in the compose stack (deploy/docker-compose.yml)
# Extra args are passed to the importer (--skip-roads, --skip-places, --skip-search, --keep-index, --pbf <file>).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PBF="$ROOT/data/osm/malaysia-singapore-brunei-latest.osm.pbf"
[[ -f "$PBF" ]] || { echo "Missing $PBF — run data/download-osm.sh first" >&2; exit 1; }

if [[ "${1:-}" == "--docker" ]]; then
  shift
  exec docker compose -f "$ROOT/deploy/docker-compose.yml" --profile tools run --rm importer "$@"
fi

export GIMANO_PBF="${GIMANO_PBF:-$PBF}"
exec dotnet run -c Release --project "$ROOT/backend/src/Gimano.Importer" -- "$@"
