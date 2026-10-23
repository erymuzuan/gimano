#!/usr/bin/env python3
"""One-time capture of REAL road geometry for Gimano's demo mode.

Queries a routing engine and converts the result to Gimano's Route contract
(docs/api.md: polyline precision 6, maneuvers with begin/endShapeIndex), writing
app/src/main/assets/demo-routes/<id>.json.

Default source: the public OSRM demo server (OpenStreetMap data, ODbL — credit
"© OpenStreetMap contributors"). Use it sparingly: this script sends one request per
route, 1.2 s apart. Re-run against Gimano's own Valhalla later with --osrm <base-url>
(any OSRM-compatible endpoint) or replace fetch() with POST /api/v1/routes.

Run from android/:  python3 tools/capture-demo-routes.py
"""
import argparse, json, math, os, time, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "demo-routes")

# Demo origin: SS2 Petaling Jaya (public landmark, near Caltex SS2). Destinations match
# places in DemoRepository's catalogue so search → Routes → Go gives real roads.
SS2 = (3.1200, 101.6200, "SS2, Petaling Jaya")
ROUTES = [
    ("ss2-midvalley", SS2, (3.1176, 101.6774, "Mid Valley Megamall")),
    ("damansara-klcc", (3.1360, 101.6230, "Damansara Utama"), (3.1579, 101.7123, "Suria KLCC")),
    ("ss2-sunway-ldp", SS2, (3.0731, 101.6070, "Sunway Pyramid")),  # Lebuhraya Damansara–Puchong (E11)
    ("ss2-klcc", SS2, (3.1579, 101.7123, "Suria KLCC")),
    ("ss2-klsentral", SS2, (3.1340, 101.6865, "KL Sentral")),
    ("ss2-1utama", SS2, (3.1502, 101.6152, "1 Utama Shopping Centre")),
    ("ss2-pavilion", SS2, (3.1490, 101.7134, "Pavilion Kuala Lumpur")),
    ("ss2-masjidnegara", SS2, (3.1418, 101.6917, "Masjid Negara")),
    ("ss2-ttdi", SS2, (3.1460, 101.6290, "Taman Tun Dr Ismail")),
    ("ss2-duta", SS2, (3.1720, 101.6700, "PETRONAS Jalan Duta")),
    ("ss2-putrajaya-e1", SS2, (2.9360, 101.6897, "Masjid Putra")),  # via expressways
]


def decode6(s):
    pts, i, lat, lng = [], 0, 0, 0
    while i < len(s):
        for which in (0, 1):
            shift = res = 0
            while True:
                b = ord(s[i]) - 63; i += 1
                res |= (b & 0x1F) << shift; shift += 5
                if b < 0x20: break
            d = ~(res >> 1) if res & 1 else res >> 1
            if which == 0: lat += d
            else: lng += d
        pts.append((lat / 1e6, lng / 1e6))
    return pts


def hav(a, b):
    r = 6371008.8
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


MOD = {"uturn": "uturn", "sharp right": "sharp_right", "right": "right", "slight right": "slight_right",
       "straight": "straight", "slight left": "slight_left", "left": "left", "sharp left": "sharp_left"}


def maneuver_type(step):
    m = step["maneuver"]
    t, mod = m["type"], m.get("modifier", "straight")
    side = "left" if "left" in mod else "right"
    if t == "depart": return "depart"
    if t == "arrive": return "arrive"
    if t in ("roundabout", "rotary", "roundabout turn"): return "roundabout_enter"
    if t == "exit roundabout" or t == "exit rotary": return "roundabout_exit"
    if t == "merge": return "merge"
    if t == "on ramp": return "ramp_" + side
    if t == "off ramp": return "exit_" + side
    if t == "fork": return "keep_" + side
    if mod == "uturn": return "uturn_" + ("left" if side == "left" else "right")
    return MOD.get(mod, "straight")


