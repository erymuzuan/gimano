# Gimano API v1 (contract)

Base URL: `https://{host}/api/v1`. JSON, camelCase, UTF-8. Coordinates are WGS84
decimal degrees. Polylines use **Google encoded polyline, precision 6**
(same as Valhalla). Times are ISO-8601 UTC. Distances in metres, durations in
seconds, speeds in km/h.

Auth: `Authorization: Bearer <jwt>`. Anonymous use is allowed: the app calls
`POST /auth/device` on first launch and stores the token.

Errors: RFC 7807 `application/problem+json`.

Language: server-written text (problem `title`, inbox `title`/`body`, area kinds in `address` such as "Bandar raya" /
"City", reverse-geocode labels, search suggestions) follows `Accept-Language`: **English by default, Malay when the header
prefers `ms`** (`ms`, `ms-MY`, `zsm`). Tier names (baharu/perak/emas) are always Malay.

## Auth & profile

```
POST /auth/register          {publicKey: base64 Ed25519, platform:"android"|"ios", locale}
  → 200 {token, userId, displayName}          (new; see identity-and-reputation.md)
POST /auth/token             {userId, ts, sig}   sig = Ed25519 over "GIMANO-AUTH\n"+userId+"\n"+ts
                          or {publicKey, ts, sig}   sig over "GIMANO1-LOGIN\n"+publicKey+"\n"+ts (recovery-code restore)
  → {token, userId, displayName}   (401 auth-failed · 429 > 10 key sign-ins/min per IP)
POST /me/keys                {publicKey, sigByOldKey?}  attach/rotate the signing key (legacy accounts: JWT only)
POST /auth/device            {deviceId, platform:"android"|"ios", locale}   (legacy, kept for old app versions)
  → 200 {token, userId, displayName}

GET  /me                     → User
PATCH /me                    {displayName?, mood?, vehicle?:"car"|"motorcycle"|"bicycle"|"walk", locale?}
```

`User = {id, displayName, mood, vehicle, points, level, joinedAt}`

## Saved places, recents, private places: ON DEVICE ONLY

Personal data never lives on the server; see [privacy-and-backup.md](privacy-and-backup.md).
Favourites (Home, Work, "Kampung", "Rumah Mertua"…), recents, "Just for me" places and
planned drives are stored in the app's local SQLite DB and backed up to the user's own Google
Drive / iCloud / file.

The earlier `/me/places` and `/me/recents` endpoints are **deprecated** and kept only for a one-time
migration:

```
GET    /me/data/export    → {savedPlaces: SavedPlace[], recents: Recent[]}   (legacy server copy, if any)
DELETE /me/data           → 204   (permanently deletes the legacy server copy; the app calls it after importing)
DELETE /me                → 204   (deletes the account; the user's reports are anonymised, not deleted)
```

`/me/places*` and `/me/recents` answer `410 Gone` once the apps have shipped the local store.
Legacy shapes (migration only): `SavedPlace = {id, kind, name, icon, lat, lng, address, placeId?, sortOrder}`,
`Recent = {id, name, address, lat, lng, placeId?, lastUsedAt}`. Favourite labels/icons:
`home, work, kampung, mertua, star, heart, school, surau, food, gym, shop, hospital`.

**Contributor identity, signed requests, trust tiers and vetting rules:** see
[identity-and-reputation.md](identity-and-reputation.md). `User` gains
`tier: "baharu"|"perak"|"emas", accuracy: number|null, placesAccepted, verifierVotes, nextTier: {name, missing: string[]}`.

## Search & places

```
GET /search?q=petronas&lat=3.1&lng=101.6&limit=10&category=fuel&near=3.15,101.70&sort=relevance&exact=false
  → {results: PlaceSummary[], correctedQuery?, suggestions?: Suggestion[], noMatch: bool, interpretation?}

GET /search/nearby?lat=&lng=&category=fuel&radius=5000
  → {results: PlaceSummary[]}

GET /places/{id}?lat=&lng=   → Place      (lat/lng optional: fills distanceM)
GET /geocode/reverse?lat=&lng=
  → {name, address, lat, lng, label?, houseNumber?, street?, area?, city?, postcode?, nearby?: PlaceSummary[]}
```

`PlaceSummary = {id, name, category, address, lat, lng, distanceM, brand?,
                source: "osm"|"community", status?: "pending"|"published",
                rating?: number, reviewCount: int, thumbnailUrl?,
                confirmationsWeighted?: number, required?: number,
                chainGroup?: {key, name, countNearby}, openNow?: bool}`

