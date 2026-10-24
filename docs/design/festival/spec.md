# Festival — desktop event and route planning specification

Status: proposal, not implemented. Revision 2 · 24 October 2026.

Companion: [illustrated design report](index.html). This extends the mobile Festival concept with a new Rust desktop app for macOS, Windows and Linux. No production API or privacy-policy change is made here.

## Product decision

Build **Manotu Desktop**, a map-based workspace for preparing private events and routes on a larger screen. Users can view maps, create an event and all its itinerary details, design routes, review contributions, and export encrypted event packs for mobile guests. No Manotu account is required. Desktop is an authoring companion, not a live tracking dashboard or a replacement for mobile turn-by-turn navigation.

Route creation should cover the useful planning jobs associated with tools such as Garmin Connect: choose a start, place and reorder waypoints, shape a route, inspect distance and elevation, and export it. This is a functional reference only, not a claim of Garmin feature parity. Do not copy Garmin assets, screenshots, text, branding or layouts. Do not integrate a Garmin account or service in v1.

## Platforms and implementation direction

Proposed stack: **Tauri 2 with a Rust application core**, a locally bundled TypeScript UI, and MapLibre GL JS for the map. This is a Rust-backed desktop app, not an all-Rust renderer. Rust owns persistence, cryptography, file parsing, route-provider boundaries and event publishing. UI code owns presentation and map interaction; it does not hold long-lived signing secrets.

Tauri uses platform webviews; its prerequisites describe WebView2 on Windows and WebKit dependencies on macOS/Linux. MapLibre GL JS renders maps using WebGL. This combination is a candidate, not evidence of identical graphics support on all machines. Gate the choice on real-platform rendering, input and accessibility tests. Pin dependencies after the spike, document their licenses and disclose platform dependencies without “100% open source stack” claims.

Proposed packaging targets, subject to minimum-OS and architecture testing:

| Platform | First target | Required validation |
|---|---|---|
| macOS | Apple Silicon and Intel where toolchains support it; signed/notarized app | Keychain, trackpad, Retina, VoiceOver, sandbox/file dialogs |
| Windows | x64 installer; ARM64 evaluated later | Credential protection, WebView2 provisioning, mouse/touchpad, scaling, Narrator |
| Linux | x64; initially a named Ubuntu LTS baseline, not every distribution | WebKitGTK/GPU compatibility, Secret Service availability, Wayland/X11, Orca screen reader |

Release support versions are an explicit open decision. CI must build and test each target natively; one successful Mac build is insufficient. macOS signing must use Erymuzuan Mustapa’s individual team, never STAFY COMPANY LIMITED. No signing or upload is authorized by this design task.

## Workspace and screens

Use a three-pane planning workspace: event/day/route outline on the left, dominant map canvas in the center, contextual details on the right. A bottom drawer switches between itinerary, elevation profile and validation. Toolbars contain explicit verbs; distinguish “Save draft locally” from “Publish revision to file.” Never imply that publishing uploads to Manotu.

At 1440 × 900, use approximately 240 px for the outline and 320 px for the inspector, with a flexible map. At 1024 × 768, collapse the inspector to a drawer and retain accessible list editing. Support resizable panes, visible focus, keyboard navigation, high-contrast states, scalable text, and OS-specific Cmd/Ctrl shortcuts. Every drag action has a keyboard or menu alternative.

Required screens:

1. **Local library:** recent local events/routes, New event, New route, Import pack, offline map readiness. No public event discovery or remote membership directory.
2. **Event setup:** name, optional alias, description, event template, language, time zone, full start/end dates, invitation opening and expiry. Templates: wedding/kenduri, dinner, company gathering, bicycle ride, motorcycle convoy and multi-day tour.
3. **Event planner:** day tabs, scheduled activities, stops, routes, attachments and private notes. Show validation beside the relevant field.
4. **Route builder:** route drawing/editing, ordered waypoints, travel profile, elevation, distance and per-leg summaries.
5. **Guest preview:** mobile-sized preview of the exact published fields; exclude organizer-only notes. Preview dates, parking arrival, offline limitations and expiry.
6. **Review and export:** change summary, unresolved conflicts, recipient/key choice, generic filename and export destination. Verify output before offering to reveal it in Finder/Explorer/file manager.
7. **Suggestions inbox:** manually imported proposals, signature status, base revision and side-by-side conflicts. No automatic message reading or remote inbox.
8. **Map/storage settings:** permitted map sources, explicit regional download, disk usage, key protection, lock app, local deletion and recovery export.

