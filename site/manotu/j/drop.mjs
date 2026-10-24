// /j/ entry (docs/events.md §5.6): short link #<dropId>.<b64u(secret)>. Only the dropId goes to the API
// (GET /api/v1/drops/<dropId>, no cookies, no referrer); the secret stays in this page, derives the key with HKDF
// and opens the blob exactly like an /a/ link. The shared viewer (../a/event.mjs) renders it.
import { parseDropLink, openDrop, dropFragment, b64uEncode, PackError } from "../a/pack.mjs";
import { startViewer, schemeLink } from "../a/event.mjs";

const cfg = window.MANOTU_EVENT_CONFIG || {};
const fragment = location.hash.slice(1);
// Optional dev override (?api=http://host:5080), as in /s/; the CSP still limits where the page may connect.
const apiParam = new URLSearchParams(location.search).get("api");
const api = (apiParam && /^https?:\/\/[\w.:-]+$/.test(apiParam) ? apiParam : cfg.apiBaseUrl || "").replace(/\/$/, "");

let link = null;
try { link = parseDropLink(fragment); } catch { link = null; }

/** GET the blob. 404/410 → drop_gone; no answer, 429 or 5xx → offline (the page offers a retry). */
async function fetchBlob(dropId) {
  let res;
  try {
    res = await fetch(`${api}/api/v1/drops/${encodeURIComponent(dropId)}`,
      { cache: "no-store", credentials: "omit", referrerPolicy: "no-referrer", redirect: "error" });
  } catch { throw new PackError("offline"); }
  if (res.status === 404 || res.status === 410) throw new PackError("drop_gone");
  if (!res.ok) throw new PackError("offline", `HTTP ${res.status}`);
  try { return new Uint8Array(await res.arrayBuffer()); } catch { throw new PackError("offline"); }
}

await startViewer({
  fragment,
  async open() {
    if (!link) throw new PackError("malformed_drop_link");
    return openDrop(await fetchBlob(link.dropId), link.secret);
  },
  // §5.6: manotu://event?drop=<dropId>[&stop=<stopId>]#<secret>
  appLink: stopId => schemeLink({ drop: link.dropId, stop: stopId }, b64uEncode(link.secret)),
  getAppLink: link ? `${(cfg.linkSite || "https://manotu.app").replace(/\/$/, "")}/j/#${dropFragment(link.dropId, link.secret)}` : null,
});
