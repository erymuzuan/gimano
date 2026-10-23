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
PATCH /me                    {displayName?, mood?, vehicle?:"car"|"motorcycle"|"bicycle"|"walk", locale?}
```

`User = {id, displayName, mood, vehicle, points, level, joinedAt}`

## Saved places

```
GET    /me/places            → SavedPlace[]   (home, work first; then favourites by sortOrder)
PUT    /me/places/{kind}     kind = home|work      body: {name, lat, lng, address, placeId?}
POST   /me/places            body: {name, icon?, lat, lng, address, placeId?}   (favourite)
PATCH  /me/places/{id}       body: {name?, icon?, lat?, lng?, address?, sortOrder?}
DELETE /me/places/{id}
GET    /me/recents           → Recent[]   (last 20 destinations)
```

`SavedPlace = {id, kind:"home"|"work"|"favorite", name, icon, lat, lng, address, placeId?, sortOrder}`

Favourites carry a user-chosen label (`name`, max 40 chars, e.g. "Kampung", "Rumah Mertua",
"Sekolah Aina") and an `icon` from the shared set:
`home, work, kampung, mertua, star, heart, school, surau, food, gym, shop, hospital`
(default `star`). Home/work always have icon `home`/`work`. Max 50 favourites per user.
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

`PlaceSummary = {id, name, category, address, lat, lng, distanceM, brand?,
                source: "osm"|"community", status?: "private"|"pending"|"published",
                rating?: number, reviewCount: int, thumbnailUrl?}`

`Place = PlaceSummary + {phone?, website?, openingHours?, openNow?:bool,
amenities: string[], tags: {k:v}, osmType?, osmId?,      // osm* null for community places
description?, addedBy?: {displayName}, verifiedCount: int, photos: Photo[] (first 10)}`

Search returns OSM places, published community places, and the caller's own private and pending
places. When `results` is empty, the app offers **"Can't find it? Add a place"**.

## Community places (add a missing place)

Clean-room flow, modelled on how big map apps let people add missing places:

1. The user drops or drags a **pin** on the map (pre-filled with the reverse-geocoded address)
   and gives it a **name**. Optional: category, description, phone, website, opening
   hours, amenities, photos.
2. **Visibility** question: "Make this place discoverable by other Gimano users?"
   - **No** → `private`: only the owner sees it in search, on the map and as a favourite target.
   - **Yes** → `pending`: automated checks run (below); if they pass, the place is shown to
     others with an "Unverified" badge, and nearby users can confirm it ("Is this place
     here?"). After **3 distinct confirmations**, or a moderator approval, it becomes `published`.
     **3 "Doesn't exist / Wrong location" votes** hide it for moderator review.
3. Anyone can then **suggest an edit** (hours, phone, category, location); owner edits apply directly,
   other users' edits need 2 confirmations.

```
POST   /places                        → 201 Place
  { name, category, lat, lng, address?, description?, phone?, website?,
    openingHours?: "Mo-Fr 08:00-18:00; Sa 08:00-13:00",     // OSM opening_hours syntax
    amenities?: string[], visibility: "private"|"public" }
PATCH  /places/{id}                   → Place        (owner only, community places only)
DELETE /places/{id}                   → 204          (owner only; published places become "closed", not deleted)
POST   /places/{id}/suggest-edit      → 202 {suggestionId}   (any user; any place incl. OSM → stored as a map-data suggestion)
POST   /places/{id}/verify            {exists: true|false, correctLocation?: bool} → 200 Place
GET    /me/places/contributed         → PlaceSummary[]
```

**Automated checks before `pending` → visible:**
- duplicates: the same or a similar name (trigram ≥ 0.6) within 100 m of an OSM or community place is
  rejected (409, with the existing place returned so the app can say "Is it this one?");
- rate limit: 10 new places per user per day, 3 public ones per hour;
- text filter (profanity/spam/URLs in names), Malay and English;
- coordinates inside MY/SG/BN and not in water (OSM water polygons).

## Reviews & photos

```
GET    /places/{id}/reviews?sort=recent|helpful&cursor=   → {reviews: Review[], next?}
POST   /places/{id}/reviews           {rating: 1–5, text?: ≤ 2000, photoIds?: string[]} → 201 Review
PATCH  /reviews/{id}  /  DELETE /reviews/{id}      (author only; one review per user per place)
POST   /reviews/{id}/helpful          → 204   (toggle)
POST   /reviews/{id}/flag             {reason: spam|offensive|off_topic|conflict_of_interest|other} → 202

POST   /photos   multipart/form-data: file (JPEG/PNG/HEIC/WebP, ≤ 15 MB), placeId, caption?
  → 201 Photo   (status "processing" → "published" | "rejected")
