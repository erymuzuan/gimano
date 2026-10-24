// Manotu event invitation viewer (docs/events.md §5.1, §7). The whole plan is in location.hash
// (#1.<key>.<nonce‖ciphertext>): browsers never send the fragment, and this page never transmits it and calls no
// API. Event strings are untrusted: they are only ever set with textContent, never parsed as HTML.
import { openLink, decide, navTarget, PackError } from "./pack.mjs";

const cfg = window.MANOTU_EVENT_CONFIG || {};
const T = {
  ms: {
    loading: "Membuka jemputan…", error: "Jemputan ini tidak dapat dibuka", expired: "Acara telah tamat",
    expiredSub: "Tempoh jemputan ini sudah berakhir.",
    errMissing: "Pautan tidak lengkap. Pastikan seluruh pautan disalin.",
    errTooLong: "Pautan ini terlalu panjang. Minta penganjur menghantar fail .manotu-event.",
    errBrowser: "Pelayar ini tidak dapat membuka jemputan. Cuba pelayar lain atau kemas kini pelayar anda.",
    errBad: "Pautan rosak, diubah atau tidak dapat disahkan. Minta penganjur menghantar pautan baharu.",
    organizer: "Penganjur:", safety: "Kod keselamatan penganjur:",
    safetyHelp: "Kod ini sama bagi setiap jemputan daripada penganjur ini untuk acara ini. Bandingkan dengan kod yang penganjur tunjukkan jika anda ragu.",
    openApp: "Buka di Manotu", addCalendar: "Tambah ke kalendar", noApp: "Belum ada Manotu?", download: "Muat turun",
    map: "Peta", mapAria: "Peta tempat dan laluan acara", stops: "Tempat", sessions: "Atur cara", routes: "Laluan",
    tzNote: tz => `Waktu mengikut zon ${tz}`, arrive: "Tiba", dwell: m => `Lebih kurang ${fmtDur(m * 60)} di sini`,
    audience: "Untuk:", entrance: "Navigasi ke pintu masuk kenderaan (garis putus-putus pada peta).",
    app: "Manotu", google: "Google Maps", other: "Apl lain", navTo: l => `ke ${l}`,
    distance: "Jarak", duration: "Masa bergerak", ascent: "Naik", descent: "Turun",
    sketch: "Laluan lakaran: bukan untuk navigasi belokan demi belokan.",
    fullInFile: "Laluan penuh ada dalam fail acara — buka dengan Manotu",
    published: (r, d) => `Versi ${r} · diterbitkan ${d}`, at: "di", stopN: (n, l) => `Tempat ${n}: ${l}`,
    privacy: "Pelan acara ada dalam pautan ini sahaja dan dibuka dalam pelayar anda. Manotu tidak menyimpan atau melihatnya.",
    kinds: { parking: "Parkir", dropoff: "Turun penumpang", entrance: "Pintu masuk", venue: "Tempat acara", meal: "Makan",
      checkpoint: "Titik semak", fuel: "Minyak", water: "Air", rest: "Rehat", prayer: "Surau / masjid",
      accommodation: "Penginapan", start: "Mula", finish: "Tamat", other: "Lain-lain" },
    templates: { kenduri: "Kenduri", dinner: "Makan malam", company: "Acara syarikat", ride: "Kayuhan / tunggangan",
      convoy: "Konvoi", trip: "Lawatan", other: "Acara" },
    modes: { car: "Kereta", motorcycle: "Motosikal", bicycle: "Basikal", walk: "Berjalan kaki" },
  },
  en: {
    loading: "Opening the invitation…", error: "This invitation can't be opened", expired: "This event has ended",
    expiredSub: "This invitation has expired.",
    errMissing: "The link is incomplete. Make sure the whole link was copied.",
    errTooLong: "This link is too long. Ask the organizer to send the .manotu-event file.",
    errBrowser: "This browser can't open the invitation. Try another browser or update this one.",
    errBad: "The link is damaged, altered or can't be verified. Ask the organizer for a new link.",
    organizer: "Organizer:", safety: "Organizer safety code:",
    safetyHelp: "This code is the same on every invitation from this organizer for this event. Compare it with the code the organizer shows if in doubt.",
    openApp: "Open in Manotu", addCalendar: "Add to calendar", noApp: "No Manotu yet?", download: "Download",
    map: "Map", mapAria: "Map of the event's places and routes", stops: "Places", sessions: "Programme", routes: "Routes",
    tzNote: tz => `Times in ${tz}`, arrive: "Arrive", dwell: m => `About ${fmtDur(m * 60)} here`,
    audience: "For:", entrance: "Navigation goes to the vehicle entrance (dashed line on the map).",
    app: "Manotu", google: "Google Maps", other: "Other app", navTo: l => `to ${l}`,
    distance: "Distance", duration: "Moving time", ascent: "Ascent", descent: "Descent",
    sketch: "Sketched route: not for turn-by-turn navigation.",
    fullInFile: "The full route is in the event file — open it with Manotu",
    published: (r, d) => `Version ${r} · published ${d}`, at: "at", stopN: (n, l) => `Place ${n}: ${l}`,
    privacy: "The event plan is only in this link and is opened in your browser. Manotu doesn't store or see it.",
    kinds: { parking: "Parking", dropoff: "Drop-off", entrance: "Entrance", venue: "Venue", meal: "Meal",
      checkpoint: "Checkpoint", fuel: "Fuel", water: "Water", rest: "Rest", prayer: "Prayer",
      accommodation: "Accommodation", start: "Start", finish: "Finish", other: "Other" },
    templates: { kenduri: "Kenduri", dinner: "Dinner", company: "Company event", ride: "Ride", convoy: "Convoy",
      trip: "Trip", other: "Event" },
    modes: { car: "Car", motorcycle: "Motorcycle", bicycle: "Bicycle", walk: "Walking" },
  },
};

