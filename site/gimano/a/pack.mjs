// Manotu event pack reader (docs/events.md §3–§5, §7). Pure logic, no DOM and no network, so the same module runs
// in the page and in Node (test/vectors.mjs). Mirrors desktop/crates/event-core: error codes are the stable
// `Error::code()` strings used by the test vectors (`expectedError`).
import _sodium from "../s/vendor/libsodium-wrappers.mjs";

export const AEAD_AD = "manotu-event-v1";
export const SIGNATURE_PREFIX = "manotu-event-v1\n";
export const LINK_VERSION = "1";
export const MAX_FRAGMENT_CHARS = 16_000;
export const MAX_INFLATED_BYTES = 20 * 1024 * 1024;
export const MAX_FILE_BYTES = 20 * 1024 * 1024;
const NONCE_LEN = 24;
const TAG_LEN = 16;
const INFLATE_INPUT_CHUNK = 1024; // feed the inflater in small pieces so the output cap is enforced early

export const LIMITS = {
  eventDays: 14, stops: 200, sessions: 50, routes: 20, polylinePoints: 50_000,
  title: 120, description: 2000, stopLabel: 80, stopNotes: 500, audience: 40, routeTitle: 80,
  sessionTitle: 120, alias: 80, tz: 64, dwellMin: 14 * 24 * 60, eventIdBytes: 16, itemIdBytes: 8,
};

export const TEMPLATES = ["kenduri", "dinner", "company", "ride", "convoy", "trip", "other"];
export const STOP_KINDS = ["parking", "dropoff", "entrance", "venue", "meal", "checkpoint", "fuel", "water", "rest",
  "prayer", "accommodation", "start", "finish", "other"];
export const LANGS = ["ms", "en"];
export const MODES = ["car", "motorcycle", "bicycle", "walk"];
export const SOURCES = ["routed", "sketch", "gpx"];

export class PackError extends Error {
  /** @param {string} code stable code (see event_core::Error::code) @param {string} [detail] */
  constructor(code, detail) {
    super(detail ? `${code}: ${detail}` : code);
    this.code = code;
    this.detail = detail;
  }
}

let sodium = null;
export async function ready() {
  if (!sodium) { await _sodium.ready; sodium = _sodium; }
  return sodium;
}

// ---- encodings ----------------------------------------------------------------------------------------------
const B64U = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
const B64U_INDEX = new Int16Array(128).fill(-1);
for (let i = 0; i < 64; i++) B64U_INDEX[B64U.charCodeAt(i)] = i;

/** Strict base64url without padding: canonical trailing bits; returns null when invalid. */
export function b64uDecode(s) {
  if (typeof s !== "string" || s.length % 4 === 1) return null;
  const out = new Uint8Array(Math.floor(s.length * 3 / 4));
  let acc = 0, bits = 0, o = 0;
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    const v = c < 128 ? B64U_INDEX[c] : -1;
    if (v < 0) return null;
    acc = ((acc << 6) | v) & 0xffffff;
    bits += 6;
    if (bits >= 8) { bits -= 8; out[o++] = (acc >> bits) & 0xff; }
  }
  if (bits > 0 && (acc & ((1 << bits) - 1)) !== 0) return null; // non-canonical trailing bits
  return out;
}

export function b64uEncode(bytes) {
  let s = "", i = 0;
  for (; i + 2 < bytes.length; i += 3) {
    const n = (bytes[i] << 16) | (bytes[i + 1] << 8) | bytes[i + 2];
    s += B64U[n >> 18] + B64U[(n >> 12) & 63] + B64U[(n >> 6) & 63] + B64U[n & 63];
  }
  if (bytes.length - i === 1) { const n = bytes[i] << 16; s += B64U[n >> 18] + B64U[(n >> 12) & 63]; }
  else if (bytes.length - i === 2) {
    const n = (bytes[i] << 16) | (bytes[i + 1] << 8);
    s += B64U[n >> 18] + B64U[(n >> 12) & 63] + B64U[(n >> 6) & 63];
  }
  return s;
}

const CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
/** Crockford base32 secret code (§5.2) → 32-byte key. Lenient reading, canonical padding. */
export function decodeSecretCode(code) {
  const values = [];
  for (const ch of String(code)) {
    if (ch === "-" || /\s/.test(ch)) continue;
    const u = ch.toUpperCase();
    const v = u === "O" ? 0 : (u === "I" || u === "L") ? 1 : CROCKFORD.indexOf(u);
    if (v < 0 || u.length !== 1) throw new PackError("invalid_secret_code", "invalid character");
    values.push(v);
    if (values.length > 52) throw new PackError("invalid_secret_code", "too long");
  }
  if (values.length !== 52) throw new PackError("invalid_secret_code", "wrong length");
  const out = new Uint8Array(32);
  let acc = 0, bits = 0, i = 0;
  for (const v of values) {
    acc = (acc << 5) | v; bits += 5;
    if (bits >= 8) { bits -= 8; out[i++] = (acc >> bits) & 0xff; acc &= (1 << bits) - 1; }
  }
  if (acc !== 0) throw new PackError("invalid_secret_code", "non-canonical padding");
  return out;
}

// SHA-256 (FIPS 180-4). The vendored libsodium-wrappers is the standard build, which has no SHA-256, and
// crypto.subtle is missing outside secure contexts; this small synchronous version works everywhere.
const K256 = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01,
  0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
  0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
  0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08,
  0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
  0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2]);
export function sha256(data) {
  const len = data.length, padded = new Uint8Array(((len + 9 + 63) >> 6) << 6);
  padded.set(data); padded[len] = 0x80;
  const dv = new DataView(padded.buffer);
  dv.setUint32(padded.length - 8, Math.floor(len / 0x20000000)); dv.setUint32(padded.length - 4, (len << 3) >>> 0);
  const H = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]);
  const W = new Uint32Array(64);
  const rotr = (x, n) => (x >>> n) | (x << (32 - n));
  for (let off = 0; off < padded.length; off += 64) {
    for (let i = 0; i < 16; i++) W[i] = dv.getUint32(off + i * 4);
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(W[i - 15], 7) ^ rotr(W[i - 15], 18) ^ (W[i - 15] >>> 3);
      const s1 = rotr(W[i - 2], 17) ^ rotr(W[i - 2], 19) ^ (W[i - 2] >>> 10);
      W[i] = (W[i - 16] + s0 + W[i - 7] + s1) >>> 0;
    }
    let [a, b, c, d, e, f, g, h] = H;
    for (let i = 0; i < 64; i++) {
      const t1 = (h + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + K256[i] + W[i]) >>> 0;
      const t2 = ((rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) >>> 0;
      h = g; g = f; f = e; e = (d + t1) >>> 0; d = c; c = b; b = a; a = (t1 + t2) >>> 0;
    }
    H[0] += a; H[1] += b; H[2] += c; H[3] += d; H[4] += e; H[5] += f; H[6] += g; H[7] += h;
  }
  const out = new Uint8Array(32), ov = new DataView(out.buffer);
  H.forEach((v, i) => ov.setUint32(i * 4, v));
  return out;
}

/** First 8 bytes of SHA-256(pub), upper-case hex in 4 groups of 4 (§7.3). */
export function safetyCode(pub) {
  const h = sha256(pub);
  const hex = Array.from(h.slice(0, 8), b => b.toString(16).padStart(2, "0")).join("").toUpperCase();
  return hex.match(/.{4}/g).join(" ");
}

