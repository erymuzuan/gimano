# Gimano for iOS

Native iOS client for Gimano, the open-source (MIT) community navigation app for Malaysia.
Swift 6, SwiftUI (iOS 17+), Observation, async/await, MapLibre Native.

- Behaviour spec: [`docs/research/ux-study.md`](../docs/research/ux-study.md)
- Visual system "Cobalt": [`docs/design-tokens.md`](../docs/design-tokens.md), icons in [`docs/icons/gimano-icons.json`](../docs/icons/gimano-icons.json)
- Backend contract: [`docs/api.md`](../docs/api.md)

## Open / build

The generated `Gimano.xcodeproj` is committed, so you can open it in Xcode directly.
If you change `project.yml`, regenerate it:

```sh
brew install xcodegen      # once
cd ios && xcodegen generate
```

Command line (Xcode lives at /Applications/Xcode.app, so override `xcode-select`):

```sh
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild test  -project Gimano.xcodeproj -scheme Gimano -destination 'platform=iOS Simulator,name=Gimano iPhone 17'
xcodebuild build -project Gimano.xcodeproj -scheme Gimano -destination 'generic/platform=iOS' -allowProvisioningUpdates
xcrun devicectl device install app --device <UDID> <DerivedData>/Build/Products/Debug-iphoneos/Gimano.app
```

Bundle id `my.gimano.app`, team `98KY9PMPX3`, automatic signing.

## Configuration

| Setting | Where | Default |
|---|---|---|
| API base URL | `GIMANO_API_BASE_URL` build setting → `GimanoAPIBaseURL` Info.plist key | Debug: `http://100.101.150.9:5080/api/v1/` (WS556 over Tailscale). Release: `http://localhost:5080/api/v1/` (placeholder until a production host exists) |
| Map style | `GIMANO_MAP_STYLE_URL` → `GimanoMapStyleURL` | `https://tiles.openfreemap.org/styles/liberty` |
| Overrides | In-app **Menu → Developer settings** (persisted in UserDefaults) | API URL, data mode, style URL, drive simulation |

Debug uses `Resources/Info-Debug.plist`, which adds an ATS exception for the cleartext dev host
(`100.101.150.9`) plus `NSAllowsLocalNetworking`. Release uses `Resources/Info.plist` and is ATS-strict.
The two plists are maintained by hand, so keep them in sync.

### Data modes

- **Auto** (default): calls the backend and falls back to demo data when it can't be reached
  (transport errors back off for 30 s) or when an endpoint is missing (404/5xx). A "Demo mode" badge shows on the map.
- **Live**: backend only, and errors are surfaced.
- **Demo**: fully offline. Kuala Lumpur places, live alerts/jams/drivers, and **real-road routes**.

Demo routes come from `Resources/DemoRoutes.json`: 8 destinations from a fixed KL origin, 2–3
alternatives each, in the exact `Route` contract shape (precision-6 polylines, maneuvers with shape
indexes). They were captured once from OpenStreetMap data through the public OSRM demo server
(© OpenStreetMap contributors, ODbL). **Demo mode never fabricates geometry.** A trip outside the captured set gets
the captured routes to the nearest captured destination, labelled "Demo route: …" in the preview.
Demo jams are 300–800 m slices of those routes; demo alerts and nearby-driver dots sit on their vertices.
A unit test asserts every one of them lies within 15 m of a captured polyline.

## Architecture

```
Gimano/
  App/            GimanoApp + RootView, AppModel (central @Observable store), AppConfig, LiveMapStore
  DesignSystem/   Cobalt tokens (colours, sizes, Nunito type), components, duotone icon renderer
                  (SVG path parser → SwiftUI Shape / CGContext), IconData.swift (generated)
  Models/         Codable DTOs matching docs/api.md, report catalogue
  Networking/     APIClient (actor, URLSession), GimanoRepository protocol,
                  LiveRepository (REST + device auth + 401 retry), DemoRepository (actor),
                  FallbackRepository (auto mode), LiveHubClient (SignalR JSON protocol over
                  URLSessionWebSocketTask: handshake, Subscribe(bbox), pings, reconnect), Keychain
  Navigation/     Polyline (precision 6), Geo, RouteTracker (snap/progress), OffRouteDetector,
                  LocationPingBatcher (5 s / 12 per POST), formatters, VoiceGuide (AVSpeechSynthesizer
                  en/ms), LocationService (CoreLocation, background while navigating), DriveSimulator,
                  NavigationSession
  Map/            MapLibre MLNMapView in UIViewRepresentable. Layers: jams, routes + traffic, alerts,
                  drivers, destination, puck. Camera modes. Duration-bubble projection.
  Features/       Home, Search, Place, Route (preview), Drive (navigation), Report, Menu, Settings
  Resources/      Info plists, Assets (Wau app icon + dark variant), Localizable.xcstrings (en + ms),
                  Fonts/Nunito.ttf (+ OFL licence), DemoRoutes.json
GimanoTests/      Swift Testing suites
```

Navigation flow: a `LocationFix` comes from CoreLocation or `DriveSimulator` and goes to `NavigationSession.handle`.
`RouteTracker` snaps it to the route and updates progress, upcoming maneuver and the "then" strip.
`VoiceAnnouncer` speaks early and near prompts. `OffRouteDetector` needs 3 consecutive fixes beyond
max(40 m, 1.2 × accuracy), with a 10 s cooldown, before calling `POST /routes/{id}/reroute` (falling back to `POST /routes`).
Alerts on the route within 600 m raise the "Still there? / Not there" card. Pings are batched to `POST /locations`.
The puck snaps to the route when within 30 m; otherwise it shows the raw fix.

## Icons

`DesignSystem/IconData.swift` is generated from `docs/icons/gimano-icons.json`:

```sh
python3 - <<'EOF'
import json
d=json.load(open('docs/icons/gimano-icons.json'))
out=['// Generated from docs/icons/gimano-icons.json — do not edit by hand.','','enum GimanoIconData {',
     '    static let grid: Double = %d' % d['grid'],'    static let paths: [String: String] = [']
out+=['        "%s": "%s",' % kv for kv in d['icons'].items()]
out+=['    ]','}','','enum GIcon: String, CaseIterable, Sendable {']+['    case %s' % k for k in d['icons']]+['}','']
open('ios/Gimano/DesignSystem/IconData.swift','w').write('\n'.join(out))
EOF
```

## Localization

`Resources/Localizable.xcstrings` holds English (source) and Bahasa Melayu. Voice guidance uses the
app language: `ms-MY` voice, falling back to `id-ID` and then English if no Malay voice is installed.

## Debug launch arguments (screenshots / demos)

`-GimanoDataMode demo|auto|live`, `-GimanoSimulateDrive YES`,
`-GimanoDemoScene search|place|route|route-far|nav|report|menu|overview` (DEBUG builds only), `-GimanoDemoQuery <text>`.

## Licences / attribution

Map data © OpenStreetMap contributors (ODbL), shown on the map. Tiles: OpenFreeMap. Renderer: MapLibre Native (BSD).
Nunito font: SIL Open Font License 1.1 (`Resources/Fonts/Nunito-OFL.txt`).
