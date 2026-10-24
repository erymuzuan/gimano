# Manotu Desktop

Private event and route planning for organizers: kenduri, dinners, company events, bicycle rides,
motorcycle convoys and multi-day trips. Plans are published as signed, encrypted event packs (a link
or a `.manotu-event` file) that guests open in the Manotu app or the web viewer.

Contract: [`docs/events.md`](../docs/events.md). Product spec: [`docs/design/festival/spec.md`](../docs/design/festival/spec.md).
Tokens: [`docs/design-tokens.md`](../docs/design-tokens.md) (Cobalt).

## Layout

| Path | What |
|---|---|
| `crates/event-core` | Event model, validation, Ed25519 signing, DEFLATE + XChaCha20-Poly1305 packs, links, files, import decisions, test vectors |
| `crates/route-core` | Route model, precision-6 polylines, GPX import/export, elevation, stateless routing client (`store:false`, `/routes/match`) |
| `crates/local-store` | Encrypted persistence: SQLite rows of XChaCha20-Poly1305 blobs, data key in the OS keychain or derived with Argon2id; recovery file |
| `src-tauri` | Tauri 2 shell (`manotu-desktop`): drafts, editing ops + undo/redo, publishing, import, GPX, typed commands |
| `src` | TypeScript UI (Vite, MapLibre GL JS, no framework): library, planner, route builder, dialogs, settings |

## Build and run