### Event details

- Event metadata: title, description, template, BM/English display language, IANA time zone, start/end and access expiry; event-specific organizer alias and key.
- Days and sessions: dated agenda, start/end windows, dwell time, ordering and linked route/leg. Validate overnight and cross-day sessions without assuming every activity ends on its start date.
- Stops: label, category, coordinates, arrival window, parking entrance, accessible drop-off, registration, checkpoint, fuel, water, lunch, rest, optional prayer stop and accommodation. Support plain-text instructions and photos with metadata stripped.
- Practical notes: surface/access constraints, parking instructions, organizer-supplied food and accessibility information, wet-weather alternative and locally authored contingency notes. Mark unverified information clearly.
- Routes: mode, endpoints, waypoints, geometry, origin of geometry, distance, optional elevation, quality warnings and a day association. A destination pin and a vehicle entrance may differ; show both explicitly.
- Privacy: organizer-only working notes are not exportable by default. Guest-visible notes and organizer working notes are different fields. No attendee names, contacts, RSVP roster, location history or automatic check-in data in v1.
- Attachments: only allowlisted bounded images/geometry; no scripts, embedded remote resources or executable content. Do not import an entire spreadsheet or existing backup.

## Route builder specification

### Core flow

1. Choose **New route**, a travel profile (bicycle, motorcycle, car or walk), and an optional event/day. A route can also exist privately on its own.
2. Choose a start by clicking the map, typing coordinates, selecting an existing event stop or searching locally available map data. Online search must be explicitly enabled because it discloses a query or area; never upload a private label.
3. Add ordered intermediate waypoints and a finish. Each waypoint is either a **stop** with an activity/dwell time or a **shaping point** controlling the path. Only stops appear in the guest agenda by default.
4. Calculate connected legs using the selected routing engine/profile. Preserve locked legs when editing another leg. Unsupported/disconnected legs stay visibly unresolved; never silently draw a straight line and label it a navigable road route.
5. Inspect route geometry, leg distances, warnings, optional elevation and estimated duration. Moving the pointer or keyboard focus over the elevation profile highlights the corresponding position on the map without creating location history.
6. Save a local draft, attach it to an event day, preview the guest version, then export a route or include it in a signed encrypted event revision.

### Editing tools and behavior

| Tool | Required behavior |
|---|---|
| Add/move/delete point | Click or coordinate entry; drag with visible preview; keyboard alternative; undo/redo. Cancel returns to the last valid geometry. |
| Reorder waypoints | Outline drag or Move up/down. Recalculate affected unlocked legs and explain changes. Never reorder automatically. |
| Shape route | Insert a shaping point on a chosen leg; distinguish it visually and semantically from scheduled stops. |
| Out-and-back | Create a candidate return path; recompute directional restrictions. Do not assume reversing coordinates makes a legal driving route. |
| Close loop | Route last point to start through the engine; show actual distance, not a guaranteed target distance. Automatic distance-target loop suggestions are deferred. |
| Reverse route | Reverse order then recalculate legal traversable legs. Preserve the previous draft until user accepts. Flag agenda timing for review. |
| Split by day | Split at a selected stop, preserve route continuity, and require full dates for arrival/departure. |
| Compare edits | Show old/new distance and geometry; no silent replacement of an already published route. |
| Manual track | Allow explicitly labeled sketch geometry for off-road planning. No legality, safety, routing or turn-by-turn claim; warn before export. |

Travel profiles must respect available engine data. Proposed options include avoiding tolls, highways, ferries and unpaved surfaces **only when supported** by the chosen engine/profile. Show unsupported controls as unavailable with an explanation; do not imply current API support for an unpaved-road preference. A bicycle route is not a motorcycle route. OSM access and surface data may be incomplete; organizer review is required.

