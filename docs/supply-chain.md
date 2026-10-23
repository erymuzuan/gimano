# What Gimano is built from

Public, checkable list behind the website claims **"100% open source"** and **"No Israeli
technology"**. Every component, its licence and its origin. Update this file in the same PR that
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
| iOS app | Swift, SwiftUI, GRDB.swift | Apache-2.0 / MIT | swift.org, GRDB community |
| Encryption | libsodium | ISC | libsodium project |
| Signatures (backend) | NSec.Cryptography (Ed25519 over libsodium; NuGet `libsodium` 1.0.22, ISC) | MIT | Klaus Hartke (open source) |
| CBOR (backend, App Attest) | System.Formats.Cbor | MIT | .NET Foundation |
| Device attestation | Google Play Integrity API / Apple App Attest | platform services | Google / Apple (verdicts only; no SDK on the server) |
| Images | SkiaSharp / Skia | MIT / BSD-3 | .NET Foundation / Skia project |
| Font | Nunito | SIL OFL | Open-source type project |

**Not used:** no advertising, analytics or tracking SDKs, and no closed-source map, traffic or
navigation SDKs. Gimano's traffic is built from its own community data (see `traffic.md`), not
bought from third-party traffic providers.

Optional services the *user* chooses for their own backups (Google Drive, iCloud) are
the user's own accounts. Gimano stores nothing there that it can read.

## Rule

New dependencies must be open source with an OSI-approved licence (fonts: OFL). Record their
origin here. Do not add technology from Israeli companies or Israeli-government-funded
projects, and do not add closed-source SDKs. When in doubt, raise it in the PR before merging.
