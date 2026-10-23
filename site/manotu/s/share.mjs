// Manotu "Share my drive" viewer. The decryption key is in location.hash (#shareId.key): browsers never send the
// fragment to any server, and this page never transmits it. Only the shareId is used to fetch the ciphertext.
import _sodium from "./vendor/libsodium-wrappers.mjs";

const cfg = window.MANOTU_SHARE_CONFIG;
const T = {
  ms: {
    waiting: "Menunggu lokasi pertama…", driving: "Dalam perjalanan", arrived: "Sudah sampai", ended: "Perkongsian tamat",
    to: "ke", eta: "Anggaran tiba", remaining: "Baki", updated: s => `Dikemas kini ${s} saat lalu`,
    updatedMin: m => `Dikemas kini ${m} minit lalu`, invalid: "Pautan tidak sah", invalidSub: "Minta pemandu hantar pautan baharu.",
    decrypt: "Tidak dapat membuka pautan ini", decryptSub: "Kunci dalam pautan tidak sepadan.", offline: "Tiada sambungan, mencuba semula…",
    endedSub: "Pemandu berhenti berkongsi atau tempoh tamat.",
    privacy: "Disulitkan hujung ke hujung: hanya anda yang mempunyai pautan ini boleh melihat lokasi. Manotu tidak dapat membacanya.",
  },
  en: {
    waiting: "Waiting for the first location…", driving: "On the way", arrived: "Arrived", ended: "Sharing ended",
    to: "to", eta: "ETA", remaining: "Remaining", updated: s => `Updated ${s} s ago`, updatedMin: m => `Updated ${m} min ago`,
    invalid: "Invalid link", invalidSub: "Ask the driver to send a new link.", decrypt: "Can't open this link",
    decryptSub: "The key in the link doesn't match.", offline: "Offline, retrying…", endedSub: "The driver stopped sharing or it expired.",
    privacy: "End-to-end encrypted: only people with this link can see the location. Manotu can't read it.",
  },
};
let lang = (navigator.language || "ms").toLowerCase().startsWith("en") ? "en" : "ms";
const $ = id => document.getElementById(id);
const t = k => T[lang][k];

function applyLang() {
  document.documentElement.lang = lang;
  $("lang").textContent = lang === "ms" ? "EN" : "BM";
  document.querySelectorAll("[data-t]").forEach(el => { el.textContent = t(el.dataset.t); });
  render();
}
$("lang").onclick = () => { lang = lang === "ms" ? "en" : "ms"; applyLang(); };

// ---- link -------------------------------------------------------------------------------------------
function b64urlToBytes(s) {
  const b = atob(s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4));
  return Uint8Array.from(b, c => c.charCodeAt(0));
}
const frag = location.hash.slice(1);
const dot = frag.indexOf(".");
const shareId = dot > 0 ? frag.slice(0, dot) : null;
let key = null;
try { key = dot > 0 ? b64urlToBytes(frag.slice(dot + 1)) : null; } catch { key = null; }
// Optional dev override (?api=http://host:5080); only the shareId is ever sent there.
const apiParam = new URLSearchParams(location.search).get("api");
const api = (apiParam && /^https?:\/\/[\w.:-]+$/.test(apiParam) ? apiParam : cfg.apiBaseUrl).replace(/\/$/, "");

