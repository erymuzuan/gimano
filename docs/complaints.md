# Road and place complaints: build spec (v1)

Source of truth for the design: the design canvas, page **"Vetting & complaints (idea)"**, boards AduanFlow,
AduanMatrix, AduanLapor, AduanLencong, AduanElak, AduanTempat and AduanOsm. The user approved building it on
2026-10-23. This file turns those boards into an implementable v1. Where they differ, **this file wins for v1**; the
canvas stays the long-term picture.

## Principles (non-negotiable)

1. **One report never closes a road.** It warns and asks others; only confirmation changes routing.
2. **Corrections only make routing more cautious.** We may avoid a road or block a direction, never allow what OSM
   forbids. Opening a road or direction only happens through an OSM fix.
3. **Vehicles that actually drive through are the strongest evidence.** Anonymous traffic flow refutes "closed".
4. **Privacy rule unchanged (AGENTS.md):**
   - reports are public contributions under the pseudonym;
   - deviation detection happens **on the phone** (only the answer + segment are sent);
   - passive evidence is **aggregate per-segment counts**, never per-user tracks;
   - no new per-user location history.
5. Everything expires or can be undone. Every issue has a state, an expiry and an audit trail. Nothing is permanent
   except through OSM.

## Issue types (v1)

| kind | target | confirm rule | routing / search effect | expiry |
|---|---|---|---|---|
| `road_closed_temp` (subtype accident/flood/event/roadworks) | road segment + direction(s) | 2 weighted votes from different devices, OR passive "no traversals" + 1 vote, OR 1 Emas | **suspected:** warn on routes passing it; **confirmed:** avoid segment (all vehicles) | accident 2 h · flood 6 h · event 12 h · roadworks 7 d; extended by confirmations |
| `road_not_exist` (gate, private, track) | segment | 2 weighted + no traversals in 14 d | confirmed: avoid for the reported vehicle class | until refuted (traffic resumes) or OSM fixed |
| `oneway_wrong_closed_dir` (map says two-way, really one-way) | segment + direction | 2 weighted + direction stats (no traversals in that direction) | confirmed: block that direction only (**v1: warn only, see routing**) | until OSM fixed |
| `oneway_wrong_open_dir` (map says one-way, really two-way) | segment | passive stats + 1 Emas | **no routing change, ever** (principle 2): maintainer/OSM queue only | — |
| `turn_not_allowed` | node + from/to segments | 2 weighted | v1: warn on routes using that turn | until OSM fixed |
| `odd_route` | route summary (from/to rounded to ~1 km, chosen vs taken roads) | none (feedback) | maintainer quality queue | — |
| `new_road` | user-chosen opt-in trace of that segment only (200 m trimmed at each end) | none | maintainer/OSM queue | — |
| `place_closed_perm` | placeId | 3 local weighted votes, or 1 Emas | suspected: "Dilaporkan tutup" badge; confirmed: hidden from search, no routing to it | reopened by 2 "masih buka" votes |
| `place_closed_temp` | placeId + until date | 2 votes | badge "Tutup sementara sehingga …" | the date (max 60 d) |
| `place_hours_wrong` | placeId + proposed hours | 2 votes on the new hours | "Waktu mungkin berubah" until confirmed, then the new hours | — |
| `place_entrance_wrong` | placeId + proposed nav point | 2 votes or 1 Emas | separate **navigation point** used for routing; display pin unchanged | — |
| `place_info_wrong` / `place_duplicate` | placeId (+ target) | 2 votes / Emas or maintainer | edit suggestion → applied | — |

Vote weights reuse the reputation tiers (Baharu 1 · Perak 1.5 · Emas 2; accounts < 7 days count 0). **Major roads**
(motorway/trunk/primary, toll) need 3 weighted votes **and** passive evidence before `confirmed`.

**States:**
- `reported` → `suspected` (on creation) → `confirmed`;
- or `refuted` (≥ 5 traversals after the report, or 2 weighted "dah dibuka / masih buka" votes);
- or `expired`;
- or `resolved` (OSM now matches, checked at weekly import).

Reporters get Inbox items on confirmed/refuted/resolved, and accuracy counts toward reputation.

## Backend (v1)

- **Tables:** `issues` (id, kind, subtype, state, target: way id + segment index + direction | node | placeId,
  geom (point, rounded storage ≤ 5 m), vehicleClass, createdBy pseudonym, createdAt, expiresAt, confidence,
  lastEvidenceAt, payload json); `issue_votes` (unlinked after resolution exactly like place votes:
  HMAC + per-issue salt); `segment_traversal_counts` (segment id, direction, hour bucket, count). The counts come from
  the existing anonymous ping pipeline (TrafficAggregator); no per-driver data.
