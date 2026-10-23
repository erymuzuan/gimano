# UX study: community navigation apps (clean-room)

Gimano aims to feel familiar to people who already drive with a community
navigation app such as Waze. This document records **observed behaviour only**,
written in our own words, from:

- driving the Android app on a personal device (Samsung S24) over `adb`
  (screens + accessibility tree, no decompilation), and
- loading the public Live Map web page in a browser and watching its requests.

No code, binaries, icons, artwork, map data, copy text or trade dress from any
third-party product is included in this repository. Screenshots taken during
the study were kept locally and are not committed. Gimano uses its own name,
colours, icons and illustrations.

## 1. Home (idle) screen

| Element | Observed behaviour | Gimano equivalent |
|---|---|---|
| Full-bleed map | Light, low-contrast basemap. Road shields for highways (e.g. `E11`, `E23`). Live traffic painted as an orange/red band along congested roads. Other drivers shown as small avatars along roads. | MapLibre + OSM vector tiles, highway shields from `ref` tag, traffic overlay from backend, anonymised nearby-driver dots. |
| Top-left | Square "hamburger" button opens a side menu. | Same. |
| Top-right | Round floating buttons stacked: audio/music, sound on/off. | Round FABs: sound mode (on / alerts only / off), layers. |
| Bottom-right | Large rounded-square **report** button (yellow/amber, warning-triangle + plus). Always visible, both idle and while driving. | Large report FAB, Gimano accent colour. |
| Bottom sheet | Draggable sheet with handle. Collapsed: a pill search field "Where to?" with a mic icon. Expanded: a horizontal carousel of saved places (Home, Work, favourites) then a "Recent" list (title + address). | Same structure. |
| Above sheet | Speedometer chip (`0 km/h`), and a small vehicle/"car info" pill. | Speedometer that turns red above the speed limit; vehicle-type pill. |
| First-run | Modal dialogs for permissions or features with "Continue" / "No thanks" and "Don't show again". | Onboarding: location, notifications, Bluetooth audio. |

## 2. Side menu

Header with a greeting and **View profile**. Rows: *Plan a drive*, *Inbox*,
*Settings*, *Help and feedback*, *Shut down*. Occasionally a promo card.

Gimano: Profile, Planned drives, Inbox, Settings, Help, Exit.

## 3. Search

- Tapping the search pill opens a full-screen search with the field pinned on top.
- Autocomplete updates as you type. Each row shows: **name**, **distance from
  you** right-aligned (`910 m`, `5.1 km`, `260 km`), and a one-line address
  under it.
- Results mix brands (fuel stations), landmarks and addresses. Relevance is
  biased by proximity but distant exact-name matches still appear.

Gimano: `GET /api/v1/search?q=&lat=&lng=` backed by OpenSearch (`search_as_you_type`
on name, `geo_distance` decay on location, popularity boost).

## 4. Place card

Shown after picking a result: name, open/closed state, rating + review count,
distance, full address; action row **Save · Call · Share · More**; photos;
opening hours per weekday; amenities (toilets, cards accepted); editor
attribution; primary CTA **View routes**.

Gimano: name, category, `opening_hours` (OSM), phone, website, amenities from
OSM tags, "Save / Call / Share", CTA **Routes**.

## 5. Route preview ("trip overview")

- Header: `Your location → Destination`, back arrow.
- Map shows **2–3 alternatives**. The selected route is drawn thick in a saturated
  colour with traffic segments overlaid; alternatives drawn lighter. Each route
  has a callout bubble with its duration (`10 min`, `15 min`); the best one is
  labelled **Best**.
- Floating **Avoid** dropdown (tolls, highways, ferries…).
- Vehicle selector tabs (car / motorbike). Motorbike routing matters in Malaysia.
- Route card: big duration, distance on the right, "Via E11 LDP, Exit 1107 …",
  and a traffic description ("Best route, Typical traffic").
- Two buttons: **Leave later** (secondary) and **Go now** (primary). The
  primary button fills left→right as a **countdown and auto-starts** navigation
  if the user does nothing.

## 6. Navigation (driving)

- Top banner (dark): big manoeuvre arrow + distance (`240 m`), and a second
  strip "**then** ↰" for the following manoeuvre.
- Map tilts/follows heading; own vehicle is an arrow puck; route line purple.
- Speedometer bottom-left; report button bottom-right; sound buttons top-right.
- Bottom bar: **ETA clock time** (`8:13 AM`) large in the middle, `10 min • 3.7 km`
  under it; search (add stop) on the left; alternate-routes button on the right.
- Tapping the bottom bar expands: destination + via, **Add a stop**, **Find the
  best time to leave**, **Share drive**, and **Stop** / continue buttons.
- Tooltips introduce features ("See alternate routes while driving").

## 7. Reporting

Report button → bottom sheet "What do you see?" with a 3-column grid of large
illustrated tiles:

`Traffic · Police · Accident · Hazard · Closure · Blocked lane · Map issue · Bad weather · Animal`

Sub-types follow (e.g. Traffic → moderate / heavy / standstill; Hazard → object
on road, pothole, vehicle stopped on shoulder, flood…). Reports are one-tap while
driving with large targets.

Other drivers approaching a report see an alert card with **Still there? /
Not there** (thumbs) that decays or extends the alert's TTL.

## 8. Live Map web: network shape (public page)

The web map fetches overlay data by **bounding box**:

```
GET /live-map/api/georss?top=..&bottom=..&left=..&right=..&env=row&types=alerts,traffic
GET /live-map/api/reverse-geocoding?lat=..&lng=..&radius=..
```

Both endpoints are protected by bot mitigation (403 without a valid browser
token), so no payloads were collected, and we did not try to get around that.
The shape still tells us what the design needs: **viewport-bbox polling for
alerts + jams**, separate from routing. Gimano exposes `GET /api/v1/live?bbox=`
plus a WebSocket/SignalR push channel.

## 9. Malaysia specifics

- Units km / km/h; left-hand traffic; tolled expressways (`E1`…`E38`, PLUS,
  LDP, SPRINT, DUKE, SMART) with RFID/Touch 'n Go: an **avoid tolls** option and
  toll-plaza POIs matter.
- Motorcycles are a big share of traffic → motorbike profile.
- POIs drivers search for: PETRONAS/Shell/Petron/BHPetrol/Caltex/BHP fuel, R&R
  (rest & service areas on highways), mosques/surau, mamak/restaurants, malls,
  EV chargers, parking, hospitals, police stations.
- Languages: Bahasa Melayu, English, 中文, தமிழ்.
- Monsoon floods: a flood hazard sub-type matters.