### Summaries and elevation

Display total/per-leg distance, movement-time estimate separately from scheduled stops, and ascent/descent when elevation data is available. Document the calculation method, elevation dataset/license, sampling interval and smoothing policy before release. Missing elevation is “Unavailable,” not zero. Mark estimates and their travel-profile assumptions; imported GPX timestamps must not be mistaken for a future schedule. Do not fabricate elevation values for a draft.

No dependency on server-side caching of a private route. On-device routing and local elevation are the preferred target; engine choice, regional data size and licensing remain feasibility gates. If only stored geometry is available offline, users can inspect/edit points but calculations remain unavailable until a permitted engine is available. Do not advertise offline rerouting before it exists.

### GPX and interoperability

- Import GPX tracks, routes and waypoints locally with size/point-count limits, bounded XML parsing, external entities disabled and validation of coordinate ranges. Preserve the original as a local reference; preview before applying simplification or snapping.
- A GPX track is geometry, not necessarily a road route or a turn instruction set. Display its source and routeability explicitly.
- Offer GPX export for generic device interoperability. Remove timestamps and unnecessary identifying metadata by default. Let the user inspect exactly which route names, coordinates and notes will be exported.
- **GPX is plaintext and has no enforceable access expiry.** Show a clear warning and explicit confirmation before writing it. The encrypted `.manotu-event` pack is the recommended private sharing format. Export is a deliberate user action, never an automatic cloud upload.
- No promise of direct Garmin sync, FIT/TCX support, device-course compatibility or cue-sheet equivalence. Test exported GPX against a documented set of readers and publish limitations.
- Desktop and mobile use the same versioned event envelope, canonical signing format and route/stop IDs. Mobile verifies desktop-published packs without a login. Proposed itinerary limits remain 14 days/200 stops/20 MiB; route geometry needs a separate benchmarked point limit.
- Existing routing API supports at most five intermediate stops and a three-hour cache. Do not use it for private event routes. Any future authorized stateless service needs deliberate API changes; larger routes must partition legs without changing stop order, never silently truncate.

## Local architecture and security

Proposed source layout, to create only during a later implementation task:

```text
desktop/
  src/                 bundled UI and map presentation
  src-tauri/           Rust desktop shell; allowlisted commands
  crates/event-core/   validation, revisions, pack format and signing boundary
  crates/route-core/   route model, GPX parser, profiles and provider interface
  crates/local-store/  encrypted persistence and migrations
  tests/fixtures/      fictional shared desktop/mobile interoperability vectors
```

Logical path: bundled UI → narrow typed Tauri commands → Rust core → encrypted local storage / OS key store / explicitly chosen files. Map renderer gets only the view data it needs. Rust verifies file signatures, authorizes role-specific operations and handles secret keys. No generic command execution, unrestricted filesystem access or arbitrary remote URL opener is exposed to imported content.

Use an encrypted local database or encrypted event/route payloads with metadata minimization; select a reviewed implementation during the security spike. Account for SQLite WAL, journals, temp files, thumbnails, crash dumps and search indexes. Use OS key protection where available. If Linux secure key storage is unavailable, require a passphrase-protected vault or clearly block secret persistence; no silent plaintext fallback.

Bundle UI assets; disallow remote executable content, restrict CSP/IPC permissions, sanitize plain text, and do not put keys in web storage. Do not render arbitrary GPX descriptions as HTML. Treat local-protocol file access and attachment paths as untrusted. Restrict files to the selected workspace and explicit import/export picks.

Keep the single-publisher model. A guest pack does not convey organizer authority. Moving publication from phone to desktop requires an explicit encrypted transfer of organizer key material, safety-code verification and a warning to designate one active publishing device. There is no server lock preventing both copies publishing; equal-revision/different-hash imports must produce a fork conflict. Seamless multi-device coauthoring is out of scope. Losing the key without a recovery export requires a new event.