// ---- state + rendering -----------------------------------------------------------------------------------
let state = { kind: "waiting", data: null, receivedAt: null };
const map = new maplibregl.Map({ container: "map", style: cfg.styleUrl, center: [101.69, 3.14], zoom: 11, attributionControl: false });
map.addControl(new maplibregl.AttributionControl({ compact: true, customAttribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors · OpenFreeMap' }));
const carEl = document.createElement("div");
carEl.className = "car";
const marker = new maplibregl.Marker({ element: carEl, rotationAlignment: "map" });
let centered = false;

function fmtKm(m) { return m == null ? "–" : m >= 1000 ? `${(m / 1000).toFixed(m >= 10000 ? 0 : 1)} km` : `${Math.round(m)} m`; }
function fmtTime(iso) {
  if (!iso) return "–";
  const d = new Date(iso);
  return isNaN(d) ? "–" : d.toLocaleTimeString(lang === "ms" ? "ms-MY" : "en-MY", { hour: "numeric", minute: "2-digit" });
}

function render() {
  const p = $("panel");
  p.className = "card state-" + state.kind;
  const d = state.data;
  const dest = d?.destinationName ? ` ${t("to")} ${d.destinationName}` : "";
  const titles = { waiting: t("waiting"), driving: t("driving") + dest, arrived: t("arrived") + dest, ended: t("ended"),
                   invalid: t("invalid"), decrypt: t("decrypt"), offline: t("offline") };
  $("title").textContent = titles[state.kind] ?? "";
  $("subtitle").textContent = { invalid: t("invalidSub"), decrypt: t("decryptSub"), ended: t("endedSub") }[state.kind] ?? "";
  const live = d && (state.kind === "driving" || state.kind === "arrived" || state.kind === "offline");
  $("stats").hidden = !live || state.kind === "arrived";
  if (live) {
    $("eta").textContent = fmtTime(d.etaIso);
    $("remaining").textContent = fmtKm(d.remainingM);
  }
  const ts = d?.ts ? Date.parse(d.ts) : state.receivedAt;
  if (live && ts) {
    const s = Math.max(0, Math.round((Date.now() - ts) / 1000));
    $("updated").textContent = s < 120 ? t("updated")(s) : t("updatedMin")(Math.round(s / 60));
  } else $("updated").textContent = "";
}
setInterval(render, 1000);

function show(d) {
  state = { kind: d.status === "arrived" ? "arrived" : d.status === "ended" ? "ended" : "driving", data: d, receivedAt: Date.now() };
  if (typeof d.lat === "number" && typeof d.lng === "number") {
    marker.setLngLat([d.lng, d.lat]).setRotation(typeof d.heading === "number" ? d.heading : 0).addTo(map);
    if (!centered) { map.jumpTo({ center: [d.lng, d.lat], zoom: 15 }); centered = true; }
    else if (!map.getBounds().contains([d.lng, d.lat])) map.easeTo({ center: [d.lng, d.lat] });
  }
  render();
}

// ---- polling + decryption ----------------------------------------------------------------------------------
let timer = null;
async function poll(sodium) {
  try {
    const r = await fetch(`${api}/api/v1/shares/${encodeURIComponent(shareId)}`, { cache: "no-store", credentials: "omit", referrerPolicy: "no-referrer" });
    if (r.status === 410 || r.status === 404) { state = { ...state, kind: r.status === 410 ? "ended" : "invalid" }; render(); return stop(); }
    if (!r.ok) throw new Error(String(r.status));
    const body = await r.json();
    if (!body.ciphertext) { if (!state.data) { state.kind = "waiting"; render(); } return; }
    let plain;
    try {
      plain = sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(
        null, sodium.from_base64(body.ciphertext, sodium.base64_variants.ORIGINAL), sodium.from_string(shareId),
        sodium.from_base64(body.nonce, sodium.base64_variants.ORIGINAL), key);
    } catch { state = { ...state, kind: "decrypt" }; render(); return stop(); }
    const d = JSON.parse(sodium.to_string(plain));
    show(d);
    if (d.status === "ended") stop();
  } catch {
    if (state.data) { state.kind = "offline"; render(); }
  }
}
function stop() { if (timer) clearInterval(timer); timer = null; }

applyLang();
if (!shareId || !key || key.length !== 32 || !/^[A-Za-z0-9_-]{16,64}$/.test(shareId)) {
  state.kind = "invalid"; render();
} else {
  await _sodium.ready;
  await poll(_sodium);
  if (state.kind !== "ended" && state.kind !== "invalid" && state.kind !== "decrypt")
    timer = setInterval(() => poll(_sodium), (cfg.pollSeconds || 10) * 1000);
}
