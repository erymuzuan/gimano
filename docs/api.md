# Gimano API v1 (contract)

Base URL: `https://{host}/api/v1`. JSON, camelCase, UTF-8. Coordinates are WGS84
decimal degrees. Polylines use **Google encoded polyline, precision 6**
(same as Valhalla). Times are ISO-8601 UTC. Distances in metres, durations in
seconds, speeds in km/h.

Auth: `Authorization: Bearer <jwt>`. Anonymous use is allowed: the app calls
`POST /auth/device` on first launch and stores the token.

Errors: RFC 7807 `application/problem+json`.

## Auth & profile

```
POST /auth/device            {deviceId, platform:"android"|"ios", locale}
  → 200 {token, userId, displayName}

GET  /me                     → User
PATCH /me                    {displayName?, mood?, vehicle?:"car"|"motorcycle", locale?}
```

`User = {id, displayName, mood, vehicle, points, level, joinedAt}`

## Saved places

```
GET    /me/places            → SavedPlace[]
PUT    /me/places/{kind}     kind = home|work      body: {name, lat, lng, address, placeId?}
POST   /me/places            body: {name, lat, lng, address, placeId?}   (favourite)
DELETE /me/places/{id}
GET    /me/recents           → Recent[]   (last 20 destinations)
```

`SavedPlace = {id, kind:"home"|"work"|"favorite", name, lat, lng, address, placeId?}`
`Recent = {id, name, address, lat, lng, placeId?, lastUsedAt}`

## Search & places

```
GET /search?q=petronas&lat=3.1&lng=101.6&limit=10&category=fuel
  → {results: PlaceSummary[]}

GET /search/nearby?lat=&lng=&category=fuel&radius=5000
  → {results: PlaceSummary[]}

GET /places/{id}?lat=&lng=   → Place      (lat/lng optional: fills distanceM)
GET /geocode/reverse?lat=&lng= → {name, address, lat, lng}
```

`PlaceSummary = {id, name, category, address, lat, lng, distanceM, brand?}`

`Place = PlaceSummary + {phone?, website?, openingHours?, openNow?:bool,
amenities: string[], tags: {k:v}, osmType, osmId}`

### Place categories

`fuel, ev_charging, rest_area, toll_booth, parking, restaurant, cafe, fast_food,
mall, supermarket, convenience, mosque, surau, temple, church, hospital, clinic,
pharmacy, police, school, university, hotel, atm, bank, car_repair, car_wash,
tourism, transit_station, airport, government, other`

## Routing

```
POST /routes
{
  "origin":      {"lat":3.13,"lng":101.62},
  "destination": {"lat":3.12,"lng":101.61, "placeId":"..."},
  "waypoints":   [],
  "vehicle":     "car" | "motorcycle",
  "avoid":       {"tolls":false,"highways":false,"ferries":false},
  "departAt":    null,        // ISO time for "Leave later"
  "alternatives": 2
}
→ 200 {"routes": Route[]}
→ 422 problem+json  no route between the points (Valhalla error)
→ 503 problem+json  routing engine unavailable (Retry-After: 30)
```

Routes are cached server-side for 3 h so that `/routes/{id}/reroute` can reuse the
original destination/vehicle/avoid options (404 when the id is unknown or expired).
`tollEstimateMyr` is currently always `null` (no toll tariff data yet).

