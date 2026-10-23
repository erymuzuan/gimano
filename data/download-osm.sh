#!/usr/bin/env bash
# Downloads the Geofabrik Malaysia + Singapore + Brunei extract (~250 MB) to data/osm/.
# Data © OpenStreetMap contributors, ODbL 1.0 — see data/README.md.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/osm"
URL="${GIMANO_OSM_URL:-https://download.geofabrik.de/asia/malaysia-singapore-brunei-latest.osm.pbf}"
FILE="$DIR/$(basename "$URL")"
mkdir -p "$DIR"
echo "Downloading $URL -> $FILE"
# -z: only download if the remote file is newer than the local copy.
if [[ -f "$FILE" ]]; then
  curl -fL --retry 3 -z "$FILE" -o "$FILE.tmp" "$URL" && { [[ -s "$FILE.tmp" ]] && mv "$FILE.tmp" "$FILE" || rm -f "$FILE.tmp"; }
else
  curl -fL --retry 3 -o "$FILE.tmp" "$URL" && mv "$FILE.tmp" "$FILE"
fi
if curl -fsL "$URL.md5" -o "$FILE.md5"; then
  expected="$(cut -d' ' -f1 "$FILE.md5")"
  actual="$( (command -v md5sum >/dev/null && md5sum "$FILE" || md5 -r "$FILE") | cut -d' ' -f1)"
  if [[ "$expected" == "$actual" ]]; then echo "md5 OK"; else echo "md5 MISMATCH (remote may have been updated since download)" >&2; fi
fi
ls -lh "$FILE"