GET    /places/{id}/photos?cursor=    → {photos: Photo[], next?}
DELETE /photos/{id}                   (uploader only)
POST   /photos/{id}/flag              {reason}
GET    /media/{key}.webp              (public, immutable, Cache-Control: max-age=31536000)
```

`Review = {id, placeId, rating, text?, photos: Photo[], author: {displayName, level, reviewCount},
helpfulCount, createdAt, editedAt?, status: "published"|"hidden"}`
`Photo = {id, url, thumbUrl, width, height, caption?, uploadedBy: {displayName}, createdAt}`

**Photo processing (server):** apply EXIF orientation → **strip all metadata (incl. GPS)** →
resize to max 1600 px on the long edge → encode **WebP** (quality 80) + a 400 px thumbnail (WebP
q 70); reject < 200 px or undecodable. Stored by content hash in object storage
(`IMediaStore`: local volume by default, S3-compatible optional). The app also downsizes before
upload (≤ 2048 px JPEG/WebP) to save mobile data.

**Review moderation (clean-room, policy-based):** reviews are published immediately when
they pass automated checks (profanity/spam filter, links stripped, ≤ 1 review/user/place,
rate limit 20/day, and no reviews on a place the reviewer added in the first 24 h).
3 flags from distinct users hide the review pending moderation. Ratings are shown as the
mean with the count; places with < 3 reviews show "New".

Contributors earn points (place added +5, published +20, review +3, photo +2, helpful vote
received +1); these feed `User.points`/`level`.

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
  "waypoints":   [{"lat":3.14,"lng":101.69,"name":"Kedai Mak","placeId":null}],   // 0–5 intermediate stops, in order
  "vehicle":     "car" | "motorcycle" | "bicycle" | "walk",
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

**Travel modes** map to Valhalla costings: `car`→`auto`, `motorcycle`→`motorcycle`,
`bicycle`→`bicycle`, `walk`→`pedestrian`. `avoid.*` applies to car/motorcycle only; bicycle and
walk never use expressways or tolls. Live-traffic re-scoring and `trafficSegments` apply to
car/motorcycle only (empty for bicycle/walk). Walking/cycling instructions use the same
`Maneuver` shape.

**Multi-stop:** each waypoint becomes a leg: `legs.length == waypoints.length + 1`. Reroute keeps the
**remaining** stops (the client sends `nextStopIndex`). `nextStopIndex` always indexes the waypoints of the
**original** `POST /routes` request (also after earlier reroutes); omitted = 0. Each leg's `to.name` echoes the
waypoint/destination `name` from the request.

**Tolls & fares:** OSM has toll plazas but no tariffs. `tolls` lists every toll plaza the route
passes, in order, with the class-appropriate fare from Gimano's curated tariff table
(`data/tolls/my-toll-rates.csv`, every row cites its public source and effective date).
`tollEstimateMyr` is the sum **only when every plaza has a known fare** (`tollFareComplete: true`);
otherwise it is the sum of the known fares and `tollFareComplete: false`, and the app shows "from RM x".
Closed-system expressways (PLUS E1/E2 etc.) charge entry→exit pairs and are priced only
where the pair is in the table. On a priced pair the entry plaza is listed with `system: "closed_entry"`, `fareMyr: 0`
and the pair fare is on the exit plaza (`closed_exit`). Plazas with no tariff row are listed with
`system: "open"` and no `fareMyr`. `tollEstimateMyr` is `null` when no fare on the route is known. Vehicle class: car = class 1, motorcycle = class 0. Most Malaysian
tolls are free for class 0, but fares are taken from the table, not assumed. Bicycle and walk routes always have `tolls: []`.

```
Route = {
  id, label: "best"|"alternative",
  durationS, distanceM, trafficDurationS,
  summary: "Via E11 LDP, Exit 1107 SS2 Petaling Jaya",
  trafficDescription: "Typical traffic" | "Heavier than usual" | "Light traffic",
  vehicle: "car"|"motorcycle"|"bicycle"|"walk",
  hasTolls: bool, tollEstimateMyr?: number, tollFareComplete: bool,
  tolls: [{plazaId, name, highway?, lat, lng, fareMyr?: number, system: "open"|"closed_entry"|"closed_exit", source?}],
  polyline,                         // encoded, precision 6
  bbox: [minLng,minLat,maxLng,maxLat],
  legs: [{ durationS, distanceM, to: {lat, lng, name?}, maneuvers: Maneuver[] }],
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
POST /routes/{id}/reroute   {lat, lng, heading, nextStopIndex?}   → {routes: Route[]}
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

## Feedback

Every screen's menu has **Add feedback**. Anonymous users can send feedback too, with their device token.

```
POST /feedback
{ "category": "bug" | "idea" | "map_data" | "route" | "search" | "other",
  "message": "Jalan ini sudah ditutup sejak minggu lepas",   // 1–2000 chars, required
  "rating": 4,                       // optional 1–5
  "screen": "navigation",            // where it was opened: home|search|place|route_preview|navigation|report|settings|other
  "context": {                       // filled automatically by the app, user can untick "include diagnostics"
    "appVersion": "0.1.0 (1)", "platform": "android"|"ios", "osVersion": "16", "deviceModel": "SM-S921B",
    "locale": "ms", "lat": 3.118, "lng": 101.619, "routeId": "...", "placeId": "..."
  },
  "screenshot": "<base64 PNG/JPEG, optional, ≤ 1.5 MB decoded>" }
→ 201 {id, createdAt}
→ 400 problem+json (validation), 413 (screenshot too big), 429 (more than 10 per hour per user)

GET  /me/feedback            → FeedbackSummary[]   ({id, category, message, status, createdAt})
```

`status ∈ received, triaged, planned, done, wont_fix`. The backend stores feedback in Postgres
(screenshot as bytea, max 1.5 MB), publishes `feedback.created` on RabbitMQ, and can
optionally open a GitHub issue (`Feedback:GitHubRepo` + token configured; off by default; the
issue body never includes the device id or a precise location, which is rounded to 2 dp).

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