`openNow` is set only when the place has OSM `opening_hours` we can evaluate (Malaysia time); otherwise null.

### Finding places (`GET /search`)

Parameters: `q` (≤ 100 chars), `lat`/`lng` = the user (distances are measured from here), `near=lat,lng` = the map
centre (ranking origin when given), `limit` (≤ 50), `category`, `country` (MY/SG/BN), `sort=relevance` (default) or
`nearest`, `exact=true` (no spelling correction).

Steps, in order:
1. **Normalise**: case, accents, apostrophes, punctuation/hyphens/slashes → space, `&` = "dan". Names are also compared
   without spaces: "Family Mart" = "FamilyMart", "7 eleven" = "7-Eleven" = "7eleven".
2. **Aliases** from [`data/search/aliases.csv`](../data/search/README.md) (brand short names, renames such as
   tesco → Lotus's, dialect/abbreviations such as jln → jalan); every rewrite is searched next to the original.
3. **Typos**: per word, 0 edits up to 3 letters, 1 for 4–6, 2 from 7 letters (OpenSearch `AUTO:4,7`); 1 edit on the
   spacing-free name ("famly mart" → FamilyMart, "myddin" → Mydin, but "pak ali" never becomes "Pak Abu").
   **Results that only matched through typo tolerance always carry `correctedQuery`** (the top result's brand or name): when
   none of the top 3 contains the query literally (after normalisation and aliases; every word starting a word of the name,
   brand or address). Aliases are not corrections ("tesco" → Lotus's has no `correctedQuery`). With `exact=true` such
   results are dropped instead.
4. **Coordinates** ("3.1478, 101.6153", "3.1478 101.6153") and **Plus Codes** (full "6PH57VP3+PR", or short
   "7VP3+PR" + optional locality, recovered near `near`/the user) → one result, `category: "point"`, `id: "pt-lat,lng"`,
   `address` = the reverse-geocode label.
5. **Postcodes** ("47300") → `category: "postcode"` (centroid of the addresses carrying it).
   **Street addresses**: "12 Jalan SS2/24" → the OSM house address (`category: "address"`) when mapped, then the street;
   "Jalan SS2/24" → `category: "street"` (one result per street name per ~20–40 km, on the road), nearest first.
6. **Category words** (the whole query): minyak / stesen minyak / petrol → fuel; surau / masjid → surau+mosque;
   tandas / toilet → toilets; atm; parkir / parking; klinik; farmasi; kedai runcit; … (the `category` rows of aliases.csv).
   Nearest first within 5 km, widened to 15 then 50 km when there are fewer than 3. With two categories the first is
   primary: "surau" lists suraus first and a masjid only when it is ≥ 2 km nearer (and vice versa for "masjid").
7. **Brand + area**: a trailing or leading area name ("kfc ss2", "mydin shah alam", "minyak shah alam") → the rest of the
   query searched near that area's centre (within 10 km), unless some result's whole name already equals the query
   ("Hospital Putrajaya").
8. **Ranking**: a whole-name or brand match and areas beat names that merely contain the word; distance (from `near`, else
   the user) and popularity add to that. **Chains are always nearest-first**: results with the same spacing-free name keep
   the positions ranking gave them but are re-ordered by distance, and get `chainGroup: {key, name, countNearby}`
   (outlets within 10 km), so the app can show "FamilyMart · 12 berdekatan". Same name within 50 m → one result.
9. **Never empty**: when nothing matches, `noMatch: true` and `suggestions` (a category word inside the query, e.g.
   "kedai runcit pak ali" → nearby convenience stores). A did-you-mean place is only offered for a close spelling (same rule
   as 3, word by word) **and** within 3 km of the origin, unless it is a chain (then its nearest outlet); a different single
   shop farther away is never a correction. Unless `exact=true` it is searched instead and returned with `correctedQuery`
   (then `noMatch: false`). Nothing is ever substituted silently. When nothing else applies, `suggestions` falls back to
   popular categories nearby (kedai makan, stesen minyak, kedai runcit, with counts), so it is never empty.
   Category `count`s are places within 5 km with an OSM node and its building counted once.

`Suggestion = {label, query?, category?, placeId?, lat?, lng?, distanceM?, count?}`: a did-you-mean place
(`label`/`query` = its name, `placeId`, position, `distanceM` from the user) or a nearby category (`label` in the
request language, `query` = the category word, `category`, `count` = how many within 5 km).

`interpretation = {kind, normalized, categories?, area?, areaLat?, areaLng?}` with
`kind ∈ text, category, brand_area, category_area, street, address, postcode, coordinates, plus_code` (informational).

Result ids: `n…`/`w…`/`r…` OSM places and areas, `c_…` community places; `st-…` streets, `ad-…` addresses,
`pc-…` postcodes and `pt-…` points are search-only (no `GET /places/{id}`; use lat/lng).

### Reverse geocode ("Pilih pada peta")

`GET /geocode/reverse?lat=&lng=` is anonymous and never cached (`Cache-Control: no-store`); coordinates are not logged.
`name`/`address` are as before (a place within 40 m, else the street). Added: `label` ("Dekat 18, Jalan SS 2/24, SS2,
Petaling Jaya" / "Near …"), `houseNumber`, `street`, `area` (suburb/neighbourhood), `city`, `postcode`, and `nearby` =
places within 50 m, nearest first (max 5), for "Adakah ini FamilyMart?".
`city`/`area` come from the OSM administrative boundaries that contain the point (smallest first): `city` is the
nearest city/town inside the smallest containing boundary of admin_level ≤ 6 (else that boundary's name), `area` the
nearest suburb/neighbourhood inside the smallest containing boundary, so KLCC is "Kuala Lumpur", not a town across the
border. When there is no house address and no street within ~30 m, the label is built from the nearest named
place in `nearby` ("Dekat Suria KLCC, Kuala Lumpur").

### Search misses (anonymous zero-result counts)

When a search returns nothing, the server adds 1 to a counter keyed by **(normalised query text, ~11 km cell, ISO week)**
and nothing else: no user id or token, no IP, no exact location (the cell is lat/lng floored to 0.1°, or empty without a
location). Before counting, emails, phone numbers and digit-heavy tokens (house numbers, postcodes, codes) are removed; if
nothing is left, nothing is counted. Rows with a count below 5 are deleted once their week is over, and all rows after 26
weeks. Maintainers read them with `GET /admin/search-misses?week=2026-W43&minCount=5` (X-Admin-Key) to add aliases.

`confirmationsWeighted`/`required` are set only on places still being vetted (`/places/pending-nearby`, and
pending or audited items in `/me/places/contributed`), e.g. `1` of `3` → "1 daripada 3". Same values as the
`POST /places/{id}/verify` response.

`Place = PlaceSummary + {phone?, website?, openingHours?, openNow?:bool,
amenities: string[], tags: {k:v}, osmType?, osmId?,      // osm* null for community places
description?, addedBy?: {displayName}, verifiedCount: int, photos: Photo[] (first 10)}`

Search returns OSM places, published community places and the caller's own pending
places. The app merges in matches from its local favourites and private places **on the device**. When `results` is empty, the app offers **"Can't find it? Add a place"**.

## Community places (add a missing place)

Clean-room flow, modelled on how big map apps let people add missing places:

1. The user drops or drags a **pin** on the map (pre-filled with the reverse-geocoded address)
   and gives it a **name**. Optional: category, description, phone, website, opening
   hours, amenities, photos.
2. **Visibility** question: "Make this place discoverable by other Gimano users?"
   - **No** → private: stored **only on the device** (local DB, included in the user's backup) and never
     sent to the server.
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
    amenities?: string[] }                                   // always public; private places never reach the server
PATCH  /places/{id}                   → Place        (owner only, community places only)
DELETE /places/{id}                   → 204          (owner only; published places become "closed", not deleted)
POST   /places/{id}/suggest-edit      → 202 {suggestionId}   (any user; any place incl. OSM → stored as a map-data suggestion)
POST   /places/{id}/verify  (signed)  {answer: yes|no|unsure, nameCorrect?, locationCorrect?,
                                      proximity:{cell, withinM}, attestation?:{platform, token}}
                                      → 200 {status, confirmationsWeighted, required}
GET    /places/pending-nearby?cell=<geohash6>   → PlaceSummary[] (pending, excludes own)
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
GET    /photos/{id}                   → Photo   (poll after upload: status processing → published | rejected)
```

`Review = {id, placeId, rating, text?, photos: Photo[], author: {displayName, level, reviewCount},
helpfulCount, createdAt, editedAt?, status: "published"|"hidden"}`
`Photo = {id, url, thumbUrl, width, height, caption?, uploadedBy: {displayName}, createdAt, status}`
(`url`/`thumbUrl` are null while `status` is `processing`; uploads of HEIC or other undecodable images get `415`.)

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
tourism, transit_station, airport, government, other, area, toilets`

Search-only result kinds: `street, address, postcode, point` (see "Finding places").

`area` (search results only) is a city, town, suburb, village, neighbourhood or state/district from OSM
(`id` `n…`/`w…`/`r…`, `address` like "Bandar raya, Wilayah Persekutuan"). It cannot be chosen for a community place.
Clients must treat unknown categories as `other`. Search collapses results with the same name within 50 m.

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

## Share my drive (end-to-end encrypted live ETA)

The driver shares a link (WhatsApp etc.). The recipient opens it in any browser and sees the driver's position
and ETA until arrival. **Gimano's server only relays ciphertext it cannot read**, so this keeps the
privacy rule in AGENTS.md.

1. The app generates a random 256-bit key `K` **on the device** and calls `POST /shares`.
2. Share link: `https://www.bukitbunga.com/gimano/s/#<shareId>.<base64url(K)>`. The part after `#` is
   **never sent to any server** by browsers, so the key stays between the driver and the recipients.
3. Every 15 s while navigating, the app encrypts
   `{lat, lng, heading, speedKmh, etaIso, remainingM, destinationName, status: "driving"|"arrived"|"ended", ts}`
   with **XChaCha20-Poly1305** (libsodium `crypto_aead_xchacha20poly1305_ietf`, random 24-byte nonce,
   associated data = shareId) and PUTs it.
4. The viewer page (`site/gimano/s/index.html`, static, served from bukitbunga.com) reads the fragment, polls `GET`
   every 10 s, decrypts in the browser (libsodium.js, self-hosted next to the page) and shows the position on a
   MapLibre GL JS map with the ETA. It never sends the key anywhere.
5. The share ends on arrival, on Stop, when the driver taps "Stop sharing", or at `expiresAt` (max 12 h).

```
POST   /shares              {expiresInMinutes: 15..720}            → 201 {shareId, writeToken, expiresAt}
PUT    /shares/{shareId}    Authorization: Share <writeToken>
                            {nonce: base64, ciphertext: base64}     (≤ 1 KB)  → 204
GET    /shares/{shareId}    (public, no auth; CORS for the viewer origin) → 200 {nonce, ciphertext, updatedAt, expiresAt}
                                                                     → 404 unknown · 410 ended/expired
DELETE /shares/{shareId}    Authorization: Share <writeToken>       → 204
```

Server storage: **in memory only** (latest blob per share, with TTL; never Postgres, never logged), and a
rate limit of 3 active shares per user. `shareId` is 128-bit random and `writeToken` is 256-bit random.
After the share ends, `GET` returns 410 and the blob is dropped immediately.
Before the first `PUT`, `GET` returns 200 with `nonce`/`ciphertext` = null. Limits: `nonce` must be 24 bytes and
`ciphertext` ≤ 1024 bytes (413 above); `POST` above 3 active shares → 429; a wrong `writeToken` → 401. CORS on
`GET` allows only the viewer origin (`https://www.bukitbunga.com` in production, any origin in dev). The store is
per API process, so the API must run as a single instance (or move to a shared in-memory store if it scales out).

## Planned drives (on device)

Planned drives are personal data, so they are stored only in the local `planned_drives` table and backed up
with the rest. The app computes "Leave by" times by calling
`GET /routes/departure-times` (origin/destination are sent for the calculation only and not stored) and
schedules local notifications ("Bertolak sebelum 7:40 pagi untuk sampai 8:30 di Kerja"). A drive can be
one-off or repeat on chosen weekdays. Opening the notification goes straight to route preview.

## Feedback

Every screen's menu has **Add feedback**. Anonymous users can send feedback too, with their device token.

```
POST /feedback
{ "category": "bug" | "idea" | "map_data" | "route" | "search" | "other",
  "message": "Jalan ini sudah ditutup sejak minggu lepas",   // 1–2000 chars, required
  "rating": 4,                       // optional 1–5
  "screen": "navigation",            // home|search|place|route_preview|navigation|report|settings|other
  "device": {                        // ONLY when the user ticked "Sertakan maklumat peranti" (default ticked, shown in a preview)
    "appVersion": "0.2.0", "appBuild": 2, "platform": "android"|"ios",
    "osVersion": "16" | "iOS 26.1", "apiLevel": 36,            // apiLevel Android only
    "deviceModel": "SM-S921B", "manufacturer": "samsung",
    "screen": {"widthPx": 1080, "heightPx": 2340, "density": 2.625, "widthDp": 411, "heightDp": 891},
    "locale": "ms", "theme": "light"|"dark", "dataMode": "live"|"demo"
  },
  "location": {"lat": 3.12, "lng": 101.62},   // optional, separate tick "Sertakan lokasi kasar", rounded to 2 dp ON THE PHONE
  "contact": {                       // optional; only if the user wants to be contacted about THIS report
    "email": "ali@example.com",      // either or both; validated
    "whatsapp": "+60123456789",      // E.164
    "consent": true                  // required true when any contact field is present
  },
  "routeId": "...", "placeId": "...",
  "screenshot": "<base64 PNG/JPEG, optional, ≤ 1.5 MB decoded>" }
→ 201 {id, createdAt}
→ 400 problem+json (validation; contact without consent=true), 413 (screenshot too big), 429 (> 10 per hour per user)

GET  /me/feedback            → FeedbackSummary[]   ({id, category, message, status, createdAt, hasContact})
DELETE /feedback/{id}/contact → 204   (the user withdraws their contact details at any time)
```

`status ∈ received, triaged, planned, done, wont_fix`. Feedback is stored in Postgres (screenshot as bytea,
max 1.5 MB) and `feedback.created` is published on RabbitMQ. The optional GitHub-issue sink **never** includes
contact details, device model or location.

## Crash reports

No third-party crash SDK. The app writes a crash record **locally** (Android: `UncaughtExceptionHandler` +
`ApplicationExitInfo` for ANR/native exits; iOS: MetricKit `MXCrashDiagnostic`, delivered on the next launch).
On the next launch it asks *"Gimano terhenti tadi. Hantar laporan?"* with **Lihat butiran** (the exact JSON)
and a remembered choice: **Tanya setiap kali** (default) · **Sentiasa hantar** · **Jangan hantar**.

```
POST /crash-reports
{ "occurredAt": "2026-10-23T03:41:00Z",
  "kind": "crash" | "anr" | "native" | "hang",
  "exception": "java.lang.IllegalStateException", "message": "<first 500 chars, scrubbed>",
  "stack": "<≤ 32 KB, scrubbed>",
  "threads": "<optional, ≤ 32 KB>",
  "screen": "navigation", "uptimeS": 312,
  "device": { …same shape as feedback.device; always included for crashes, shown in the preview… },
  "contact": { "email"?, "whatsapp"?, "consent": true }      // optional, as for feedback
}
→ 201 {id, signature}      // signature = sha256 of exception + top 5 app frames, for grouping
→ 429 (> 20 per day per install)
```

**Scrubbing on the phone before sending:** coordinates (anything that looks like a lat/lng pair), emails, phone
numbers, tokens/JWTs, URLs with query strings and file paths under the user's data dir are replaced with `[redacted]`.
Crash records never include favourites, routes, search text or screenshots. Server retention: 90 days.

## Contact details (feedback & crash reports): the narrow exception

Contact details are the ONE kind of personal data the server accepts, and only when the user volunteers them on a
specific report:
- never prefilled and never stored on the user's pseudonym or profile: they belong to that one report;
- encrypted at rest (AES-GCM with a key from server config, not in the database);
- visible only to maintainers triaging that report; never in the GitHub sink, logs or analytics;
- **deleted automatically 30 days after the report is closed (done/wont_fix), and in any case after 90 days**,
  or immediately via `DELETE /feedback/{id}/contact` / `DELETE /me`.

## Inbox

Notifications about the user's own **public** contributions only. Nothing private is involved, so this fits the
privacy rule. Items are generated by the worker from contribution events, kept for **90 days**, and deleted by
`DELETE /me`.

```
GET  /me/inbox?cursor=       → {items: InboxItem[], next?, unread}
POST /me/inbox/read          {upTo: ISO time}   → 204   (everything created at or before upTo is read; never moves back)
```

`InboxItem = {id, kind, title, body, refType?, refId?, createdAt, read, count}`
`kind ∈ place_published, place_rejected, place_expired, report_thanked, review_helpful, edit_accepted, feedback_status, announcement`

- `report_thanked` and `review_helpful` are **aggregated per day** (Malaysia time) into one item, e.g. "24 drivers thanked
  you" (`count: 24`). Each new thank moves it to the top as unread.
- `place_published` / `place_rejected`: `refType: "place"`, `refId` = the community place id.
- `feedback_status`: `refType: "feedback"`.
- `announcement`: broadcast to everyone (a server-side table that operators fill), with `refType: "url"` and `refId` = a link when present.
- `title`/`body` follow the request's `Accept-Language` (English default, Malay when preferred). The app may also render
  its own text from `kind` + `count`.
- Newest first, keyset-paged with `cursor` (30 per page). `unread` counts everything newer than the read mark.

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
