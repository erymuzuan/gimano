# What Manotu is built from

Public dependency inventory for Manotu. Manotu-authored code is MIT-licensed; the distributed
apps also use proprietary platform libraries and services, listed below. Update this file in the same PR that
adds or changes a dependency; a reviewer checks it against the rule in `AGENTS.md`.

| Layer | Component | Licence | Maintainer / origin |
|---|---|---|---|
| Map data | OpenStreetMap | ODbL | OpenStreetMap Foundation (UK), global volunteers |
| Map rendering | MapLibre Native (Android/iOS) | BSD-2 | MapLibre community (Linux Foundation project) |
| Basemap tiles (dev) | OpenFreeMap | MIT / ODbL data | Independent open project (EU) |
| Routing | Valhalla | MIT | Valhalla open-source community |
| Backend | .NET 10 / ASP.NET Core | MIT | .NET Foundation |
| Database | PostgreSQL + PostGIS | PostgreSQL / GPL-2 | PostgreSQL Global Development Group, OSGeo |
| Search | OpenSearch | Apache-2.0 | OpenSearch Software Foundation (Linux Foundation) |
| Messaging | RabbitMQ | MPL-2.0 | RabbitMQ open-source project |
| Android app | Kotlin, Jetpack Compose, Room | Apache-2.0 | JetBrains, Android Open Source Project |
| iOS language / database | Swift, GRDB.swift | Apache-2.0 / MIT | swift.org, GRDB community |
| iOS UI framework | SwiftUI | proprietary Apple platform framework | Apple |
| Encryption | libsodium | ISC | libsodium project |
| Signatures (backend) | NSec.Cryptography (Ed25519 over libsodium; NuGet `libsodium` 1.0.22, ISC) | MIT | Klaus Hartke (open source) |
| CBOR (backend, App Attest) | System.Formats.Cbor | MIT | .NET Foundation |
| Device attestation | Google Play Integrity API / Apple App Attest | platform services | Google / Apple (verdicts only; no SDK on the server) |
| Images | SkiaSharp / Skia | MIT / BSD-3 | .NET Foundation / Skia project |
| Font | Nunito | SIL OFL | Open-source type project |

**Not used:** no advertising, analytics or tracking SDKs, and no closed-source map, traffic or
navigation SDKs. Manotu's traffic is built from its own community data (see `traffic.md`), not
bought from third-party traffic providers.

Optional services the *user* chooses for their own backups (Google Drive, iCloud) are
the user's own accounts. Manotu stores nothing there that it can read.

## Rule

New dependencies must be open source with an OSI-approved licence (fonts: OFL). Record their
origin here. Do not add technology from Israeli companies or Israeli-government-funded
projects. Closed-source SDKs require an explicit, documented exception; the approved exceptions
below do not authorize other SDKs. When in doubt, raise it in the PR before merging.

## Approved Google exceptions (23 October 2026)

The product owner approved retaining these proprietary Android libraries for now:

| Library / service | Purpose | Boundary |
|---|---|---|
| Google Play services Location | Device location for navigation | Does not authorize storing private location history on Manotu servers |
| Google sign-in / authorization libraries | User-authorized Google Drive backup access | Backups go directly to the user’s own Drive; credentials and backup contents are not proxied through Manotu |
| Google Play Integrity | App / device integrity checks | Integrity verification does not authorize uploading favourites, trip history or other private app data |

These dependencies remain subject to Google's terms; the MIT licence covers Manotu's own code,
not these libraries. Apple platform frameworks and services are also proprietary. Do not market
the complete app binary or dependency stack as “100% open source” or “no closed-source SDKs”.
No advertising or tracking SDKs are approved. The private-data rules in `AGENTS.md` remain in force.
A Google-free build is not promised by this decision. Revisit these exceptions when dependencies change.