Prerequisites: Rust (the toolchain in `rust-toolchain.toml` installs itself through rustup), Node 20+ with npm,
and the [Tauri 2 prerequisites](https://tauri.app/start/prerequisites/) for your OS.

```bash
cd desktop
npm ci
npx tauri dev                       # dev app with hot reload (Vite on localhost:1420)
npx tauri build --debug --no-bundle # debug binary: target/debug/manotu-desktop
npx tauri build                     # release bundle (unsigned; see Signing)
```

Checks (all from `desktop/` so `rust-toolchain.toml` applies):

```bash
cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
npm run build      # tsc --noEmit + vite build
npm test           # vitest (formatting, i18n)
```

Platform notes:

- **macOS**: WebKit webview. The data key goes to the login Keychain (service `app.manotu.desktop`).
- **Windows**: WebView2 (preinstalled on Windows 11; the installer bootstraps it elsewhere). Key in Credential Manager.
- **Linux**: WebKitGTK 4.1 (`libwebkit2gtk-4.1-dev`, `libayatana-appindicator3-dev`, `librsvg2-dev`, see the Tauri
  prerequisites). Key in the Secret Service (GNOME Keyring / KWallet) over D-Bus (`libdbus` is vendored). If no Secret
  Service is running, the app asks for a passphrase instead; it never stores data unencrypted.

Only the macOS build has been compiled and launched so far. Windows and Linux need their own native CI runs before
any release claim.

### Signing

Nothing is signed by this build (`bundle.macOS.signingIdentity` is `null`). macOS signing and notarization must use
**Erymuzuan Mustapa's individual Apple Developer team**, never STAFY COMPANY LIMITED (AGENTS.md). Verify the selected
team before any identifier, signing or upload step.

## Architecture

```
TypeScript UI (webview)  ──typed invoke──►  src-tauri commands  ──►  event-core / route-core / local-store
   MapLibre (tiles only)                         │                          │
                                                 ├─ HTTP: routing (store:false, coordinates + mode) and /readyz
                                                 ├─ native dialogs (open/save) and clipboard, from Rust
                                                 └─ SQLite file of encrypted blobs; key in OS keychain / Argon2id
```

- **Rust owns the document.** The open draft, its undo/redo history (100 snapshots) and autosave live in Rust
  (`core.rs`). The UI sends one `EditOp` per user action (`editing.rs`) and renders the `DocView` it gets back.
- **Edits touch only affected legs.** Moving a point invalidates its two legs, inserting splits one leg, reversing
  re-routes road legs (directional restrictions) but reverses sketches in place. Invalidated legs are `BELUM`
  (pending) and are never drawn; with *Ikut jalan* off they become explicitly labelled straight-line sketches.
  Late routing results are dropped by a per-leg generation counter.
- **Routing** is done from Rust with `route_core::HttpRoutingClient`: one `POST /routes` (`store:false`, no names)
  per pending leg, `POST /routes/match` for lines drawn in *Lukis* mode or GPX tracks snapped to roads.
- **Publishing** builds the exact `event_core::Event` from the draft (`assemble.rs`), uses `Event::next_revision`
  against the last published bytes, then `seal`, `encode_link` / `encode_file`. Unchanged content re-seals the same
  revision. Each event has its own Ed25519 seed and pack key, created on first publish and stored encrypted.
- **Import** uses `open_link` / `open_file` and `decide_import` (new / already / older / fork / newer / different
  organizer / expired), shows the organizer safety code and stores imported events read-only until `expiresAt`.

## Privacy boundary (AGENTS.md is non-negotiable)

- Events, stops, routes, notes, aliases and keys never reach a Manotu server, not even encrypted. The plan leaves the
  machine only inside a link or file the organizer copies or saves themselves. The UI says so on the publish screen.
- **Organizer-only notes** ("Nota penganjur sahaja") exist only in the local draft type. `event_core::Event` has no
  field for them, and a unit test checks they never appear in the signed bytes.
- Routing requests carry coordinates and travel mode only (`store:false`), never a stop label, event id or note.
- **The webview has no network access except the tile host.** CSP: `default-src 'self'`, `script-src 'self'`,
  `connect-src ipc: http://ipc.localhost https://tiles.openfreemap.org`, `img-src 'self' data: blob:
  https://tiles.openfreemap.org`, `worker-src 'self' blob:`, no frames, no objects. All HTTP to the API is done in Rust.
- **Keys never cross into JS.** Links (which contain the pack key) are written to the clipboard by Rust; the UI only
  learns the fragment length. The file secret code is shown only when the organizer presses *Tunjuk kod* (it must be
  read out or typed into another channel, §5.2). Files are suite 2: an 8-character code (`XXXX-XXXX`, generated once
  per event and stored with its keys, so every revision uses the same code) and Argon2id with a fresh salt per file;
  the import dialog also accepts the 52-character codes of older suite-1 files. Seeds and the data key never leave
  Rust and are zeroized on drop.
- **IPC is narrow.** `build.rs` declares every app command; `capabilities/main.json` grants exactly those to the main
  window. No `fs`, `shell`, `http`, `dialog` or `clipboard` permission is given to the webview (dialogs and
  clipboard are driven from Rust).
- **At rest, nothing is plaintext.** Each draft, imported pack and key set is one XChaCha20-Poly1305 blob bound to its
  row (`kind`, `id` as associated data). Titles, coordinates and expiry dates are inside the blob. SQLite runs with
  `journal_mode=DELETE`, `secure_delete=ON`, in-memory temp storage. Expired events, their keys and imports are
  purged on every unlock (published drafts expire at `expiresAt`; unpublished drafts have no expiry so planning a
  past-dated event never loses work).
- GPX export is plaintext with no expiry: the dialog says so, lists exactly what is written, strips timestamps and
  requires an explicit acknowledgement (also enforced in Rust).
- Online map tiles reveal the viewed area to OpenFreeMap, and the webview's HTTP cache may keep tiles on disk. Tiles
  can be switched off in Settings (plain background + stops). The style URL is fixed at build time because the CSP
  allowlists that one host.
- The organizer recovery file (`.manotu-recovery`) holds drafts and organizer keys, encrypted with a passphrase the
  organizer chooses (Argon2id 64 MiB / 3 passes, XChaCha20-Poly1305).

## What is not in this build

- Offline map downloads ("akan datang"); offline routing; an elevation service (ascent/descent show *Tidak tersedia*
  unless an imported GPX had elevation, and nothing is ever estimated as 0).
- Suggestions inbox (proposal files), recipient-specific key wrapping, passphrase-protected packs, attachments/photos.
- Fork resolution UI: an equal-revision import keeps both revisions and shows the newest stored one.
- Online place search; avoid tolls/highways/ferries options (not exposed for event routing).
- Packaging/notarization, Windows/Linux verification, screen-reader passes with VoiceOver/Narrator/Orca, dark mode.
- Moving the publishing role between devices is possible only through the recovery file (no device-transfer flow).

## Keyboard

| Keys | Action |
|---|---|
| ⌘S / Ctrl+S | Save draft |
| ⌘Z, ⇧⌘Z (Ctrl+Z, Ctrl+Shift+Z / Ctrl+Y) | Undo, redo |
| ⌘L / Ctrl+L | Lock |
| P / D / Esc | Point-to-point mode, draw mode, select mode |
| Arrow keys (Shift) | Move the selected point or stop 10 m (100 m) |
| Alt+↑ / Alt+↓ | Reorder the selected point or stop |
| Enter | Type coordinates for the selected point or stop |
| Delete | Delete the selected route point |

Every map pin is a focusable button; everything placed by dragging can also be typed or nudged.

## Licences

MapLibre GL JS (BSD-3-Clause), Nunito (SIL OFL, `src/fonts/OFL-Nunito.txt`), Tauri (MIT/Apache-2.0), RustCrypto
crates (MIT/Apache-2.0), rusqlite + SQLite (MIT / public domain), keyring (MIT/Apache-2.0). Map data
© OpenStreetMap contributors (ODbL), tiles by OpenFreeMap; attribution is always visible on the map.
