# Events (Acara): private event packs, invitation links and route planning

Status: **contract for the first build** (branch `worktree-events`, 24 Oct 2026). Design: canvas pages
"Events · Acara (research & design)" and "Manotu Desktop (Rust) for events & routes"
(https://claude.ai/artifact/ACREFiVNz9gNyAbundwPWB), `docs/design/festival/` (report + `spec.md`).

Every implementation (desktop Rust core, web viewer, Android, iOS) must produce and accept **byte-identical**
results for the test vectors in `desktop/crates/event-core/tests/vectors/`. Change this file deliberately and
update the vectors in the same change.

## 1. Privacy rule (AGENTS.md, unchanged)

- An event, its stops, routes, guest-visible notes, organizer alias and keys **never reach a Manotu server**, not
  even encrypted. The plan travels inside the link fragment or a file the host sends through their own channels.
- Organizer-only notes are never written into a pack.
- Routing **does** use the Manotu server, statelessly: the request carries coordinates and travel mode only (no
  stop label, event id, alias or note), and the server stores nothing (`"store": false`, §6).
- No guest roster, contacts, RSVP, check-in or location history anywhere.

## 2. Primitives (libsodium family; all platforms already ship it)

| Use | Algorithm |
|---|---|
| Pack encryption | XChaCha20-Poly1305-IETF, 32-byte random key, 24-byte random nonce, AD = ASCII `manotu-event-v1` |
| Organizer signature | Ed25519 (one key pair **per event**, never reused across events) |
| Compression | raw DEFLATE (RFC 1951, no zlib/gzip header) of the plaintext before encryption |
| Revision hash | SHA-256 |
| File secret code | Argon2id13 (libsodium `crypto_pwhash`, opslimit 3, 64 MiB) over an 8-character code (§5.2) |
| Text encodings | base64url without padding (`b64u`); the safety-code alphabet for codes; Crockford base32 for suite-1 file codes |

Libraries: Rust `chacha20poly1305` (XChaCha20Poly1305), `ed25519-dalek`, `sha2`, `miniz_oxide`/`flate2`;
web `libsodium-wrappers` (already vendored in `site/manotu/s/vendor/`) + `DecompressionStream("deflate-raw")`;
Android lazysodium + `java.util.zip.Inflater(nowrap=true)`; iOS swift-sodium + `Compression` (`COMPRESSION_ZLIB`
is raw DEFLATE).

## 3. Layers

```
event JSON bytes (UTF-8)                      ← §4, the signed content
signed envelope JSON                          ← {"v":1,"event":b64u(eventBytes),"pub":b64u(32),"sig":b64u(64)}
  sig = Ed25519(sk, "manotu-event-v1\n" ‖ eventBytes)
raw DEFLATE(envelope bytes)
XChaCha20-Poly1305(key, nonce, AD="manotu-event-v1")
  → link  (§5.1)  or  file (§5.2)
```

The signature covers the exact event bytes, so no JSON canonicalisation is needed. Readers parse `eventBytes`
only **after** verifying the signature.

## 4. Event JSON (v1)

Field names are exact. Unknown fields are ignored (forward compatible). Times are RFC 3339; `start`/`end` and
session/arrival times carry the event's local offset (e.g. `2026-11-15T11:00:00+08:00`); `publishedAt` and
`expiresAt` are UTC (`Z`). Coordinates are WGS84 decimal degrees, at most 6 decimals.

```jsonc
{
  "id": "b64u(16 random bytes)",        // same for every revision of the event
  "rev": 3,                             // 1, 2, 3 … one per publication
  "parent": "b64u(SHA-256(eventBytes of rev-1))",   // null for rev 1
  "publishedAt": "2026-11-01T02:15:00Z",
  "template": "kenduri",                // kenduri|dinner|company|ride|convoy|trip|other
  "title": "Kenduri Aqil & Sofea",      // 1–120 chars
  "description": "…",                   // optional, ≤ 2000
  "lang": "ms",                         // ms|en (display language chosen by the host)
  "tz": "Asia/Kuala_Lumpur",            // IANA
  "start": "2026-11-15T11:00:00+08:00",
  "end": "2026-11-15T16:00:00+08:00",
  "expiresAt": "2026-11-16T08:00:00Z",  // access ends; default end + 24 h
  "organizer": { "alias": "Keluarga Aqil" },        // optional, event-only alias
  "stops": [{
    "id": "b64u(8 random bytes)",
    "kind": "parking",                  // parking|dropoff|entrance|venue|meal|checkpoint|fuel|water|rest|prayer|accommodation|start|finish|other
    "label": "Parkir tetamu",           // 1–80
    "lat": 2.987100, "lng": 101.793000, // the pin
    "entrance": { "lat": 2.986600, "lng": 101.794200 },   // optional vehicle entrance, navigate here if present
    "arrive": { "from": "2026-11-15T11:00:00+08:00", "to": "2026-11-15T16:00:00+08:00" },  // optional
    "dwellMin": 60,                     // optional
    "notes": "Masuk ikut pagar belakang sekolah.",   // optional guest-visible, ≤ 500
    "audience": "Semua"                 // optional free text ≤ 40 ("Keluarga", "Penunggang")
  }],
  "sessions": [{                        // optional agenda
    "id": "b64u(8)", "title": "Jamuan", "start": "…+08:00", "end": "…+08:00", "stopId": "…"
  }],
  "routes": [{                          // optional
    "id": "b64u(8)",
    "title": "Hari 1",                  // optional ≤ 80
    "mode": "bicycle",                  // car|motorcycle|bicycle|walk
    "day": "2026-11-15",                // optional local date
    "stopIds": ["…", "…"],              // ordered stops this route visits
    "polyline": "…",                    // Google encoded polyline, precision 6 (same as the routing API)
    "source": "routed",                 // routed|sketch|gpx  (sketch/gpx = not navigable turn by turn)
    "distanceM": 58200, "durationS": 13200,          // durationS optional (movement time only)
    "ascentM": 1120, "descentM": 570,   // optional; absent = unavailable, never 0
    "legs": [{ "fromStopId": "…", "toStopId": "…", "distanceM": 12400, "durationS": 2880, "source": "routed" }]
  }]
}
```

Reader rules fixed by the Rust reference (`desktop/crates/event-core`, see its `tests/vectors/README.md`):
optional fields are omitted when absent (`parent` is always written, `null` on rev 1) and readers accept missing
or `null`; text lengths count Unicode code points; ids decode to exactly 16 (event) or 8 (item) bytes and are
unique; stop references must resolve; unknown `template`/`kind` read as `other`, unknown `lang`/`mode`/`source`
are rejected; `distanceM`/`durationS`/`ascentM`/`descentM` are non-negative integers; rev 1 has `parent: null`
and later revisions must have a parent. Session title 1–120, alias ≤ 80, `dwellMin` ≤ 20 160.

Limits (reject above them): 14 days between `start` and `end`, 200 stops, 50 sessions, 20 routes, 50 000 polyline
points in total, 20 MiB file, 16 000-character link fragment.

## 5. Transport

### 5.1 Invitation link (default)

```
https://<site>/a/#1.<b64u(key)>.<b64u(nonce ‖ ciphertext)>
```

- `<site>` is `https://manotu.app` (links made before 24 Oct 2026 on `https://www.bukitbunga.com/gimano` keep working). The page is
  static (`site/manotu/a/`): browsers never send the fragment, and the page never transmits it or calls our API.
- Writers **simplify route geometry before signing** (§5.4), so most multi-day rides still fit in a link.
- If the fragment still exceeds 16 000 characters, the app offers the file instead, or a **stops-only link**
  ("Pautan tanpa laluan") after asking (§5.5).
- Phones register `/a/` as an app link (Android App Links / iOS Universal Links) so it opens in Manotu when
  installed; otherwise the page shows the plan with "Buka di Manotu / Google Maps / apl lain".

### 5.2 File `.manotu-event` (sensitive events)

```
suite 2 (written since 24 Oct 2026):  "MNTE" ‖ 0x01 (format) ‖ 0x02 (suite: code) ‖ salt (16) ‖ nonce (24) ‖ ciphertext
suite 1 (read only, older files):     "MNTE" ‖ 0x01 (format) ‖ 0x01 (suite: raw key) ‖ nonce (24) ‖ ciphertext
```

**Suite 2 (secret code).** The host sees an **8-character secret code** shown as two groups of 4, e.g. `K7M4-QX9P`:
8 characters drawn uniformly (CSPRNG) from the safety-code alphabet `23456789ABCDEFGHJKLMNPQRSTUVWXYZ` (no 0/O, 1/I),
i.e. 40 bits. The file key is

```
key = Argon2id13(password = the 8 code characters as ASCII, upper case, no separator,
                 salt = the file's 16-byte salt, opslimit = 3, memlimit = 67 108 864 bytes (64 MiB), outlen = 32)
```

(libsodium `crypto_pwhash`, `crypto_pwhash_ALG_ARGON2ID13`). The ciphertext is XChaCha20-Poly1305 of the same
deflated envelope as the link (AD `manotu-event-v1`) under that key. Readers accept the code case-insensitively,
ignore spaces and `-`, and reject any other character before deriving (`invalid_secret_code`); a wrong code fails as
`decrypt`. Each written file gets a fresh salt and nonce; the code itself stays the same for every revision of an
event (stored with the organiser's keys) so guests keep one code. The file key is independent of the link key.

Code rules: exactly 8 code characters after removing spaces and `-`; a 52-character code on a suite-2 file, an
8-character code on a suite-1 file, a wrong length or a look-alike (`0 O 1 I`) are all `invalid_secret_code`. An
Argon2 allocation failure is `key_derivation`. Events published before suite 2 get a new 8-character code on their
next file; the old 52-character code still opens the old file only.

The 40-bit code is a deliberate trade-off (owner decision, 24 Oct 2026): short enough to read out. Each guess costs
one Argon2id run (≈70 ms on a desktop core, slower on phones, 64 MiB each), so trying every code is ≈1 200 core-years;
a well-funded attacker with many machines can cut that a lot. For very sensitive events, share the link only with
trusted people. The web viewer does not open files (files are for Manotu users), so it needs no Argon2.

**Suite 1 (older files):** the 52-character Crockford base32 code *is* the 32-byte key (no derivation). Readers keep
opening these; writers no longer produce them.

The host sends the code through a different channel. MIME `application/vnd.manotu.event`.

### 5.3 Opening in the app (web viewer hand-off)

The web viewer's "Buka di Manotu" buttons use the app's custom scheme, carrying the **same fragment**:

```
manotu://event#1.<b64u(key)>.<b64u(nonce ‖ ciphertext)>
manotu://event?stop=<stopId>#1.<b64u(key)>.<b64u(nonce ‖ ciphertext)>   // per-stop button
```

- Apps register `manotu://` (Android intent filter `scheme="manotu" host="event"`, iOS `CFBundleURLSchemes`
  `manotu`) and read everything after `#` exactly like an `/a/` link (§5.1, §7). `stop` is optional and only
  selects that stop after import; ignore it if unknown. `gimano://event…` should be accepted the same way.
- The custom-scheme URL is only handed to the app on the device; it is never sent to a server.
- App Links / Universal Links on `/a/` (§5.1) remain the primary path; the scheme is the in-page fallback.

### 5.4 Route simplification (writers)

Before an event is signed, each route's `polyline` is simplified with Douglas–Peucker at a tolerance of **5 m**
(perpendicular distance on a local equirectangular projection of the decoded precision-6 points; the first and last
points are always kept). `distanceM`/`durationS` keep the routing values, not the simplified length. The output need
not be byte-identical across implementations; readers never re-simplify. A route the host imported from GPX is
simplified the same way.

### 5.5 Stops-only variant

A stops-only link carries the **same revision** with route geometry removed, so it stays short:

- The event JSON is the full event with every route's `polyline` removed (all other route fields kept, so guests
  still see the route list, distances and stops) and one extra top-level field
  `"variant": { "kind": "stops-only", "fullHash": "<b64u SHA-256 of the full event bytes>" }`.
- It is signed with the same organiser key; `id`, `rev`, `parent`, `publishedAt` are those of the full event.
- `polyline` is required on every route of a full event and must be absent on every route of a stops-only variant
  (errors `missing_polyline`, `variant_has_polyline`). `variant: null` or absent = full event. An unknown
  `variant.kind` is rejected (`unsupported_variant`) on purpose: unlike other unknown fields, a future kind must not
  be read as a full event by an older app. A malformed `fullHash` is `invalid_event`.
- The reference writer puts `variant` last; other writers need not match its bytes, because the revision hash is
  `fullHash`, not the variant's own hash.
- Offer it only when the full link is too long and the stops-only link fits (for short routes it can be longer).
- Its **revision hash** (§7, the value a later revision's `parent` refers to) is `variant.fullHash`, not the hash of
  its own bytes.
- The file (§5.2) always carries the full event. Hosts send the stops-only link plus the file for those who want the
  map lines.

## 6. Routing API additions (stateless; see docs/api.md)

- `POST /routes` gains `"store": false`. Then nothing is cached (no reroute: `/routes/{id}/reroute` → 404),
  up to **25** waypoints are allowed, and the route ids start with `x-`. Default `true` keeps today's behaviour.
- New `POST /routes/match`: snap a drawn line to roads (Valhalla `trace_route`/`trace_attributes`,
  `shape_match: map_snap`). Stateless, never cached or logged.

```
POST /routes/match
{ "points": [{"lat":3.1,"lng":101.7}, …],   // 2–5000, in drawing order
  "vehicle": "bicycle" }                    // car|motorcycle|bicycle|walk
→ 200 { "polyline": "…", "distanceM": 1234, "durationS": 300,
        "unmatched": [{"fromIndex": 40, "toIndex": 55}] }   // input-point ranges that matched no road
→ 422 nothing matched · 503 routing engine down
```

## 7. Import rules (all readers)

1. Decrypt; on failure show "Jemputan ini tidak dapat dibuka / disahkan" and import nothing.
2. Inflate (bounded to 20 MiB output), parse the envelope, verify the signature, then parse `event`.
3. First time an event `id` is seen: pin `pub` after showing the organizer alias and a **safety code**
   (6 characters, e.g. `S8EVC7`: the first 30 bits of SHA-256(pub), most significant first, 5 bits per character
   from the alphabet `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`, which leaves out the look-alikes 0/O and 1/I).
4. Identical bytes already stored → "sudah diimport", nothing changes.
   Stops-only variants (§5.5): compare **revision hashes** (`fullHash` for a variant). Same `id`, `rev` and revision
   hash is never a fork, including two different variant byte strings of one revision. Incoming full event over a stored variant → replace it silently ("laluan
   penuh ditambah"); incoming variant over the stored full event → "sudah diimport". The `parent` check uses the
   stored side's revision hash (`fullHash` for a variant).
   Later revisions must have the same `pub` (else reject: "penganjur berbeza"). Lower `rev` than stored → keep the
   stored one ("versi lebih lama"). Same `rev`, different bytes → fork warning, keep both, user chooses.
   `parent` mismatch with the stored previous revision → warn, still allowed (missed revisions).
5. `expiresAt` in the past → do not import, show "Acara telah tamat". Stored events are deleted (keys, payload,
   thumbnails, caches) at `expiresAt`, also checked on start-up and after a backup restore.
6. Event data is excluded from the app's backups.
7. Navigating to a stop calls the routing API with coordinates only (`entrance` if present, else the pin) and no
   waypoint `name`.

## 8. What is in the first build

| Part | Location | Scope |
|---|---|---|
| Rust core | `desktop/crates/event-core`, `route-core` | model, validation, pack/link encode/decode, signing, test vectors, GPX import/export, polyline |
| Desktop app | `desktop/` (Tauri 2, TS + MapLibre GL JS) | library, event planner, route builder (point-to-point, draw-to-follow-roads, sketch), GPX, export link/file, encrypted local store, settings |
| Backend | `backend/` | `store:false`, `/routes/match`, tests, docs/api.md |
| Web viewer | `site/manotu/a/` | open link, verify, show plan, hand off to map apps, calendar file |
| Android, iOS | apps | open link/file, verify + pin, event screen, navigate to stop, expiry |

Deferred: guest suggestions (proposal files), recipient-specific key wrapping, passphrase-protected files, live
convoy, public events.