// ---- transport ------------------------------------------------------------------------------------------------
/** Link or bare fragment → { key, nonce, ciphertext }. Length is checked before any decoding. */
export function parseLink(link) {
  const s = String(link);
  const hash = s.indexOf("#");
  const fragment = (hash >= 0 ? s.slice(hash + 1) : s).trim();
  const len = [...fragment].length;
  if (len > MAX_FRAGMENT_CHARS) throw new PackError("fragment_too_long", `${len} > ${MAX_FRAGMENT_CHARS}`);
  const parts = fragment.split(".");
  if (parts.length !== 3) throw new PackError("malformed_link");
  if (parts[0] !== LINK_VERSION) throw new PackError("unsupported_link_version");
  const key = b64uDecode(parts[1]);
  if (!key || key.length !== 32) throw new PackError("malformed_link");
  const data = b64uDecode(parts[2]);
  if (!data || data.length < NONCE_LEN + TAG_LEN) throw new PackError("malformed_link");
  return { key, nonce: data.slice(0, NONCE_LEN), ciphertext: data.slice(NONCE_LEN) };
}

/** `.manotu-event` bytes → { nonce, ciphertext } (§5.2). */
export function parseFile(bytes) {
  if (bytes.length > MAX_FILE_BYTES) throw new PackError("file_too_large");
  if (bytes.length < 6 + NONCE_LEN + TAG_LEN || bytes[0] !== 0x4d || bytes[1] !== 0x4e || bytes[2] !== 0x54 || bytes[3] !== 0x45)
    throw new PackError("malformed_file");
  if (bytes[4] !== 0x01) throw new PackError("unsupported_file_format", String(bytes[4]));
  if (bytes[5] !== 0x01) throw new PackError("unsupported_suite", String(bytes[5]));
  return { nonce: bytes.slice(6, 6 + NONCE_LEN), ciphertext: bytes.slice(6 + NONCE_LEN) };
}

// ---- layers ---------------------------------------------------------------------------------------------------
/** Raw DEFLATE decode with an output cap: streamed, aborted (not truncated) above `max`. */
export async function inflateRaw(data, max = MAX_INFLATED_BYTES) {
  let ds;
  try { ds = new DecompressionStream("deflate-raw"); } catch { throw new PackError("inflate", "DecompressionStream unavailable"); }
  const writer = ds.writable.getWriter();
  let stopped = false;
  const feeding = (async () => {
    for (let i = 0; i < data.length && !stopped; i += INFLATE_INPUT_CHUNK)
      await writer.write(data.subarray(i, Math.min(i + INFLATE_INPUT_CHUNK, data.length)));
    if (!stopped) await writer.close();
  })().catch(() => {});
  const reader = ds.readable.getReader();
  const chunks = [];
  let total = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.length;
      if (total > max) throw new PackError("inflate_too_large", String(max));
      chunks.push(value);
    }
  } catch (e) {
    stopped = true;
    reader.cancel().catch(() => {});
    writer.abort().catch(() => {});
    throw e instanceof PackError ? e : new PackError("inflate");
  }
  await feeding;
  const out = new Uint8Array(total);
  let o = 0;
  for (const c of chunks) { out.set(c, o); o += c.length; }
  return out;
}

/** Decrypt → bounded inflate → envelope → signature → event (§7 steps 1–2). Event JSON is parsed only after the
 *  signature verifies. Returns the exact signed bytes and the validated event. */
