# Where Gimano's traffic comes from

Short answer: **Gimano's own crowd first** (anonymous speeds plus driver reports). Official
sources such as **LLM** (Lembaga Lebuhraya Malaysia) and the highway concessionaires are
added only through a partnership or a published feed. **AI** helps *interpret* text sources;
it never invents traffic.

## 1. Crowdsourced speed probes (built; primary source)

- While navigating, each phone sends anonymised GPS points every 5 s (`POST /locations`).
- The worker snaps them to OSM road segments and keeps a 5-minute rolling mean speed.
- A segment is a **jam** when the mean is below 50 % of free-flow, with ≥ 3 distinct drivers.
  Levels 1–4 are drawn on the map and fed into route ETAs.
- Privacy: pings carry only a rotating pseudonymous session id (per-hour key, never the user id), travel on a
  transient queue (30 s TTL), and are processed **in memory**. Raw pings and per-driver positions are never written
  to disk. Only aggregated segment speeds and jams are kept, and drivers are shown only as ≥ 3-driver cells.
- Weakness: it needs drivers. It's useful on busy Klang Valley roads within weeks, but
  sparse on rural roads for a long time.

## 2. Driver reports (built)

Traffic, police, accident, hazard, closure, blocked lane, flood, weather, animal. Other
drivers confirm with "Still there? / Not there", and reputation weights the votes. These cover
what speeds can't explain (*why* it's slow) and events with no slowdown yet (police,
potholes, floods).

## 3. Historical profiles (next; from our own data)

The speed probes are aggregated into **hour-of-week speed profiles** per segment (e.g.
"Federal Highway, Thu 08:00 = 18 km/h"). These feed:
- "Leave later" / "Find the best time to leave" (today it shows the same time for every hour),
- ETAs when there is no live data for a road right now.

Until a segment has enough history, the app says "Not enough traffic history yet" and
doesn't guess.

## 4. Official and partner sources (only with permission)

| Source | What it has | How we would use it |
|---|---|---|
| **LLM (Lembaga Lebuhraya Malaysia)** traffic info / CCTV | Expressway incidents, closures, CCTV | Only via a formal data-sharing agreement or a published API. No scraping of the app or website. |
| Concessionaires (PLUS, Prolintas, Gamuda, ANIH…) | Incidents, lane closures, toll-plaza congestion | Same: partner feeds. Their public posts on X/Facebook may only be read through official APIs, if their terms allow. |
| JKR / PDRM / state councils | Planned road works, closures, events | Open data portals (data.gov.my) where published. |
| e-hailing and logistics fleets | Dense probe speeds | Partner probe feeds; anonymised and aggregated before they reach Gimano. |

Each external source carries a `source` field and its own confidence, and never overrides
fresh crowd data.

## 5. Where AI (LLMs) fits

Language models are good at **turning unstructured text into structured incidents**, for
example a permitted official post "Kemalangan di KM 23.5 arah utara, lorong kiri ditutup" →
`{type: accident, subtype: blocked_lane, highway: E1, km: 23.5, direction: north, lane: left}`.
It is geocoded against OSM kilometre markers (`highway=milestone`).

The rules:
- AI only **parses** text from sources we are allowed to use. It never predicts or makes up
  traffic.
- Parsed incidents get `source: "ai_parsed:<feed>"`, lower confidence, and a short TTL until
  drivers confirm them with "Still there?".
- AI also helps triage **user feedback** ("Add feedback") into bug / map data / route quality.

## Status

| Layer | State |
|---|---|
| Crowd speeds → jams | Built (worker `TrafficAggregator`) |
| Driver reports + voting | Built |
| Historical profiles | Planned |
| LLM / concessionaire feeds | Needs partnership: not started |
| AI incident parsing | Planned, after a permitted feed exists |
