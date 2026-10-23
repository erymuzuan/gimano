# Map data

Gimano is built entirely on **OpenStreetMap** data.

| What | Source | Used by |
|---|---|---|
| `osm/malaysia-singapore-brunei-latest.osm.pbf` (~250 MB, not committed) | [Geofabrik](https://download.geofabrik.de/asia/malaysia-singapore-brunei.html) | `Gimano.Importer` (POIs, road segments), Valhalla (routing tiles) |

```bash
data/download-osm.sh        # fetch / refresh the extract into data/osm/
data/import.sh              # import with the local .NET SDK (Postgres + OpenSearch must be reachable)
data/import.sh --docker     # or as a container inside the compose stack
```

The importer is idempotent: places are upserted (and ones that disappeared from OSM are deleted),
road segments and the OpenSearch `places` index are rebuilt.

## Licence and attribution (ODbL)

OpenStreetMap data is © OpenStreetMap contributors and licensed under the
[Open Database License (ODbL) 1.0](https://opendatacommons.org/licenses/odbl/1-0/).
Gimano's own code is MIT; the **data** keeps its ODbL licence.

What this means for Gimano:

- **Attribution.** Every map view in the Android and iOS apps shows
  “© OpenStreetMap contributors” linking to <https://www.openstreetmap.org/copyright>.
  Any other place we display OSM-derived data (e.g. a web live map, screenshots in docs) must do the same.
- **Derived databases are share-alike.** Our Postgres `places` / `road_segments` tables, the OpenSearch
  `places` index and the Valhalla tiles are *derivative databases*. If we publicly distribute them
  (e.g. ship a data dump), they must be offered under the ODbL. Serving results through the API
  (a "Produced Work") only requires attribution.
- **Community reports are not OSM data.** User-generated reports, jams and speeds are Gimano's own data
  and are not mixed back into the OSM-derived tables.
- Do not import data from sources whose licence is incompatible with the ODbL (e.g. Google Maps).