def convert(osrm_route, rid, label):
    shape = osrm_route["geometry"]
    pts = decode6(shape)
    steps = osrm_route["legs"][0]["steps"]
    begin, maneuvers, idx = 0, [], 0
    tolls = False
    for s in steps:
        n = len(decode6(s["geometry"]))
        b, e = idx, idx + max(n - 1, 0)
        idx = e
        for inter in s.get("intersections", []):
            if "toll" in inter.get("classes", []): tolls = True
        t = maneuver_type(s)
        m = s["maneuver"]
        # Name changes / straight continues are not driver actions: fold into the previous maneuver.
        if maneuvers and t == "straight" and m["type"] in ("new name", "continue", "turn", "notification"):
            prev = maneuvers[-1]
            prev["distanceM"] = round(prev["distanceM"] + s["distance"], 1)
            prev["durationS"] = round(prev["durationS"] + s["duration"], 1)
            prev["endShapeIndex"] = e
            continue
        mv = {
            "type": t,
            "instruction": "",
            "streetName": s.get("name") or None,
            "roadRef": (s.get("ref") or "").split(";")[0] or None,
            "exitNumber": (str(m["exit"]) if "exit" in m else (s.get("exits") or None)),
            "distanceM": round(s["distance"], 1),
            "durationS": round(s["duration"], 1),
            "beginShapeIndex": b,
            "endShapeIndex": e,
        }
        maneuvers.append({k: v for k, v in mv.items() if v is not None})
    assert idx == len(pts) - 1, f"{rid}: step shapes ({idx + 1}) != overview ({len(pts)})"
    # Summary: the two longest named roads.
    named = sorted((s for s in steps if s.get("name") or s.get("ref")), key=lambda s: -s["distance"])
    via = []
    for s in named:
        label_road = " ".join(x for x in [(s.get("ref") or "").split(";")[0], s.get("name") or ""] if x).strip()
        if label_road and label_road not in via: via.append(label_road)
        if len(via) == 2: break
    lats = [p[0] for p in pts]; lngs = [p[1] for p in pts]
    return {
        "id": rid,
        "label": label,
        "durationS": round(osrm_route["duration"], 1),
        "distanceM": round(osrm_route["distance"], 1),
        "summary": "Via " + ", ".join(via) if via else "",
        "hasTolls": tolls,
        "polyline": shape,
        "bbox": [min(lngs), min(lats), max(lngs), max(lats)],
        "legs": [{"maneuvers": maneuvers}],
        "trafficSegments": [],
        "alerts": [],
    }


def fetch(base, o, d):
    url = (f"{base}/route/v1/driving/{o[1]},{o[0]};{d[1]},{d[0]}"
           "?overview=full&geometries=polyline6&steps=true&alternatives=true")
    req = urllib.request.Request(url, headers={"User-Agent": "gimano-demo-capture/1.0 (one-time, open-source project)"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--osrm", default="https://router.project-osrm.org")
    ap.add_argument("--only", help="capture just this id")
    a = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    for rid, o, d in ROUTES:
        if a.only and rid != a.only: continue
        data = fetch(a.osrm, o, d)
        if data.get("code") != "Ok":
            print(rid, "FAILED", data.get("code")); continue
        routes = [convert(r, f"{rid}-{i}", "best" if i == 0 else "alternative") for i, r in enumerate(data["routes"][:3])]
        doc = {
            "id": rid,
            "origin": {"lat": o[0], "lng": o[1], "name": o[2]},
            "destination": {"lat": d[0], "lng": d[1], "name": d[2]},
            "source": a.osrm,
            "attribution": "Route geometry © OpenStreetMap contributors (ODbL), computed with OSRM.",
            "capturedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "routes": routes,
        }
        with open(os.path.join(OUT, rid + ".json"), "w") as f:
            json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))
        first = decode6(routes[0]["polyline"])
        print(f"{rid}: {len(routes)} route(s), {routes[0]['distanceM'] / 1000:.1f} km, "
              f"{len(routes[0]['legs'][0]['maneuvers'])} maneuvers, start off by {hav(first[0], o[:2]):.0f} m, "
              f"end off by {hav(first[-1], d[:2]):.0f} m, tolls={routes[0]['hasTolls']}")
        time.sleep(1.2)


if __name__ == "__main__":
    main()