Desktop drafts autosave locally and survive restart. Exclude event data from app-controlled backups by default. Desktop OS backup/sync tools may copy arbitrary files; do not promise control over Time Machine, OneDrive or user backup tools. Avoid known cloud-synced workspace folders by default, encrypt at rest, explain the boundary and apply expiry on restore. Expiry cannot recall exported packs, plaintext GPX, screenshots or external backup copies.

## Sharing on desktop

Always support Save encrypted pack and Open containing folder. OS share sheets are optional enhancements, not a cross-platform guarantee. Users manually attach files in WhatsApp, Telegram, Gmail or other clients. Do not read messages, scrape groups, request contact access, send email automatically or assume Facebook supports file delivery in every surface. Use neutral filenames; share secrets separately or use recipient-specific key wrapping.

The app sends no event, private route or membership data to Manotu servers, including encrypted blobs. Online basemaps can reveal the viewed region, and online geocoding/routing can disclose queries/coordinates. Provide a visible online/offline indicator and explicit network settings; downloaded maps must respect licensing. The existing cached route path remains prohibited for private routes. No new server endpoint is proposed by this revision.

## Delivery phases and acceptance gates

1. **Cross-platform feasibility:** demonstrate MapLibre map rendering, keyboard controls, local file opening and secure storage on macOS/Windows/Linux. Benchmark local route/elevation candidates. Resolve licensing, dependencies and supported OS matrix.
2. **Local authoring prototype:** event wizard, day/stop editor, route builder with clearly labeled fixture geometry, guest preview and validation. Use fictional Malaysian examples only; no simulated control should imply a real backend operation.
3. **Interoperable MVP:** actual local route calculations where validated, bounded GPX import/export, encrypted event import/export, signed revision review, autosave/undo, and mobile interoperability.
4. **Release gate:** privacy network capture, parser fuzzing, key/vault tests, offline/missing-data flows, cross-platform accessibility and packaging verification. No release claim for incomplete offline navigation.

Acceptance criteria:

- A user on each target OS creates a two-day event with parking, a meal break and regroup checkpoints, designs and edits a route, and opens the resulting encrypted pack on Android/iOS.
- A standalone route is saved/reopened locally; converting it into an event does not publish it publicly.
- Dragging a shaping point, undoing it, changing mode and reversing a route never silently changes stop order or converts an unresolved leg into an apparently valid one.
- Route distance/elevation reflects actual data and known calculation assumptions; missing elevation/routing has a clear state.
- Malicious or oversized GPX/event files cannot fetch remote entities, execute content, escape the selected directory or cause unbounded decompression.
- Plaintext GPX export requires explicit acknowledgement; encrypted packs never include organizer-only notes or signing secrets.
- IPC permissions prevent arbitrary file/network access from imported content; no secrets appear in logs, web storage or crash diagnostics.
- Offline route inspection works on all platforms; unavailable calculations are visibly disabled. Map attribution remains visible.
- No private route/event content reaches Manotu caches, databases, logs or queues. Backup restore honors expiry before displaying data.
- Keyboard-only and screen-reader users can add, reorder and edit stops without map dragging. Test 200% text scaling and 1024 × 768 layouts.

## Claude `/design` prompt

Copy the following into Claude with the repository available. This is a design request, not permission to implement or deploy the app.

