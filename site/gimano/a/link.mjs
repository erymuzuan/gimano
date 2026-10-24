// /a/ entry (docs/events.md §5.1): the whole plan is in location.hash (#1.<key>.<nonce‖ciphertext>). This page
// calls no API; the shared viewer (event.mjs) decrypts, verifies and renders it.
import { openLink } from "./pack.mjs";
import { startViewer, schemeLink } from "./event.mjs";

const fragment = location.hash.slice(1);
await startViewer({
  fragment,
  open: () => openLink(fragment),
  // §5.3: manotu://event[?stop=<stopId>]#<the same fragment>
  appLink: stopId => schemeLink({ stop: stopId }, fragment),
});
