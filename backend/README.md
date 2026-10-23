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
  src/Gimano.Api        minimal APIs (/api/v1), JWT device auth, SignalR /hubs/live, Valhalla proxy, health, OpenAPI,
                        agent API /admin/v1 + MCP server /mcp (docs/back-office.md)
  src/Gimano.BackOffice Blazor back office for maintainers: feedback, crash groups, agent runs/tokens (Identity + passkeys)
  src/Gimano.Worker     RabbitMQ consumers: TrafficAggregator, AlertLifecycle, SearchIndexer (+ jam publisher, expiry sweeper,
                        back-office retention sweep)
  src/Gimano.Importer   OSM PBF → Postgres (places, road_segments) + OpenSearch `places` index
  tests/Gimano.Tests    xUnit v3 unit tests
```

## Run everything with Docker (recommended)

From the repo root:

```bash
cp deploy/.env.example deploy/.env        # set JWT_SIGNING_KEY, POSTGRES_PASSWORD and RABBITMQ_PASSWORD
docker compose -f deploy/docker-compose.yml up -d --build
data/download-osm.sh                       # ~250 MB Geofabrik extract → data/osm/
docker compose -f deploy/docker-compose.yml --profile tools run --rm importer   # ~2–5 min
curl http://localhost:5080/readyz
```

| Service | Host port (default) | Notes |
|---|---|---|
| api | 5080 | `/api/v1`, `/hubs/live`, `/healthz`, `/readyz`, `/openapi/v1.json`, `/scalar/v1` |
| postgis | 5433 | PostgreSQL 17 + PostGIS 3.5, db/user `gimano`; password from `deploy/.env` |
| opensearch | 9210 | 2.x single node, security plugin disabled (dev only) |
| rabbitmq | 5673 / 15673 | AMQP / management UI, user `gimano` |
| valhalla | 8002 | builds routing tiles from the Geofabrik extract on first start (10–30 min) |
| backoffice | 5090 | maintainer back office; Tailscale only (set `CONTACT_ENCRYPTION_KEY`, `BACKOFFICE_MAINTAINER`) |
| worker | – | consumes `gimano.events` |

Generate a separate random value for each required secret; no shared passwords or signing keys are committed. For existing volumes, changing `deploy/.env` alone does not rotate database or broker credentials: update the existing PostgreSQL role and RabbitMQ user first, then recreate the API/worker with the matching environment. Never delete volumes to rotate credentials.

All ports are overridable in `deploy/.env`. Until Valhalla has finished building tiles,
`POST /api/v1/routes` returns **503** with a problem+json body and `/readyz` reports `valhalla: degraded`.

## Run the .NET apps locally against the stack

The `appsettings.json` defaults point at the compose host ports on `localhost`
(Postgres 5433, OpenSearch 9210, RabbitMQ 5673, Valhalla 8002). To use a remote Docker host
(e.g. WS556, see [`AGENTS.md`](../AGENTS.md)) override with environment variables:

```bash
export ConnectionStrings__Postgres="Host=100.101.150.9;Port=5433;Database=gimano;Username=gimano;Password=${POSTGRES_PASSWORD:?Set POSTGRES_PASSWORD}"
export OpenSearch__Url=http://100.101.150.9:9210 RabbitMq__Host=100.101.150.9 Valhalla__Url=http://100.101.150.9:8002

export RabbitMq__Password="${RABBITMQ_PASSWORD:?Set RABBITMQ_PASSWORD}"
export Jwt__SigningKey="${JWT_SIGNING_KEY:?Set JWT_SIGNING_KEY (at least 32 characters)}"

cd backend
dotnet run --project src/Gimano.Api          # http://0.0.0.0:5080 (JWT key from the environment)
dotnet run --project src/Gimano.Worker
dotnet run --project src/Gimano.BackOffice   # http://localhost:5090; Development allows any email to register
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
| `RabbitMq:Host/Port/User/Password` | `localhost` / `5673` / `gimano` / *(none)* | |
| `Valhalla:Url` | `http://localhost:8002` | |
| `Jwt:SigningKey` | *(none)* | ≥ 32 chars, **required in every environment** |
| `Jwt:Issuer` / `Jwt:Audience` / `Jwt:LifetimeDays` | `gimano` / `gimano-app` / `365` | |
| `Urls` | `http://0.0.0.0:5080` | bound on all interfaces so phones on the LAN/Tailscale can connect |
| `Contact:EncryptionKey` | *(none)* | api + backoffice: base64 of 32 bytes; unset = volunteered contacts are dropped |
| `Admin:Enabled` / `AllowPublic` / `AllowedNetworks` | `true` / `false` / `[]` | api: guards `/admin` and `/mcp` |
| `BackOffice:Maintainers` | `[]` (`["*"]` in Development) | backoffice: emails allowed to register / sign in |
| `BackOffice:ApiBaseUrl` | `http://100.101.150.9:5080` | backoffice: shown in the MCP setup command |
| `DataProtection:KeysPath` | *(none)* | backoffice: persist cookie keys (set in compose) |

## Migrations

```bash
cd backend
dotnet tool restore
dotnet ef migrations add <Name> -p src/Gimano.Core -s src/Gimano.Api -o Data/Migrations
# back-office maintainer accounts (schema "backoffice"; applied by the back office on start)
dotnet ef migrations add <Name> -p src/Gimano.BackOffice -s src/Gimano.BackOffice -o Data/Migrations -c ApplicationDbContext
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
