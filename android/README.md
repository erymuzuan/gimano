# Manotu for Android

Native Android client for Manotu, open-source (MIT) community navigation for Malaysia.
Kotlin 2.3 · Jetpack Compose + Material 3 · MapLibre Native 13 · Retrofit/kotlinx.serialization ·
SignalR · DataStore · FusedLocationProvider. Package `my.gimano.app`, minSdk 26, target/compile 36.

## Build & run

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew assembleDebug testDebugUnitTest
./gradlew installDebug                                    # default API: http://100.101.150.9:5080/api/v1/ (Tailscale)
./gradlew installDebug -Pgimano.apiBaseUrl=http://192.168.1.20:5080/api/v1/
./gradlew installDebug -Pgimano.demoMode=true             # start in demo mode
./gradlew installDebug -Pgimano.mapStyleUrl=https://…/style.json
```

`local.properties` needs `sdk.dir=…`. Defaults live in `gradle.properties`. The server URL and
map style can also be changed at runtime in **Settings**.

**Cleartext HTTP (debug only).** `app/build.gradle.kts` generates the debug network-security config,
allowing HTTP to `100.101.150.9`, `10.0.2.2`, `localhost` and whatever host `gimano.apiBaseUrl` names.
Release builds use `src/main/res/xml/network_security_config.xml` (HTTPS only). If you change the server
URL at runtime in Settings to a host that isn't in that list, use HTTPS or rebuild with `-Pgimano.apiBaseUrl`.

**Website APK vs Google Play AAB.** `assembleManotuRelease` builds the website APK (manotu.app), which updates
itself from `manotu.app/download/version.json`. `bundleManotuPlay` builds the Google Play AAB: the same app,
key and version, minus the self-updater (`BuildConfig.SELF_UPDATE=false`, `DISTRIBUTION="play"`;
`src/play/AndroidManifest.xml` removes `REQUEST_INSTALL_PACKAGES` and the updater receivers). Play policy forbids
updating outside Play. `verifyManotuPlayPlayPolicy` runs before every Play bundle and fails the build if the merged
manifest still has the updater; `testManotuPlayUnitTest` runs `src/testPlay`.

## Demo mode

If the backend can't be reached, the app falls back to demo data by itself and shows a "Demo data"
pill. Demo mode can also be turned on in Settings. It uses an in-memory repository: sample POIs around
the Klang Valley, saved places, live alerts, jams and drivers. Routes are **real road geometry** captured once from
an OSM router (`app/src/main/assets/demo-routes/*.json`, origin SS2 Petaling Jaya, © OpenStreetMap
contributors, ODbL). Navigation in demo mode is a simulated drive along the route polyline.
To regenerate the routes (later against Manotu's own Valhalla):

```sh
python3 tools/capture-demo-routes.py [--osrm https://router.project-osrm.org]
```

## Icons & design

Cobalt tokens: `ui/theme/GimanoTokens.kt` (from `docs/design-tokens.md`); the font is Nunito
(OFL, `res/font`, licence in `assets/licenses`). Duotone icons are generated from `docs/icons/gimano-icons.json`:

```sh
python3 tools/gen_icons.py   # → res/drawable/gi_*.xml + ui/common/GimanoIconPaths.kt
```

The launcher icon ("Wau") is converted from `docs/brand/manotu/manotu-icon-android-foreground.svg`
(adaptive icon plus a monochrome themed layer).

## Layout

```
core/geo        GeoPoint, GeoMath, Polyline (precision 6)          ← pure Kotlin, unit-tested
core/format     distance / duration / ETA / report-TTL formatting (en + ms)
core/nav        NavigationTracker, OffRouteDetector, PuckPlacement, VoicePromptPlanner,
                LocationBatcher, ManeuverFormatter
data/model      DTOs for docs/api.md
data/remote     Retrofit API + OkHttp (Bearer token, re-auth on 401)
data/repo       Remote / Demo / Switching repositories, CannedRoutes
data/live       SignalR /hubs/live client
data/location   Fused location + DriveSimulator
nav/            NavigationController (drive state, voice, reroute, pings), foreground service, TTS
ui/             one persistent MapLibre map + screen overlays (home, search, place, route preview,
                navigation, report sheet, side menu, settings)
```