export async function openSealed(key, nonce, ciphertext) {
  const na = await ready();
  if (ciphertext.length < TAG_LEN) throw new PackError("decrypt");
  let deflated;
  try {
    deflated = na.crypto_aead_xchacha20poly1305_ietf_decrypt(null, ciphertext, na.from_string(AEAD_AD), nonce, key);
  } catch { throw new PackError("decrypt"); }

  const envelopeBytes = await inflateRaw(deflated, MAX_INFLATED_BYTES);
  let env;
  try { env = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(envelopeBytes)); } catch { throw new PackError("malformed_envelope"); }
  if (!env || typeof env !== "object" || Array.isArray(env) || !Number.isInteger(env.v) || env.v < 0 ||
      typeof env.event !== "string" || typeof env.pub !== "string" || typeof env.sig !== "string")
    throw new PackError("malformed_envelope");
  if (env.v !== 1) throw new PackError("unsupported_envelope_version", String(env.v));
  const pub = b64uDecode(env.pub);
  const sig = b64uDecode(env.sig);
  const eventBytes = b64uDecode(env.event);
  if (!pub || pub.length !== 32 || !sig || sig.length !== 64 || !eventBytes) throw new PackError("malformed_envelope");

  const prefix = na.from_string(SIGNATURE_PREFIX);
  const msg = new Uint8Array(prefix.length + eventBytes.length);
  msg.set(prefix); msg.set(eventBytes, prefix.length);
  let ok = false;
  try { ok = na.crypto_sign_verify_detached(sig, msg, pub); } catch { ok = false; }
  if (!ok) throw new PackError("signature");

  // Only now is the event JSON parsed.
  let text, raw;
  try { text = new TextDecoder("utf-8", { fatal: true }).decode(eventBytes); raw = JSON.parse(text); }
  catch (e) { throw new PackError("malformed_event", e.message); }
  const event = readEvent(raw);
  return {
    event, eventBytes, eventText: text, organizerPub: pub,
    organizerPubB64u: b64uEncode(pub),
    safetyCode: safetyCode(pub),
    revisionHashB64u: b64uEncode(sha256(eventBytes)),
  };
}

export async function openLink(link) {
  const { key, nonce, ciphertext } = parseLink(link);
  return openSealed(key, nonce, ciphertext);
}

export async function openFile(bytes, secretCode) {
  const { nonce, ciphertext } = parseFile(bytes);
  return openSealed(decodeSecretCode(secretCode), nonce, ciphertext);
}

/** Import decision for a viewer that stores nothing: `expired` at or after expiresAt (§7.5), else `new`. */
export function decide(event, now = new Date()) {
  return now.getTime() >= event.expiresAt.ms ? "expired" : "new";
}

