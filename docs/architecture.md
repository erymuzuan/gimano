# Gimano architecture

Gimano is an open-source, community-driven navigation app for Malaysia. It
works the way Waze users expect (see [research/ux-study.md](research/ux-study.md))
and is built entirely on open data from OpenStreetMap.

```
 ┌──────────────┐   ┌──────────────┐
 │ Android app  │   │   iOS app    │   Kotlin/Compose + MapLibre   |   Swift/SwiftUI + MapLibre
 └──────┬───────┘   └──────┬───────┘
        │ HTTPS JSON + SignalR (WebSocket)
 ┌──────▼──────────────────▼───────┐
 │          Gimano.Api             │  ASP.NET Core (.NET 10) minimal APIs, SignalR hub
 └──┬──────────┬─────────┬─────────┘
    │          │         │ publish
    │          │   ┌─────▼──────┐     ┌────────────────┐
    │          │   │  RabbitMQ  ├────►│ Gimano.Worker  │  traffic aggregation, alert lifecycle,
    │          │   └────────────┘     └──┬──────────┬──┘  search indexing
    │          │                         │          │
 ┌──▼──────────▼─┐  ┌──────────────┐  ┌──▼───────┐  │
 │ PostgreSQL 17 │  │  OpenSearch  │◄─┤          │◄─┘
 │  + PostGIS    │  │  (POI search)│  └──────────┘
 └───────────────┘  └──────────────┘
    ▲                       ▲
    │   ┌───────────────────┴──┐       ┌──────────────┐
    └───┤   Gimano.Importer    │◄──────┤ Geofabrik OSM│  malaysia-singapore-brunei-latest.osm.pbf
        └──────────────────────┘       └──────────────┘
 ┌──────────────┐
 │   Valhalla   │  routing engine (car + motorcycle costing, avoid tolls/highways/ferries,
 └──────────────┘  alternates, turn-by-turn), built from the same OSM extract
```

## Components

| Component | Tech | Responsibility |
|---|---|---|
| `backend/src/Gimano.Api` | ASP.NET Core 10, EF Core + Npgsql/NetTopologySuite, OpenSearch.Client, RabbitMQ.Client, SignalR | Public REST API, auth, live push |
| `backend/src/Gimano.Worker` | .NET 10 worker service | Consumes queues: location pings → road-segment speeds → jams; report votes → alert TTL; POI changes → OpenSearch |
| `backend/src/Gimano.Importer` | .NET 10 console, OsmSharp | Reads OSM PBF, extracts POIs into Postgres and OpenSearch |
| `backend/src/Gimano.Core` | class library | Domain model, contracts shared by Api/Worker/Importer |
| PostgreSQL + PostGIS | 17 / 3.5 | Users, places, reports, votes, jams, traffic segments |
| OpenSearch | 2.x | `places` index: search-as-you-type + geo-distance decay |
| RabbitMQ | 4.x | `gimano.events` topic exchange |
| Valhalla | 3.x | Routing; the API proxies it and decorates routes with alerts and traffic |
| Map tiles | OpenFreeMap vector tiles (dev) / self-hosted PMTiles (prod) | Basemap rendered by MapLibre on device |

## Messaging (RabbitMQ)

Exchange `gimano.events` (topic, durable).

| Routing key | Producer | Consumer | Payload |
|---|---|---|---|
| `location.ping` | Api (`POST /api/v1/locations`) | Worker `TrafficAggregator` | `{userId, lat, lng, speedKmh, heading, ts}` |
| `report.created` | Api | Worker `AlertLifecycle`, Api SignalR fan-out | `Report` |
| `report.voted` | Api | Worker `AlertLifecycle` | `{reportId, stillThere, userId}` |
| `report.updated` | Api (merge / vote / thanks) | Api SignalR fan-out (`AlertUpdated`) | `Report` |
| `report.expired` | Worker | Api SignalR fan-out | `{reportId}` |
| `jam.updated` | Worker | Api SignalR fan-out | `Jam` |
| `jam.cleared` | Worker | Api SignalR fan-out (`JamUpdated` with `level: 0`) | `Jam` |
| `place.changed` | Importer / Api | Worker `SearchIndexer` | `{placeId}` |

## Traffic model

1. Clients send a location ping every 5 s while navigating (batched, max 12 per request).
2. Worker snaps each ping to the nearest `road_segment` (PostGIS KNN, ≤ 25 m,
   heading-compatible) and keeps a 5-minute rolling mean speed per segment.
3. A segment is a **jam** when `mean_speed < 0.5 × free_flow_speed` with ≥ 3
   distinct users; level 1–4 by ratio. Adjacent jam segments are merged into a
   `Jam` polyline and published.
4. Route responses from Valhalla are re-scored: per-edge time is scaled by the
   live speed factor where available, then alternatives are re-ranked (the best
   one is labelled `best`).

## Alert lifecycle

- New report: TTL depends on type (police 30 min, accident 60 min, hazard 60 min,
  traffic 20 min, closure 24 h, weather 120 min).
- "Still there" extends TTL by 50% of base (max 3× base); "Not there" ×2 from
  distinct users expires it. Reporter reputation weights votes.
- Duplicate suppression: same type within 150 m along the same heading merges
  into the existing report (`confirmations++`).

## Malaysia-first data

- Extract: `https://download.geofabrik.de/asia/malaysia-singapore-brunei-latest.osm.pbf`
- Places are tagged with `country` (MY/SG/BN) by point-in-polygon on admin
  level 2; the default search scope is MY.
- POI categories in [api.md](api.md#place-categories).
- Licence: OSM data © OpenStreetMap contributors, ODbL. Attribution is shown in
  the map view of both apps.
