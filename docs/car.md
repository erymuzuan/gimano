# Manotu in the car: Android Auto & Apple CarPlay (spec, not built yet)

Design: canvas "Manotu App Design", page **Android Auto & CarPlay (design)**
(https://claude.ai/artifact/ACREFiVNz9gNyAbundwPWB). Boards: `CarFlow` (overview), `CarPhone`, `AaHome`,
`AaRoutes`, `AaNavigate`, `AaReport`, `CpList`, `CpRoutes`, `CpNavigate`, `CpReport`.
The boards still say "Gimano"; the shipped car UI uses the Manotu name and icon (`docs/rebrand-manotu.md`).

## 1. Goal and scope

The car screen is a **second view of the drive the phone already runs**. It shows the same route, the same reports
and the same on-device data. It is not a separate app and has no account or settings of its own.

**On the car screen (phase 1)**
- Pick a destination: Rumah, Kerja, favourites, recents. No typing while moving.
- Compare up to 3 routes and start one with one tap.
- Turn-by-turn: maneuver, distance, street, "kemudian" (next maneuver), ETA, traffic colours on the route, report
  icons on the map, speed and speed limit (when known), lane hint (when known).
- Report in one tap (6 types). Answer "Masih ada?" with Ya / Tiada.
- Mute / unmute voice. End the trip.

**Stays on the phone**
- Adding places, reviews, photos, feedback, settings, backup and restore.
- Typing a new search. The car keyboard is allowed only when the car allows it, which is usually only when parked.
- Planned drives, multi-stop editing, share my drive.
- While the car screen is active, the phone shows a calm "on the car screen" view (`CarPhone`).

**Phase 2:** instrument cluster / CarPlay Dashboard (next turn + ETA only), voice-assistant "navigate to"
intents, Android Automotive OS (built-in cars).

## 2. One drive session

- Phone and car share **one navigation session** (one route, one maneuver index, one voice). Starting, rerouting or
  ending on either side updates the other immediately.
- Voice prompts play **once**, through the car audio (audio focus: transient duck on Android, `.voicePrompt` +
  ducking on iOS).
- The map follows the car's day/night signal (`CarContext.isDarkMode` / `UITraitCollection` of the CarPlay
  window), not the phone's Theme setting.
- Disconnecting the car keeps the drive running on the phone, with no prompt. Connecting mid-drive picks up the
  current session.
- Location pings, reroutes and live alerts are the **same calls** as on the phone (`POST /locations`,
  `POST /routes/{id}/reroute`, `/hubs/live`). The car adds no new requests.

## 3. Screens and template mapping

| Manotu screen | Android Auto (Car App Library) | CarPlay (CarPlay framework) | Notes |
|---|---|---|---|
| Ke mana? (`AaHome`, `CpList`) | `MapWithContentTemplate` + `ListTemplate` | `CPListTemplate` pushed over `CPMapTemplate` | Rumah and Kerja first, then favourites, then recents. ≤ 6 rows while driving. ETA per row |
| Cari | `SearchTemplate` | `CPSearchTemplate` | Results from `GET /search`. Keyboard only when the car allows it |
| Route preview (`AaRoutes`, `CpRoutes`) | `MapWithContentTemplate` (route list) / `RoutePreviewNavigationTemplate` | `CPMapTemplate.showTripPreviews` | Max 3 routes. Line 1 duration + distance; line 2 "Tanpa tol" or "Tol RM x" (`from RM x` when `tollFareComplete` is false) + traffic. Button **Mula** |
| Navigating (`AaNavigate`, `CpNavigate`) | `NavigationTemplate` (`RoutingInfo`, `TravelEstimate`) | `CPNavigationSession` + `CPManeuver` + `CPTravelEstimates` | Map surface drawn by us (MapLibre) |
| Lapor (`AaReport`, `CpReport`) | `GridTemplate` | `CPGridTemplate` (≤ 8 buttons) | 6 tiles, one tap, closes itself |
| Masih ada? | `Alert` (Car App API level 5+) | `CPNavigationAlert` | Ya / Tiada |
| Cluster / Dashboard | cluster display (phase 2) | `CPTemplateApplicationDashboardScene` (phase 2) | Next turn + ETA only |

### 3.1 Ke mana?
- Rows: icon (flat pale cobalt, `docs/design-tokens.md`), name, one-line subtitle (area or "Kegemaran" /
  "Terkini"), ETA. The ETA comes from a cheap `POST /routes` (`alternatives: 0`), fetched only for visible rows,
  cached 5 min, and left out when offline.
- Empty state: "Simpan Rumah dan Kerja dalam telefon untuk pilihan pantas di sini."
- Action strip: **Cari**, **Lapor**. Map buttons: recenter, zoom in, zoom out.

### 3.2 Route preview
- Same request as the phone's route preview: vehicle from the phone's last mode (car / motorcycle), `avoid` from
  the phone's settings, `alternatives: 2`.
- The selected route is drawn `routeActive`, alternatives `routeAlt`, plus traffic colours and an on-map duration
  label per route.

### 3.3 Navigating
- Maneuver card: maneuver icon (our own glyphs, `docs/icons/`), distance, `streetName` / `roadRef` /
  `exitNumber`, and a "kemudian" row when the next maneuver is < 300 m after this one.
- Travel estimate: arrival time, remaining minutes, remaining km. Arrival time is green when traffic is normal.
- On the map surface (ours to draw): route with traffic colours, report icons from `alerts` and `/hubs/live`, the
  car position, speed limit roundel + current speed bottom-left of the visible area, and the
  **© OpenStreetMap contributors** line (always visible, never covered by a template).