```
Route = {
  id, label: "best"|"alternative",
  durationS, distanceM, trafficDurationS,
  summary: "Via E11 LDP, Exit 1107 SS2 Petaling Jaya",
  trafficDescription: "Typical traffic" | "Heavier than usual" | "Light traffic",
  hasTolls: bool, tollEstimateMyr?: number,
  polyline,                         // encoded, precision 6
  bbox: [minLng,minLat,maxLng,maxLat],
  legs: [{ maneuvers: Maneuver[] }],
  trafficSegments: [{fromIndex, toIndex, level:0..4}],   // indexes into decoded polyline
  alerts: AlertOnRoute[]
}
Maneuver = {type, instruction, streetName?, exitNumber?, roadRef?,
            distanceM, durationS, beginShapeIndex, endShapeIndex,
            verbalPre?, verbalPost?}
type ∈ depart, arrive, straight, slight_left, left, sharp_left, slight_right,
       right, sharp_right, uturn_left, uturn_right, roundabout_enter,
       roundabout_exit, merge, ramp_left, ramp_right, exit_left, exit_right,
       keep_left, keep_right, ferry
AlertOnRoute = Report + {distanceAlongRouteM}
```

```
POST /routes/{id}/reroute   {lat, lng, heading}   → {routes: Route[]}
GET  /routes/departure-times?originLat=&originLng=&destLat=&destLng=&date=&vehicle=
  → {slots:[{departAt, durationS}]}         // "Find the best time to leave"
  // date = yyyy-MM-dd (Malaysia time, default today); one slot per hour 06:00–22:00 MYT;
  // vehicle optional (car|motorcycle, default car)
```

## Live map (alerts, jams, drivers)

```
GET /live?bbox=minLng,minLat,maxLng,maxLat&types=alerts,jams,drivers
  → {alerts: Report[], jams: Jam[], drivers: Driver[], serverTime}
```

`Jam = {id, level:1..4, speedKmh, delayS, lengthM, polyline, street?, updatedAt}`
`Driver = {lat, lng, heading, mood}` (anonymised, rounded to 3 dp, ≥ 3 drivers per cell)

SignalR hub: `wss://{host}/hubs/live`

| Direction | Method | Args |
|---|---|---|
| client→server | `Subscribe` | `bbox: number[4]` (replaces the previous subscription) |
| server→client | `AlertCreated` | `Report` |
| server→client | `AlertUpdated` | `Report` |
| server→client | `AlertExpired` | `reportId` |
| server→client | `JamUpdated` | `Jam` (`level: 0` = jam cleared → remove it) |

## Reports

```
POST /reports
{ "type":"hazard", "subtype":"pothole", "lat":3.1, "lng":101.6,
  "heading":270, "comment":null }
→ 201 Report

POST /reports/{id}/votes     {"stillThere": true}   → Report
POST /reports/{id}/thanks                           → 204
```

```
Report = { id, type, subtype?, lat, lng, heading?, street?,
           reportedBy:{displayName, mood}, createdAt, expiresAt,
           confirmations, thanks }
type ∈ traffic, police, accident, hazard, closure, blocked_lane,
       map_issue, weather, animal
subtypes:
  traffic: moderate | heavy | standstill
  police:  visible | hidden | roadblock
  accident: minor | major | other_side
  hazard:  object_on_road | pothole | stopped_vehicle | roadkill | construction | flood | oil_spill
  closure: (free text in comment, optional endLat/endLng)
  blocked_lane: left | center | right
  weather: heavy_rain | fog | flood | haze
  animal:  on_road | on_shoulder
```

## Location pings

```
POST /locations
{ "routeId": "...", "points": [{lat,lng,speedKmh,heading,accuracyM,ts}] }   // ≤ 12
→ 202
```

## Enum wire format

All enums are lower `snake_case` strings exactly as listed above (`ev_charging`,
`blocked_lane`, `slight_left`, …). Unknown values are rejected with 400.
Optional fields are always present and `null` when absent.

## Health

`GET /healthz` (liveness) · `GET /readyz` (Postgres, OpenSearch, RabbitMQ, Valhalla)

`/readyz` returns 503 only when Postgres is down; OpenSearch / RabbitMQ / Valhalla
outages report `degraded` (200) since the API keeps working without them
(search falls back to Postgres, routing returns 503, live push pauses).
OpenAPI: `GET /openapi/v1.json`, interactive docs at `/scalar/v1`.
