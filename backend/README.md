# Gimano backend

.NET 10 backend for Gimano: REST + SignalR API, background worker, and OSM importer.
Contract: [`docs/api.md`](../docs/api.md) · design: [`docs/architecture.md`](../docs/architecture.md).

```
backend/
  Gimano.slnx
  Directory.Build.props / Directory.Packages.props   (central package management)
  global.json                                         (.NET 10 SDK, Microsoft.Testing.Platform)
  src/Gimano.Core       domain, contracts (DTOs), EF Core DbContext + migrations, rules
                        (alerts, jams, OSM mapping, search query, Valhalla mapping), RabbitMQ/OpenSearch plumbing
  src/Gimano.Api        minimal APIs (/api/v1), JWT device auth, SignalR /hubs/live, Valhalla proxy, health, OpenAPI
  src/Gimano.Worker     RabbitMQ consumers: TrafficAggregator, AlertLifecycle, SearchIndexer (+ jam publisher, expiry sweeper)
  src/Gimano.Importer   OSM PBF → Postgres (places, road_segments) + OpenSearch `places` index
  tests/Gimano.Tests    xUnit v3 unit tests
```

## Run everything with Docker (recommended)

From the repo root:

```bash
cp deploy/.env.example deploy/.env        # set JWT_SIGNING_KEY (>= 32 chars)
docker compose -f deploy/docker-compose.yml up -d --build
data/download-osm.sh                       # ~250 MB Geofabrik extract → data/osm/
docker compose -f deploy/docker-compose.yml --profile tools run --rm importer   # ~2–5 min
curl http://localhost:5080/readyz
```

| Service | Host port (default) | Notes |
|---|---|---|
| api | 5080 | `/api/v1`, `/hubs/live`, `/healthz`, `/readyz`, `/openapi/v1.json`, `/scalar/v1` |
| postgis | 5433 | PostgreSQL 17 + PostGIS 3.5, db/user/password `gimano` |
| opensearch | 9210 | 2.x single node, security plugin disabled (dev only) |
| rabbitmq | 5673 / 15673 | AMQP / management UI, user `gimano` |
| valhalla | 8002 | builds routing tiles from the Geofabrik extract on first start (10–30 min) |
| worker | – | consumes `gimano.events` |

All ports are overridable in `deploy/.env`. Until Valhalla has finished building tiles,
`POST /api/v1/routes` returns **503** with a problem+json body and `/readyz` reports `valhalla: degraded`.

## Run the .NET apps locally against the stack

The `appsettings.json` defaults point at the compose host ports on `localhost`
(Postgres 5433, OpenSearch 9210, RabbitMQ 5673, Valhalla 8002). To use a remote Docker host
(e.g. WS556, see [`AGENTS.md`](../AGENTS.md)) override with environment variables:

```bash
export ConnectionStrings__Postgres="Host=100.101.150.9;Port=5433;Database=gimano;Username=gimano;Password=gimano"
export OpenSearch__Url=http://100.101.150.9:9210 RabbitMq__Host=100.101.150.9 Valhalla__Url=http://100.101.150.9:8002

cd backend
dotnet run --project src/Gimano.Api          # http://0.0.0.0:5080 (Development JWT key from appsettings.Development.json)
dotnet run --project src/Gimano.Worker
data/import.sh                               # (from repo root) runs the importer with the local SDK
dotnet run --project src/Gimano.Importer -- --dry-run   # parse the extract only, no DB needed (~30 s)
```

The API applies EF Core migrations on start (`Database:MigrateOnStartup`, default `true`); the importer does too.

## Build & test

```bash
cd backend
dotnet build
dotnet test            # Microsoft.Testing.Platform (configured in global.json)
```

## Configuration

| Key | Default | |
|---|---|---|
| `ConnectionStrings:Postgres` | `Host=localhost;Port=5433;…` | |
| `OpenSearch:Url` | `http://localhost:9210` | optional `OpenSearch:User` / `Password` |
| `RabbitMq:Host/Port/User/Password` | `localhost` / `5673` / `gimano` / `gimano` | |
| `Valhalla:Url` | `http://localhost:8002` | |
| `Jwt:SigningKey` | *(none; dev key in appsettings.Development.json)* | ≥ 32 chars, **required in Production** |
| `Jwt:Issuer` / `Jwt:Audience` / `Jwt:LifetimeDays` | `gimano` / `gimano-app` / `365` | |
| `Urls` | `http://0.0.0.0:5080` | bound on all interfaces so phones on the LAN/Tailscale can connect |

## Migrations

```bash
cd backend
dotnet tool restore
dotnet ef migrations add <Name> -p src/Gimano.Core -s src/Gimano.Api -o Data/Migrations
```

## Behaviour notes

- **Search** uses OpenSearch (`search_as_you_type` + gauss geo decay + popularity, soft MY preference).
  If OpenSearch is down it falls back to a Postgres `ILIKE` search ordered by distance.
- **Routing** proxies Valhalla (`auto` / `motorcycle` costing, `use_tolls/highways/ferry` = 0 when avoided),
  maps maneuvers to the contract, attaches active reports within 40 m of the route (direction-aware),
  overlays live jams as `trafficSegments`, re-scores durations and re-ranks (`best` first).
- **Traffic**: pings are snapped (≤ 25 m, heading-compatible) to ~300 m `road_segments`; a 5-minute rolling
  mean per segment; jam when `< 0.5 × free-flow` with ≥ 3 distinct users; jams recomputed every 30 s.
- **Alerts**: TTLs, “still there” extension (+50 % of base, max 3×), “not there” expiry (reputation-weighted ×2),
  150 m same-heading de-duplication — see `Gimano.Core/Alerts/AlertRules.cs`.
- **Countries** (MY/SG/BN) are resolved with simplified border polygons (`CountryResolver`), not admin relations.