- **Endpoints** (signed like other contributions; documented in `docs/api.md` "Issues"):
  - `POST /issues {kind, subtype?, lat, lng, heading?, placeId?, vehicle?, durationHint?, source: "report"|"deviation"|"place_edit", payload?}`
    The server snaps road reports to the nearest segment + direction (heading), dedupes within 50 m of the same
    kind/segment (merging into the existing issue), and returns the issue.
  - `GET /issues/nearby?bbox=&kinds=` (public, for map + "Masih ditutup?" prompts) and `GET /issues/{id}`.
  - `POST /issues/{id}/votes {answer: "still"|"cleared"|"unsure"}` (one per pseudonym; local proximity check on the
    phone like place vetting; rate limit 20/day).
  - `GET /routes` / `POST /routes` responses gain `issues: [{id, kind, state, segment, summary}]`. These are issues
    on or avoided by each route, so the app can show "Mengelak Jln X" or warn.
  - An admin/maintainer endpoint (ADMIN_KEY) lists the `odd_route`, `new_road`, `oneway_wrong_open_dir` and
    long-lived confirmed issues, as an **OSM notes queue (export only; no automatic posting to OSM in v1)**.
- **Routing (v1):** confirmed `road_closed_temp` / `road_not_exist` → Valhalla `exclude_polygons` (small buffers around
  the segment) on every route request in that bbox. Directional blocking, soft penalties and turn restrictions (the
  canvas's live-traffic-tile approach) are **v2**; v1 warns for those.
- **Search:** confirmed `place_closed_perm` are excluded from results; others add a `status` badge field to
  PlaceSummary.
- **Passive evidence job:** every 15 min, for open road issues: traversals since `createdAt` ≥ 5 → `refuted`; zero
  traversals for the confirm window + votes → supports `confirmed`.
- **Tests:** state machine, thresholds (major roads), expiry, dedupe/merge, unlinking, exclude_polygons in route
  requests, the refute job.

## Apps (Android + iOS, v1)

1. **Report "Masalah jalan"** (AduanLapor): a new group in the report sheet during navigation. Ditutup, Tiada jalan,
   Sehala, Tak boleh belok, Laluan pelik, Jalan baharu; for Ditutup, the duration chips Tak tahu / Hari ini /
   Beberapa hari / Kerja jalan lama. The location and heading come from the current fix and the route context. One
   tap + Hantar.
2. **"Kenapa tak ikut laluan?"** (AduanLencong): the **phone** notices the user left the suggested route at a decision
   point and didn't rejoin. After arrival or when stopped (never while driving), at most once per day, it asks with
   chips Jalan ditutup / Tiada jalan / Sehala / Tak boleh belok / Jem teruk / Saya pilih sendiri, plus "Jangan tanya
   lagi" (persisted). Only the answer + that segment are sent.
3. **Route preview** (AduanElak): a banner "Mengelak {road}: ditutup ({subtype}) · disahkan N pemandu · 2 j lalu", with
   Butiran and "Lalu juga (saya tahu jalan ini)". Lalu juga recomputes without the exclusion **for this trip only**,
   and counts as weak evidence if the user then drives through.
4. **"Masih ditutup?"**: when passing near (≤ 300 m) a suspected/confirmed issue, use the existing alert-ahead UI;
   answers are Masih / Dah dibuka / Tak pasti.
5. **Place card "Cadangkan suntingan"** (AduanTempat): Tutup kekal / Tutup sementara (date) / Waktu buka salah (hours
   editor) / Pintu masuk / parkir salah (drag a green pin) / Nama, jenis, telefon / Pendua. Place cards show badges
   ("Dilaporkan tutup · sahkan?", "Tutup sementara sehingga …"); the nearby vetting prompt also asks about suspected
   place issues.
6. Inbox items for the reporter. All strings in EN + MS ("anda").

## Out of scope for v1 (keep for v2)

- automatic OSM note posting and a Manotu OSM account;
- MapRoulette;
- users linking their own OSM account;
- Valhalla live-traffic tiles for directional closures and soft penalties;
- turn-restriction patches in the weekly tile build;
- the weekly tile rebuild + reconcile automation (manual for now);
- the satellite layer.
