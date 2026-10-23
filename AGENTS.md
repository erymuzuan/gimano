# AGENTS.md — Gimano dev infrastructure

Source of truth for the product: `docs/architecture.md`, `docs/api.md` (the mobile apps code against it —
change it deliberately and say so). Backend: `backend/README.md`. Data/licensing: `data/README.md`.

## Layout

| Path | What |
|---|---|
| `backend/` | .NET 10 solution `Gimano.slnx` (Api, Worker, Importer, Core, Tests) |
| `android/` | Kotlin / Compose + MapLibre app |
| `ios/` | Swift / SwiftUI + MapLibre app |
| `deploy/` | `docker-compose.yml` (project `gimano`), Dockerfiles, `.env.example` |
| `data/` | `download-osm.sh`, `import.sh`; `data/osm/` is git-ignored (~250 MB PBF) |

## Runtime host: WS556 (Windows, Docker Engine, Linux containers)

- Reachable over Tailscale at **100.101.150.9**. No SSH and no remote Docker API.
- Repo checkout on WS556: `E:/project/work/gimano`. Shell there is **PowerShell**.
- Gimano runs as its own compose project `name: gimano` with these host ports:

| Service | Port(s) |
|---|---|
| Api | 5080 → base URL `http://100.101.150.9:5080/api/v1`, hub `ws://100.101.150.9:5080/hubs/live` |
| PostGIS | 5433 |
| RabbitMQ | 5673 (AMQP), 15673 (management UI) |
| OpenSearch | 9210 |
| Valhalla | 8002 |

- **Leave other projects' containers alone** (never stop / modify / prune them):
  `postgres18_dev` :5432, `sentinel-smartpay-eis-postgres` :5443, `opensearch1` :9201,
  `rabbitmq` :5672/15672, `redis` :6379, `compreface` :8000.
  Only ever use `docker compose -f deploy/docker-compose.yml …` (project `gimano`); no `docker system prune`.

### Driving WS556 through Orca terminals

```bash
# start a PowerShell terminal in a directory on WS556
orca terminal create --environment WS556 --worktree "path:E:/project/work/gimano" --title gimano-<task> \
  --command "docker compose -f deploy/docker-compose.yml ps" --json
orca terminal list  --environment WS556 --json                 # find the handle by title
orca terminal read  --environment WS556 --terminal <handle>    # read output
orca terminal send  --environment WS556 --terminal <handle> ...  # send more input
orca terminal close --environment WS556 --terminal <handle>    # always close when done
```

Only touch terminals you created (prefix titles with `gimano-`); other agents use WS556 too.

Typical cycle on WS556 (PowerShell, repo root):

```powershell
git pull
docker compose -f deploy/docker-compose.yml up -d --build          # needs deploy/.env with JWT_SIGNING_KEY
docker compose -f deploy/docker-compose.yml --profile tools run --rm importer
docker compose -f deploy/docker-compose.yml logs -f --tail 100 api worker
```

`data/download-osm.sh` is bash; on WS556 download with
`Invoke-WebRequest https://download.geofabrik.de/asia/malaysia-singapore-brunei-latest.osm.pbf -OutFile data/osm/malaysia-singapore-brunei-latest.osm.pbf`.

Smoke test from the Mac: `curl http://100.101.150.9:5080/readyz`.

## Mac (build machine) toolchain

| Tool | Location / id |
|---|---|
| .NET SDK 10 | `/opt/homebrew/bin/dotnet` (`cd backend && dotnet test`) |
| Xcode | set `DEVELOPER_DIR` to the Xcode.app in use (`export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`) |
| Android SDK | `/opt/homebrew/share/android-commandlinetools` (`ANDROID_HOME`) |
| JDK 17 | `/opt/homebrew/opt/openjdk@17` (`JAVA_HOME`) |
| Docker | **not installed on the Mac** — use WS556 |

## Test devices

| Device | Id |
|---|---|
| Samsung S24 (Android) | `adb -s RFCXA0LVBTE …` |
| iPhone 16 (iOS) | `xcrun devicectl … --device 00008140-00154CA90A8B001C` |

Phones reach the backend over Tailscale at `http://100.101.150.9:5080` (plain HTTP in dev:
Android needs `usesCleartextTraffic`/network-security-config, iOS an ATS exception for that host).

## Rules

- No secrets in the repo: `deploy/.env` and `.env*` are git-ignored; JWT key is set per host.
- OSM data © OpenStreetMap contributors (ODbL) — keep attribution visible in every map view.
- Clean-room: no third-party navigation app assets/code/copy (see `docs/research/ux-study.md`).