```text
/design
Design Manotu Desktop: a Rust-backed desktop app for macOS, Windows and Linux that lets people view maps, create complete private events and build routes. Read AGENTS.md, docs/design/festival/spec.md, docs/design/festival/index.html, docs/architecture.md, docs/api.md, docs/privacy-promise.md and docs/privacy-and-backup.md first. The desktop spec is the scope; existing API behavior is evidence, not permission to send private routes to it.

Produce a design specification and annotated, interactive HTML prototype; do not implement production app code, change API contracts, deploy, send messages or create accounts. Use fictional data. Mark simulated map/routing operations clearly. Write the design deliverables under docs/design/festival/desktop/ and link them from the existing report. Preserve unrelated work.

Product: a local-first organizer workspace for Malaysian kenduri/weddings, company dinners, bicycle rides, motorcycle convoys and multi-day trips. A guest uses the existing mobile app. No Manotu login is required. Support BM/English and Asia/Kuala_Lumpur dates by default, without assumptions about a participant’s religion or identity.

Technology direction: Tauri 2 shell and Rust domain/crypto/storage/file core; locally bundled TypeScript UI; MapLibre GL JS map. State that the renderer is not all Rust. Validate the webview/graphics/accessibility differences across platforms. Do not claim unvalidated native or offline capabilities.

Design a three-pane workspace: event/day/route outline, dominant map, contextual inspector; bottom itinerary/elevation/validation drawer. Support 1440×900 and 1024×768 with collapsed panes. Show local save and encrypted file publication as distinct actions. Include keyboard equivalents for every drag interaction, accessible focus, screen-reader order and large-text layouts.

Required screens: local library; new-event wizard; multi-day planner; stop/parking/meal/checkpoint inspector; route builder; GPX import preview; guest mobile preview; review and encrypted export; suggestion conflict review; map downloads and local vault settings.

Route builder: functionally inspired by the planning jobs of Garmin Connect, but use original Manotu layouts, assets and wording. No Garmin branding, login or direct sync. Support start/finish, ordered stop and shaping waypoints, dragging with undo/redo, mode selection (bicycle/motorcycle/car/walk), affected-leg recalculation, return/loop/reverse with directional restrictions, split by day, distance/per-leg duration, optional elevation profile linked to the map, and GPX import/export. Distinguish a track from a routable path, movement time from dwell time, and manual sketches from navigable routes. Show missing elevation as unavailable. Never invent routing results.

Event detail fields: title/description/template/language/time zone/start/end/access expiry; dated sessions; private stop coordinates and arrival instructions; parking entrance and accessible drop-off; checkpoint/fuel/water/lunch/rest/accommodation; organizer-supplied dietary/accessibility notes; route/day association; safe image attachments. Organizer-only notes must never enter guest packs. No guest roster, contacts, live location tracking or attendance history.

Privacy architecture: all event and route data stay on user-controlled devices/files, never Manotu servers, even encrypted. Rust owns signing keys and verification. Share signed encrypted .manotu-event packs via manually chosen messaging/email clients. Save-file fallback is required on all OSes. Single publisher; collaborators submit signed proposals. No server inbox or automatic transport. Explain first-use key verification and offline freshness limits.

Show warnings and recovery for: lost organizer key, device transfer/forked revisions, forwarded invitation, stale plan, rejected signature, expired event, unavailable map/routing/elevation, malformed GPX, and absent Linux secure key storage. Expiry cannot recall copies. GPX is plaintext: explicit export consent and metadata stripping. Online tiles/search reveal some location/query information. Existing API route caching is incompatible with private routes; prefer on-device computation and identify feasibility gaps.

Deliver: screen/interaction inventory, component/token spec, annotated desktop layouts, event and route data model, Rust/UI boundary diagram, state transitions, accessibility behavior, platform support matrix, threat-model gaps, staged implementation plan and testable acceptance criteria. Include a realistic two-day fictional cycling event and a one-day fictional kenduri scenario. Preserve OSM attribution in every map view. Use the existing report’s warm olive/cream/terracotta palette with restrained typography and substantial map space. Avoid generic analytics dashboards.

Keep proposals, verified repository behavior and unresolved decisions distinct. Cite primary technical sources. Do not add trackers, new proprietary SDKs, server storage exceptions or promises of guaranteed expiry/anonymity. Finish with files changed and validation performed.
```

## Technical references

- [Tauri prerequisites](https://tauri.app/start/prerequisites/): system dependencies and Windows WebView2. Reviewed 24 October 2026.
- [MapLibre GL JS](https://maplibre.org/maplibre-gl-js/docs/): browser map rendering with WebGL. Reviewed 24 October 2026.
- Repository `docs/api.md`, Routing: current route cache and waypoint constraints.
- Repository `docs/privacy-and-backup.md` and AGENTS.md: private-data and distribution rules.
