// Pure formatting helpers (unit-tested with vitest).

export function distance(m: number): string {
  if (m < 1000) return `${Math.round(m)} m`;
  const km = m / 1000;
  return `${km < 100 ? km.toFixed(1) : Math.round(km)} km`;
}

export function duration(s: number): string {
  const min = Math.round(s / 60);
  if (min < 60) return `${min} min`;
  const h = Math.floor(min / 60);
  const r = min % 60;
  return r ? `${h} j ${r} min` : `${h} j`;
}

/** "2026-11-15T11:00:00+08:00" → "2026-11-15T11:00" for <input type=datetime-local>. */
export function toLocalInput(rfc3339: string): string {
  const m = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})/.exec(rfc3339);
  return m ? m[1] : "";
}

/** Offset of an RFC 3339 string ("+08:00"), default Malaysia time. */
export function offsetOf(rfc3339: string, fallback = "+08:00"): string {
  const m = /([+-]\d{2}:\d{2}|Z)$/.exec(rfc3339.trim());
  return m ? (m[1] === "Z" ? "+00:00" : m[1]) : fallback;
}

/** Back from datetime-local, keeping the event's offset. Empty stays empty. */
export function fromLocalInput(local: string, offset: string): string {
  if (!local) return "";
  const withSeconds = local.length === 16 ? `${local}:00` : local;
  return `${withSeconds}${offset}`;
}

/** Parses "lat, lng" typed by the user; rejects out-of-range values. */
export function parseCoords(s: string): { lat: number; lng: number } | null {
  const m = /^\s*(-?\d+(?:\.\d+)?)\s*[,;\s]\s*(-?\d+(?:\.\d+)?)\s*$/.exec(s);
  if (!m) return null;
  const lat = Number(m[1]);
  const lng = Number(m[2]);
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) return null;
  return { lat: Math.round(lat * 1e6) / 1e6, lng: Math.round(lng * 1e6) / 1e6 };
}

export const coordText = (lat: number, lng: number) => `${lat.toFixed(6)}, ${lng.toFixed(6)}`;

/** Dates between start and end (inclusive) as YYYY-MM-DD, using the strings' own calendar dates. */
export function daysBetween(start: string, end: string): string[] {
  const a = /^(\d{4}-\d{2}-\d{2})/.exec(start)?.[1];
  const b = /^(\d{4}-\d{2}-\d{2})/.exec(end)?.[1];
  if (!a || !b) return [];
  const out: string[] = [];
  const d = new Date(`${a}T00:00:00Z`);
  const last = new Date(`${b}T00:00:00Z`);
  while (d <= last && out.length < 15) {
    out.push(d.toISOString().slice(0, 10));
    d.setUTCDate(d.getUTCDate() + 1);
  }
  return out;
}

/** Human date/time from RFC 3339 without converting time zones (shows the event's local time). */
export function when(rfc3339: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(rfc3339);
  return m ? `${m[3]}/${m[2]}/${m[1]} ${m[4]}:${m[5]}` : rfc3339;
}

/** Maps "stops[3].label" to the stop index and field. */
export function parsePath(path: string): { list?: string; index?: number; field?: string } {
  const m = /^(\w+)\[(\d+)\](?:\.(.+))?$/.exec(path);
  if (m) return { list: m[1], index: Number(m[2]), field: m[3] };
  return { field: path };
}