let lang = "ms";
let langChosen = false;
const $ = id => document.getElementById(id);
const t = k => T[lang][k];
const fragment = location.hash.slice(1);
let state = { kind: "loading" }; // loading | error | expired | ok
let opened = null;
let map = null;

function el(tag, attrs = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === undefined || v === null || v === false) continue;
    if (k === "class") e.className = v; else if (k === "text") e.textContent = v; else e.setAttribute(k, v);
  }
  for (const c of children) if (c) e.append(c); // strings become text nodes
  return e;
}

// ---- formatting -------------------------------------------------------------------------------------------
const locale = () => (lang === "ms" ? "ms-MY" : "en-MY");
function fmtTime(tm, opts) {
  const tz = opened?.event.tz;
  try { return new Intl.DateTimeFormat(locale(), { ...opts, timeZone: tz }).format(tm.ms); }
  catch { // unknown IANA zone in this browser: show the wall time the organizer wrote (its RFC 3339 offset)
    return new Intl.DateTimeFormat(locale(), { ...opts, timeZone: "UTC" }).format(tm.ms + tm.offsetMin * 60_000);
  }
}
const DATE = { weekday: "long", day: "numeric", month: "long", year: "numeric" };
const SHORT_DATE = { weekday: "short", day: "numeric", month: "short" };
const TIME = { hour: "numeric", minute: "2-digit" };
function fmtRange(a, b, dateOpts = DATE) {
  const da = fmtTime(a, dateOpts), db = fmtTime(b, dateOpts);
  return da === db ? `${da} · ${fmtTime(a, TIME)} – ${fmtTime(b, TIME)}`
    : `${da}, ${fmtTime(a, TIME)} – ${db}, ${fmtTime(b, TIME)}`;
}
function fmtKm(m) { return m >= 1000 ? `${(m / 1000).toLocaleString(locale(), { maximumFractionDigits: m >= 10000 ? 0 : 1 })} km` : `${m} m`; }
function fmtDur(s) {
  const h = Math.floor(s / 3600), m = Math.round((s % 3600) / 60);
  const H = lang === "ms" ? "j" : "h", M = lang === "ms" ? "min" : "min";
  return h ? `${h} ${H}${m ? ` ${m} ${M}` : ""}` : `${m} ${M}`;
}
const coord = n => n.toFixed(6).replace(/\.?0+$/, "");

// ---- hand-off links -----------------------------------------------------------------------------------------
const ua = navigator.userAgent || "";
const isAndroid = /Android/i.test(ua);
const isIOS = /iPhone|iPad|iPod/i.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
/** docs/events.md §5.3: manotu://event[?stop=<stopId>]#<the same fragment>. Opens only on this device. */
function appLink(stopId) {
  const q = stopId ? `?stop=${encodeURIComponent(stopId)}` : "";
  return `${cfg.appScheme || "manotu"}://event${q}#${fragment}`;
}
function googleLink(p) { return `https://www.google.com/maps/dir/?api=1&destination=${coord(p.lat)},${coord(p.lng)}`; }
function otherLink(p) {
  const ll = `${coord(p.lat)},${coord(p.lng)}`;
  if (isAndroid) return `geo:${ll}?q=${ll}`;
  if (isIOS) return `https://maps.apple.com/?daddr=${ll}`;
  return `https://www.openstreetmap.org/directions?to=${encodeURIComponent(ll)}`;
}