- Action strip (≤ 4): **Lapor**, mute, **Tamat**. Map button: overview of the whole route.
- Speed limit and lane hints are shown **only when the route carries them**. Today `Route`/`Maneuver` have no
  speed-limit or lane fields (OSM `maxspeed` is used only for free-flow speed in the importer). Adding them is an
  API change to make deliberately in `docs/api.md`; until then the car UI hides both.

### 3.4 Lapor (6 tiles)

| Tile | `POST /reports` |
|---|---|
| Sesak | `traffic` (subtype from current speed: < 10 km/h `standstill`, else `heavy`) |
| Polis | `police` / `visible` |
| Kemalangan | `accident` / `minor` |
| Bahaya | `hazard` / `object_on_road` |
| Jalan ditutup | `closure` |
| Banjir | `hazard` / `flood` |

- Position and heading are taken **at the tap**, not when the screen opened.
- One tap sends, shows a 2 s "Terima kasih" toast and returns to navigation. The grid closes itself after 6 s with
  no tap. No subtypes, comments or photos in the car; the driver can refine on the phone afterwards (Inbox / My
  contributions).
- Offline: queued exactly like the phone's report queue.

### 3.5 Masih ada?
- Same rules as the phone's "Still there?" (`AlertAhead`): an alert on the route within 600 m ahead.
- At most one prompt per 2 km, auto-dismissed after 8 s (no answer = no vote). Never shown during a maneuver
  closer than 150 m.
- Ya → `POST /reports/{id}/votes {"stillThere": true}`; Tiada → `false`.

## 4. Driver-distraction rules (both platforms)

- Respect the platform's list and step limits. Read them at runtime (`ConstraintManager` on Android); never
  hard-code a bigger number.
- Labels ≤ 2 words in BM, ≤ 3 in EN. No body text, no scrolling text, no marketing.
- No keyboard unless the car allows it. No photos, reviews or ratings on the car screen.
- Touch targets as the templates give them; anything we draw on the surface is information only, not tappable.
- Both languages (`values-ms`, `Localizable` ms) follow the phone's app language.

## 5. Privacy (AGENTS.md rule; no exceptions)

- The car screen reads only the on-device database (Room / GRDB): Rumah, Kerja, favourites, recents. Nothing is
  synced or uploaded for it.
- **No car identity leaves the phone**: no car make/model, VIN, head-unit ID, Bluetooth name, car API level or
  "connected to car" flag in requests, logs, crash reports or feedback device info.
- No analytics events for car usage. No new endpoint, table or log line.
- Location pings while navigating are the same anonymous, short-lived pings as on the phone (`docs/traffic.md`).

## 6. Platform requirements

**Android Auto**
- `androidx.car.app:app` (Car App Library). A version with `MapWithContentTemplate` (1.4+) and `Alert` (car API 5).
  Older head units fall back to `PlaceListNavigationTemplate` / an in-surface prompt.
- Manifest: `CarAppService` with category `androidx.car.app.category.NAVIGATION`, `minCarApiLevel`,
  `automotive_app_desc.xml`, and the nav permission set (`ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE_LOCATION`).
- The map draws into the `SurfaceContainer` from `SurfaceCallback` (MapLibre to a virtual display / surface).
  Respect `visibleArea` / `stableArea`.
- Play: passes the Android Auto app quality review for navigation apps. Sideloaded APKs work on Android Auto only
  with developer settings ("Unknown sources"); document this in `docs/sideloading.md`.

**CarPlay**
- **CarPlay navigation entitlement** (`com.apple.developer.carplay-maps`), requested from Apple under
  **Erymuzuan Mustapa's individual team**, never STAFY. Verify the team before the request and before any signing.
- `CPTemplateApplicationScene` in the scene manifest; the map renders in the CarPlay window's root view controller
  (MapLibre `MLNMapView`).
- iOS 17 deployment target (current app target).
- AltStore / sideloaded builds cannot carry the entitlement: **CarPlay is App Store / TestFlight only.** Say so in
  `docs/sideloading.md` next to the iCloud limitation.

## 7. Build order

1. Shared: a navigation-session object the phone UI and the car UI both observe (Android: a bound service / flow;
   iOS: an `@Observable` session in `AppModel`). Required before either car UI.
2. **Android Auto first** (no Apple wait): Ke mana? → route preview → navigating → Lapor → Masih ada?.
3. Request the CarPlay entitlement in parallel; build CarPlay when it is granted.
4. Phase 2: cluster / dashboard, voice intents, Android Automotive OS.

## 8. Testing

- Android: Desktop Head Unit (DHU) at 800×480 and 1920×720, day and night, car API levels 3 and latest; then the
  S24 in a real car.
- iOS: CarPlay Simulator (Xcode Additional Tools) and the Simulator's CarPlay window; then the iPhone 16 in a real
  car (TestFlight).
- Cases: start on phone → connect car; start on car; disconnect mid-drive; reroute; offline start; report while
  offline; "Masih ada?" timeout; no Rumah/Kerja saved; BM and EN; very long street names; RTL not needed.
- Privacy check: capture traffic during a car drive and confirm the requests are identical to a phone-only drive.

## 9. Open questions

- Android Auto first, or both together once the entitlement arrives?
- Day/night from the car only, or also honour the phone's Theme setting when it is forced?
- Are these the right 6 report types for the car?
- Add `speedLimitKmh` and lane data to `Route` now (API change, needs importer + Valhalla `maxspeed` output), or
  ship without them?
- Cluster / dashboard in phase 1 or 2?