// ---- event JSON (§4) ------------------------------------------------------------------------------------------
const RFC3339 = /^(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(\.\d{1,9})?([Zz]|[+-]\d{2}:\d{2})$/;

/** RFC 3339 → { ms (UTC epoch), offsetMin, iso }; null when invalid. */
export function parseTime(s) {
  if (typeof s !== "string") return null;
  const m = RFC3339.exec(s);
  if (!m) return null;
  const [y, mo, d, h, mi, se] = m.slice(1, 7).map(Number);
  const frac = m[7] ? Number("0" + m[7]) * 1000 : 0;
  let off = 0;
  if (m[8] !== "Z" && m[8] !== "z") {
    const oh = Number(m[8].slice(1, 3)), om = Number(m[8].slice(4, 6));
    if (oh > 23 || om > 59) return null;
    off = (m[8][0] === "-" ? -1 : 1) * (oh * 60 + om);
  }
  if (mo < 1 || mo > 12 || h > 23 || mi > 59 || se > 59) return null;
  const local = Date.UTC(y, mo - 1, d, h, mi, se);
  if (new Date(local).getUTCDate() !== d) return null; // e.g. 31 Nov
  return { ms: local + frac - off * 60_000, offsetMin: off, iso: s };
}

const malformed = detail => { throw new PackError("malformed_event", detail); };
const invalid = (path, reason) => { throw new PackError("invalid_event", `${path}: ${reason}`); };
const isObj = v => v !== null && typeof v === "object" && !Array.isArray(v);
const present = v => v !== undefined && v !== null;
const codePoints = s => [...s].length;

function reqStr(o, k, path) { if (typeof o[k] !== "string") malformed(`${path}${k}`); return o[k]; }
function optStr(o, k, path) { if (!present(o[k])) return undefined; if (typeof o[k] !== "string") malformed(`${path}${k}`); return o[k]; }
function u32(v, path) { if (!Number.isInteger(v) || v < 0 || v > 0xffffffff) malformed(path); return v; }
function optU32(o, k, path) { return present(o[k]) ? u32(o[k], `${path}${k}`) : undefined; }
function time(v, path) { const t = parseTime(v); if (!t) malformed(path); return t; }
function num(v, path) { if (typeof v !== "number" || !Number.isFinite(v)) malformed(path); return v; }
function arr(o, k, path) { if (!present(o[k])) return []; if (!Array.isArray(o[k])) malformed(`${path}${k}`); return o[k]; }
function oneOf(v, list, path) { if (typeof v !== "string" || !list.includes(v)) malformed(path); return v; }
function lenient(v, list, path) { if (typeof v !== "string") malformed(path); return list.includes(v) ? v : "other"; }

function text(path, s, min, max) {
  const n = codePoints(s);
  if (n < min || (min > 0 && s.trim() === "")) invalid(path, "is required");
  if (n > max) invalid(path, "is too long");
}
function optText(path, s, max) { if (s !== undefined) text(path, s, 0, max); }
function id(path, s, bytes) { const b = b64uDecode(s); if (!b || b.length !== bytes) invalid(path, "is not a valid id"); }
const sixDecimals = v => Math.abs(Math.round(v * 1e6) / 1e6 - v) <= 1e-9;
function coord(path, lat, lng) {
  if (!(lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180)) invalid(path, "coordinate out of range");
  if (!sixDecimals(lat) || !sixDecimals(lng)) invalid(path, "coordinate has more than 6 decimals");
}
function validTz(s) {
  return s.length > 0 && s.length <= LIMITS.tz && !s.startsWith("/") && !s.endsWith("/") && !s.includes("//") &&
    /^[A-Za-z0-9/_+-]+$/.test(s);
}

/** Google encoded polyline, precision 6 → [[lat, lng], …]; mirrors route-core decode_polyline. */
export function decodePolyline(s, maxPoints = Infinity) {
  const points = [];
  let i = 0, lat = 0, lng = 0;
  const read = () => {
    let result = 0, shift = 0;
    for (;;) {
      if (i >= s.length) throw new Error("truncated");
      const b = s.charCodeAt(i);
      if (b < 63 || b > 126) throw new Error("invalid char");
      i++;
      if (shift > 35) throw new Error("overflow");
      const chunk = b - 63;
      result += (chunk & 0x1f) * 2 ** shift; // no 32-bit bitwise ops: values may exceed 2^31
      shift += 5;
      if (chunk < 0x20) break;
    }
    const v = Math.floor(result / 2);
    return result % 2 === 1 ? -v - 1 : v;
  };
  while (i < s.length) {
    if (points.length >= maxPoints) { const e = new Error("too many points"); e.tooMany = true; throw e; }
    lat += read();
    if (i >= s.length) throw new Error("truncated");
    lng += read();
    const p = [lat / 1e6, lng / 1e6];
    if (!(p[0] >= -90 && p[0] <= 90 && p[1] >= -180 && p[1] <= 180)) throw new Error("out of range");
    points.push(p);
  }
  return points;
}

/** Parses and validates the event object (types → malformed_event, §4 rules → invalid_event). Returns a
 *  normalised read-only model; strings are plain data and must only ever be rendered as text. */
export function readEvent(raw) {
  if (!isObj(raw)) malformed("event is not an object");
  const e = {
    id: reqStr(raw, "id", ""),
    rev: u32(raw.rev, "rev"),
    parent: optStr(raw, "parent", "") ?? null,
    publishedAt: time(raw.publishedAt, "publishedAt"),
    template: lenient(raw.template, TEMPLATES, "template"),
    title: reqStr(raw, "title", ""),
    description: optStr(raw, "description", ""),
    lang: oneOf(raw.lang, LANGS, "lang"),
    tz: reqStr(raw, "tz", ""),
    start: time(raw.start, "start"),
    end: time(raw.end, "end"),
    expiresAt: time(raw.expiresAt, "expiresAt"),
    organizer: undefined,
    stops: [], sessions: [], routes: [], polylinePoints: 0,
  };
  if (present(raw.organizer)) {
    if (!isObj(raw.organizer)) malformed("organizer");
    e.organizer = { alias: optStr(raw.organizer, "alias", "organizer.") };
  }
  e.stops = arr(raw, "stops", "").map((s, i) => {
    const p = `stops[${i}].`;
    if (!isObj(s)) malformed(p);
    const stop = {
      id: reqStr(s, "id", p), kind: lenient(s.kind, STOP_KINDS, `${p}kind`), label: reqStr(s, "label", p),
      lat: num(s.lat, `${p}lat`), lng: num(s.lng, `${p}lng`),
      entrance: undefined, arrive: undefined, dwellMin: optU32(s, "dwellMin", p),
      notes: optStr(s, "notes", p), audience: optStr(s, "audience", p),
    };
    if (present(s.entrance)) {
      if (!isObj(s.entrance)) malformed(`${p}entrance`);
      stop.entrance = { lat: num(s.entrance.lat, `${p}entrance.lat`), lng: num(s.entrance.lng, `${p}entrance.lng`) };
    }
    if (present(s.arrive)) {
      if (!isObj(s.arrive)) malformed(`${p}arrive`);
      stop.arrive = { from: time(s.arrive.from, `${p}arrive.from`), to: time(s.arrive.to, `${p}arrive.to`) };
    }
    return stop;
  });
  e.sessions = arr(raw, "sessions", "").map((s, i) => {
    const p = `sessions[${i}].`;
    if (!isObj(s)) malformed(p);
    return { id: reqStr(s, "id", p), title: reqStr(s, "title", p), start: time(s.start, `${p}start`),
      end: time(s.end, `${p}end`), stopId: optStr(s, "stopId", p) };
  });
  e.routes = arr(raw, "routes", "").map((r, i) => {
    const p = `routes[${i}].`;
    if (!isObj(r)) malformed(p);
    return {
      id: reqStr(r, "id", p), title: optStr(r, "title", p), mode: oneOf(r.mode, MODES, `${p}mode`),
      day: optStr(r, "day", p),
      stopIds: arr(r, "stopIds", p).map((x, j) => { if (typeof x !== "string") malformed(`${p}stopIds[${j}]`); return x; }),
      polyline: reqStr(r, "polyline", p), source: oneOf(r.source, SOURCES, `${p}source`),
      distanceM: u32(r.distanceM, `${p}distanceM`), durationS: optU32(r, "durationS", p),
      ascentM: optU32(r, "ascentM", p), descentM: optU32(r, "descentM", p),
      legs: arr(r, "legs", p).map((l, j) => {
        const q = `${p}legs[${j}].`;
        if (!isObj(l)) malformed(q);
        return { fromStopId: reqStr(l, "fromStopId", q), toStopId: reqStr(l, "toStopId", q),
          distanceM: u32(l.distanceM, `${q}distanceM`), durationS: optU32(l, "durationS", q),
          source: oneOf(l.source, SOURCES, `${q}source`) };
      }),
      points: [],
    };
  });
  validate(e);
  return e;
}

function validate(e) {
  id("id", e.id, LIMITS.eventIdBytes);
  if (e.rev === 0) invalid("rev", "must be 1 or more");
  if (e.rev === 1 && e.parent !== null) invalid("parent", "must be null for rev 1");
  if (e.rev > 1) {
    if (e.parent === null) invalid("parent", "is required after rev 1");
    const b = b64uDecode(e.parent);
    if (!b || b.length !== 32) invalid("parent", "is not a SHA-256 hash");
  }
  text("title", e.title, 1, LIMITS.title);
  optText("description", e.description, LIMITS.description);
  if (!validTz(e.tz)) invalid("tz", "is not an IANA time zone name");
  if (e.end.ms < e.start.ms) invalid("end", "ends before it starts");
  if (e.end.ms - e.start.ms > LIMITS.eventDays * 86_400_000) invalid("end", "is more than 14 days after start");
  if (e.organizer) optText("organizer.alias", e.organizer.alias, LIMITS.alias);

  if (e.stops.length > LIMITS.stops) invalid("stops", "has more than 200 stops");
  const stopIds = new Set();
  e.stops.forEach((s, i) => {
    const p = f => `stops[${i}].${f}`;
    id(p("id"), s.id, LIMITS.itemIdBytes);
    if (stopIds.has(s.id)) invalid(p("id"), "is duplicated");
    stopIds.add(s.id);
    text(p("label"), s.label, 1, LIMITS.stopLabel);
    coord(p("lat"), s.lat, s.lng);
    if (s.entrance) coord(p("entrance"), s.entrance.lat, s.entrance.lng);
    if (s.arrive && s.arrive.to.ms < s.arrive.from.ms) invalid(p("arrive"), "ends before it starts");
    if (s.dwellMin !== undefined && s.dwellMin > LIMITS.dwellMin) invalid(p("dwellMin"), "is too long");
    optText(p("notes"), s.notes, LIMITS.stopNotes);
    optText(p("audience"), s.audience, LIMITS.audience);
  });
  const knownStop = (path, sid) => { if (!stopIds.has(sid)) invalid(path, "refers to an unknown stop"); };

  if (e.sessions.length > LIMITS.sessions) invalid("sessions", "has more than 50 sessions");
  const sessionIds = new Set();
  e.sessions.forEach((s, i) => {
    const p = f => `sessions[${i}].${f}`;
    id(p("id"), s.id, LIMITS.itemIdBytes);
    if (sessionIds.has(s.id)) invalid(p("id"), "is duplicated");
    sessionIds.add(s.id);
    text(p("title"), s.title, 1, LIMITS.sessionTitle);
    if (s.end.ms < s.start.ms) invalid(p("end"), "ends before it starts");
    if (s.stopId !== undefined) knownStop(p("stopId"), s.stopId);
  });

  if (e.routes.length > LIMITS.routes) invalid("routes", "has more than 20 routes");
  const routeIds = new Set();
  let points = 0;
  e.routes.forEach((r, i) => {
    const p = f => `routes[${i}].${f}`;
    id(p("id"), r.id, LIMITS.itemIdBytes);
    if (routeIds.has(r.id)) invalid(p("id"), "is duplicated");
    routeIds.add(r.id);
    optText(p("title"), r.title, LIMITS.routeTitle);
    if (r.day !== undefined) {
      const t = /^\d{4}-\d{2}-\d{2}$/.test(r.day) && parseTime(`${r.day}T00:00:00Z`);
      if (!t) invalid(p("day"), "is not a YYYY-MM-DD date");
    }
    r.stopIds.forEach((sid, j) => knownStop(p(`stopIds[${j}]`), sid));
    try { r.points = decodePolyline(r.polyline, LIMITS.polylinePoints - points); }
    catch (err) {
      invalid(p("polyline"), err.tooMany ? "routes have more than 50 000 polyline points in total" : "is not a valid precision-6 polyline");
    }
    if (r.points.length < 2) invalid(p("polyline"), "needs at least 2 points");
    points += r.points.length;
    r.legs.forEach((l, j) => {
      knownStop(p(`legs[${j}].fromStopId`), l.fromStopId);
      knownStop(p(`legs[${j}].toStopId`), l.toStopId);
    });
  });
  e.polylinePoints = points;
}

/** Where navigation goes: the vehicle entrance if present, else the pin (§7.7). */
export const navTarget = stop => stop.entrance ?? { lat: stop.lat, lng: stop.lng };