// ---- calendar (.ics generated in the browser, no network) -------------------------------------------------------
function icsEscape(s) { return String(s).replace(/\\/g, "\\\\").replace(/;/g, "\\;").replace(/,/g, "\\,").replace(/\r?\n/g, "\\n"); }
function icsFold(line) {
  const bytes = new TextEncoder();
  const out = [];
  let cur = "", n = 0;
  for (const ch of line) {
    const b = bytes.encode(ch).length;
    if (n + b > (out.length ? 74 : 75)) { out.push(cur); cur = ""; n = 0; }
    cur += ch; n += b;
  }
  out.push(cur);
  return out.join("\r\n ");
}
const icsTime = ms => new Date(ms).toISOString().replace(/[-:]/g, "").replace(/\.\d{3}/, "");
function downloadIcs() {
  const e = opened.event;
  const first = e.stops[0];
  const lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Manotu//Event invitation//EN", "CALSCALE:GREGORIAN",
    "METHOD:PUBLISH", "BEGIN:VEVENT", `UID:${e.id}@manotu.app`, `SEQUENCE:${e.rev}`, `DTSTAMP:${icsTime(Date.now())}`,
    `DTSTART:${icsTime(e.start.ms)}`, `DTEND:${icsTime(e.end.ms)}`, `SUMMARY:${icsEscape(e.title)}`];
  if (e.description) lines.push(`DESCRIPTION:${icsEscape(e.description)}`);
  if (first) {
    const p = navTarget(first);
    lines.push(`LOCATION:${icsEscape(first.label)}`, `GEO:${coord(p.lat)};${coord(p.lng)}`);
  }
  lines.push("END:VEVENT", "END:VCALENDAR");
  const blob = new Blob([lines.map(icsFold).join("\r\n") + "\r\n"], { type: "text/calendar;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = el("a", { href: url, download: `${e.title.replace(/[^\p{L}\p{N} _-]+/gu, "").trim().slice(0, 60) || "acara"}.ics` });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

// ---- rendering --------------------------------------------------------------------------------------------------
function applyLang() {
  document.documentElement.lang = lang;
  $("lang").textContent = lang === "ms" ? "EN" : "BM";
  $("lang").setAttribute("aria-label", lang === "ms" ? "English" : "Bahasa Melayu");
  document.querySelectorAll("[data-t]").forEach(n => { n.textContent = t(n.dataset.t); });
  document.querySelectorAll("[data-t-aria]").forEach(n => n.setAttribute("aria-label", t(n.dataset.tAria)));
  render();
}

function render() {
  const status = $("status");
  if (state.kind !== "ok") {
    status.hidden = false;
    $("plan").hidden = true;
    status.className = `card status state-${state.kind}`;
    $("status-title").textContent = t(state.kind);
    $("status-sub").textContent = state.kind === "expired" ? t("expiredSub") : state.kind === "error" ? t(state.sub) : "";
    document.title = `Manotu · ${t(state.kind)}`;
    return;
  }
  status.hidden = true;
  $("plan").hidden = false;
  const e = opened.event;
  document.title = `${e.title} · Manotu`;
  $("ev-kind").textContent = t("templates")[e.template];
  $("ev-title").textContent = e.title;
  $("ev-when").textContent = fmtRange(e.start, e.end);
  let viewerTz = "";
  try { viewerTz = Intl.DateTimeFormat().resolvedOptions().timeZone; } catch { /* ignore */ }
  $("ev-tz").hidden = viewerTz === e.tz;
  $("ev-tz").textContent = t("tzNote")(e.tz);
  $("ev-desc").hidden = !e.description;
  $("ev-desc").textContent = e.description || "";
  const alias = e.organizer?.alias;
  $("ev-org").hidden = false;
  $("ev-alias").textContent = alias || "—";
  $("ev-safety").textContent = opened.safetyCode;
  $("open-app").href = appLink();
  $("download").href = cfg.downloadUrl || "../download/";
  $("published").textContent = t("published")(e.rev, fmtTime(e.publishedAt, { day: "numeric", month: "long", year: "numeric" }));

  const stopIndex = new Map(e.stops.map((s, i) => [s.id, i]));
  $("stops").replaceChildren(...e.stops.map((s, i) => {
    const target = navTarget(s);
    const meta = el("p", { class: "meta" }, el("span", { class: "kind", text: t("kinds")[s.kind] }));
    if (s.audience) meta.append(" · ", `${t("audience")} ${s.audience}`);
    const li = el("li", { id: `stop-${i + 1}`, class: "stop", tabindex: "-1" },
      el("div", { class: "stop-head" }, el("span", { class: "num", "aria-hidden": "true", text: String(i + 1) }),
        el("h3", { text: s.label })),
      meta,
      s.arrive && el("p", {}, el("strong", { text: `${t("arrive")}: ` }), fmtRange(s.arrive.from, s.arrive.to, SHORT_DATE)),
      s.dwellMin ? el("p", { class: "muted small", text: t("dwell")(s.dwellMin) }) : null,
      s.notes && el("p", { class: "notes", text: s.notes }),
      s.entrance && el("p", { class: "muted small", text: t("entrance") }),
      el("div", { class: "nav", role: "group", "aria-label": t("navTo")(s.label) },
        el("a", { class: "btn primary", href: appLink(s.id), "aria-label": `${t("app")}: ${t("navTo")(s.label)}`, text: t("app") }),
        el("a", { class: "btn", href: googleLink(target), target: "_blank", rel: "noopener noreferrer",
          "aria-label": `${t("google")}: ${t("navTo")(s.label)}`, text: t("google") }),
        el("a", { class: "btn", href: otherLink(target), target: isAndroid ? null : "_blank", rel: "noopener noreferrer",
          "aria-label": `${t("other")}: ${t("navTo")(s.label)}`, text: t("other") })));
    return li;
  }));

  $("sessions-card").hidden = e.sessions.length === 0;
  const sessions = [...e.sessions].sort((a, b) => a.start.ms - b.start.ms);
  $("sessions").replaceChildren(...sessions.map(s => {
    const where = s.stopId !== undefined ? e.stops[stopIndex.get(s.stopId)] : null;
    return el("li", {}, el("strong", { text: s.title }),
      el("div", { class: "muted small", text: fmtRange(s.start, s.end, SHORT_DATE) + (where ? ` · ${t("at")} ${where.label}` : "") }));
  }));

  $("routes-card").hidden = e.routes.length === 0;
  // Stops-only link (docs/events.md §5.5): route list with distance and time, but no line on the map.
  $("routes-note").hidden = !e.stopsOnly;
  $("routes").replaceChildren(...e.routes.map((r, i) => {
    const facts = [`${t("modes")[r.mode]}`, `${t("distance")} ${fmtKm(r.distanceM)}`];
    if (r.durationS !== undefined) facts.push(`${t("duration")} ${fmtDur(r.durationS)}`);
    if (r.ascentM !== undefined) facts.push(`${t("ascent")} ${r.ascentM} m`);
    if (r.descentM !== undefined) facts.push(`${t("descent")} ${r.descentM} m`);
    const day = r.day ? fmtTime({ ms: Date.parse(`${r.day}T12:00:00Z`), offsetMin: 0 }, SHORT_DATE) : "";
    const path = r.stopIds.map(id => stopIndex.get(id) + 1).join(" → ");
    return el("li", {}, el("strong", { text: r.title || `${t("routes")} ${i + 1}` }),
      day && el("span", { class: "muted", text: ` · ${day}` }),
      el("div", { class: "small", text: facts.join(" · ") }),
      path && el("div", { class: "muted small", text: path }),
      r.source !== "routed" && el("div", { class: "muted small", text: t("sketch") }));
  }));
  if (map) labelMarkers();
}

// ---- map (created only after the plan opened and verified) ---------------------------------------------------------
let markers = [];
function labelMarkers() {
  markers.forEach((m, i) => m.getElement().setAttribute("aria-label", t("stopN")(i + 1, opened.event.stops[i].label)));
}
function showMap() {
  const e = opened.event;
  const pts = [];
  e.stops.forEach(s => { pts.push([s.lng, s.lat]); if (s.entrance) pts.push([s.entrance.lng, s.entrance.lat]); });
  e.routes.forEach(r => r.points.forEach(([la, ln]) => pts.push([ln, la])));
  if (!pts.length || typeof maplibregl === "undefined") { $("map").closest("section").hidden = true; return; }

  map = new maplibregl.Map({ container: "map", style: cfg.styleUrl, center: pts[0], zoom: 14, attributionControl: false,
    cooperativeGestures: true });
  // The style's own attribution (OpenFreeMap, OpenMapTiles, OpenStreetMap) stays expanded; the line under the map
  // repeats the OSM credit so it stays visible even when the map can't load.
  map.addControl(new maplibregl.AttributionControl({ compact: false }));
  map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");

  if (pts.length > 1) {
    const b = pts.reduce((acc, p) => acc.extend(p), new maplibregl.LngLatBounds(pts[0], pts[0]));
    map.fitBounds(b, { padding: 48, maxZoom: 16, duration: 0 });
  }
  markers = e.stops.map((s, i) => {
    const pin = el("button", { type: "button", class: "pin", text: String(i + 1) });
    pin.addEventListener("click", () => { const li = $(`stop-${i + 1}`); li.scrollIntoView({ behavior: "smooth", block: "center" }); li.focus({ preventScroll: true }); });
    return new maplibregl.Marker({ element: pin }).setLngLat([s.lng, s.lat]).addTo(map);
  });
  labelMarkers();

  map.on("load", () => {
    const css = getComputedStyle(document.documentElement);
    const brand = css.getPropertyValue("--brand").trim() || "#0047AB";
    map.addSource("routes", { type: "geojson", data: { type: "FeatureCollection", features: e.routes.filter(r => r.points.length >= 2).map(r => ({
      type: "Feature", properties: { sketch: r.source !== "routed" },
      geometry: { type: "LineString", coordinates: r.points.map(([la, ln]) => [ln, la]) } })) } });
    map.addLayer({ id: "routes-casing", type: "line", source: "routes", layout: { "line-join": "round", "line-cap": "round" },
      paint: { "line-color": "#ffffff", "line-width": 8 } });
    map.addLayer({ id: "routes", type: "line", source: "routes", layout: { "line-join": "round", "line-cap": "round" },
      paint: { "line-color": brand, "line-width": 5 } });
    const withEntrance = e.stops.filter(s => s.entrance);
    map.addSource("entrances", { type: "geojson", data: { type: "FeatureCollection", features: [
      ...withEntrance.map(s => ({ type: "Feature", properties: {},
        geometry: { type: "LineString", coordinates: [[s.lng, s.lat], [s.entrance.lng, s.entrance.lat]] } })),
      ...withEntrance.map(s => ({ type: "Feature", properties: {}, geometry: { type: "Point", coordinates: [s.entrance.lng, s.entrance.lat] } })),
    ] } });
    map.addLayer({ id: "entrance-line", type: "line", source: "entrances", filter: ["==", ["geometry-type"], "LineString"],
      paint: { "line-color": "#FF6B4A", "line-width": 3, "line-dasharray": [2, 1.5] } });
    map.addLayer({ id: "entrance-dot", type: "circle", source: "entrances", filter: ["==", ["geometry-type"], "Point"],
      paint: { "circle-radius": 6, "circle-color": "#FF6B4A", "circle-stroke-color": "#ffffff", "circle-stroke-width": 2 } });
  });
}

// ---- start --------------------------------------------------------------------------------------------------------
$("lang").addEventListener("click", () => { lang = lang === "ms" ? "en" : "ms"; langChosen = true; applyLang(); });
$("ics").addEventListener("click", () => { if (opened) downloadIcs(); });
applyLang();

function fail(sub) { opened = null; state = { kind: "error", sub }; render(); }

if (!fragment) fail("errMissing");
else if (typeof DecompressionStream === "undefined") fail("errBrowser");
else {
  try {
    const result = await openLink(fragment);
    if (decide(result.event) === "expired") { state = { kind: "expired" }; render(); }
    else {
      opened = result;
      state = { kind: "ok" };
      if (!langChosen) lang = result.event.lang; // the host's display language (§4 `lang`); BM until then
      applyLang();
      // No WebGL (old phone, disabled GPU): the invitation still opens, just without the map.
      try { showMap(); } catch { map?.remove(); map = null; markers = []; $("map").closest("section").hidden = true; }
    }
  } catch (err) {
    const code = err instanceof PackError ? err.code : "unknown";
    fail(code === "fragment_too_long" ? "errTooLong" : "errBad");
  }
}
