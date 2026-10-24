// Runs the viewer's pack reader against every shared vector (desktop/crates/event-core/tests/vectors).
//   node site/manotu/a/test/vectors.mjs [vectorsDir]
// Positive: link, bare fragment and file + secret code open to the exact signed bytes, the organizer key, safety
// code, revision hash and the expected summary/decision. Negative: the expected error code (or `expired`).
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createHash } from "node:crypto";
import { sha256, safetyCode, openLink, openFile, decide, decideImport, readEvent, b64uDecode, b64uEncode, decodePolyline, inflateRaw, PackError, parseLink } from "../pack.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, "../../../..");
const dir = process.argv[2] ? resolve(process.argv[2]) : join(repo, "desktop/crates/event-core/tests/vectors");
const files = readdirSync(dir).filter(f => f.endsWith(".json")).sort();
assert.ok(files.length >= 12, `expected the vectors in ${dir}`);

const code = async p => { try { await p; return "ok"; } catch (e) { if (e instanceof PackError) return e.code; throw e; } };
let passed = 0, failed = 0;
async function check(name, fn) {
  try { await fn(); passed++; console.log(`  ok   ${name}`); }
  catch (e) { failed++; console.log(`  FAIL ${name}\n       ${e.stack || e}`); }
}

for (const f of files) {
  const v = JSON.parse(readFileSync(join(dir, f), "utf8"));
  console.log(`${f} (${v.kind})`);
  // The viewer opens links and suite-1 files only; Argon2id (suite 2) needs the sumo libsodium build (docs/events.md §5.2).
  const suite2File = v.fileB64u && b64uDecode(v.fileB64u)[5] === 2;
  if (v.kind === "kat" || (suite2File && !v.linkFragment)) { console.log("  skip suite 2 (file only)"); continue; }
  if (v.kind === "positive") {
    const now = new Date(v.now);
    const verify = (o, how) => {
      assert.equal(o.eventText, v.eventJson, `${how}: signed bytes`);
      assert.deepEqual(o.eventBytes, b64uDecode(v.eventB64u), `${how}: eventB64u`);
      assert.equal(o.organizerPubB64u, v.organizerPubB64u, `${how}: pub`);
      assert.equal(o.safetyCode, v.safetyCode, `${how}: safety code`);
      assert.equal(o.revisionHashB64u, v.revisionHashB64u, `${how}: revision hash`);
      // §5.5: a stops-only variant's revision hash is fullHash; its own bytes hash differently.
      assert.equal(o.stopsOnly, v.expected.stopsOnly ?? false, `${how}: stopsOnly`);
      if (v.eventBytesHashB64u) assert.equal(o.eventBytesHashB64u, v.eventBytesHashB64u, `${how}: bytes hash`);
      if (v.fullHashB64u) assert.equal(o.event.variant.fullHash, v.fullHashB64u, `${how}: fullHash`);
      const x = v.expected, e = o.event;
      assert.equal(e.id, x.id); assert.equal(e.rev, x.rev); assert.equal(e.title, x.title);
      assert.equal(e.stops.length, x.stops); assert.equal(e.sessions.length, x.sessions);
      assert.equal(e.routes.length, x.routes); assert.equal(e.polylinePoints, x.polylinePoints);
      assert.equal(decide(e, now), x.decision);
      // The model carries the same values as the signed JSON (spot-check every stop and route).
      const raw = JSON.parse(v.eventJson);
      raw.stops.forEach((s, i) => {
        assert.equal(e.stops[i].id, s.id); assert.equal(e.stops[i].label, s.label);
        assert.equal(e.stops[i].lat, s.lat); assert.equal(e.stops[i].lng, s.lng);
        assert.deepEqual(e.stops[i].entrance, s.entrance);
        assert.equal(e.stops[i].notes, s.notes); assert.equal(e.stops[i].audience, s.audience);
      });
      (raw.routes ?? []).forEach((r, i) => {
        assert.equal(e.routes[i].points.length, r.polyline === undefined ? 0 : decodePolyline(r.polyline).length);
        assert.equal(e.routes[i].distanceM, r.distanceM); assert.equal(e.routes[i].durationS, r.durationS);
      });
    };
    await check("link", async () => verify(await openLink(v.link), "link"));
    await check("fragment", async () => verify(await openLink(v.linkFragment), "fragment"));
    await check("file + secret code", async () => verify(await openFile(b64uDecode(v.fileB64u), v.secretCode), "file"));
    await check("lower-case secret code with spaces", async () =>
      verify(await openFile(b64uDecode(v.fileB64u), v.secretCode.toLowerCase().replaceAll("-", " ")), "file"));
    await check("deflatedB64u inflates to envelopeJson", async () =>
      assert.equal(new TextDecoder().decode(await inflateRaw(b64uDecode(v.deflatedB64u))), v.envelopeJson));
    await check("link fragment layout", async () => {
      const p = parseLink(v.linkFragment);
      assert.equal(b64uEncode(p.key), v.keyB64u); assert.equal(b64uEncode(p.nonce), v.nonceB64u);
      assert.equal(b64uEncode(p.ciphertext), v.ciphertextB64u);
    });
    if (v.fullVector) await check(`fullHash = SHA-256 of ${v.fullVector} eventJson`, async () => {
      const full = JSON.parse(readFileSync(join(dir, `${v.fullVector}.json`), "utf8"));
      assert.equal(b64uEncode(sha256(new TextEncoder().encode(full.eventJson))), v.fullHashB64u);
      assert.equal(v.revisionHashB64u, full.revisionHashB64u);
    });
  } else if (v.kind === "import-sequence") {
    const now = new Date(v.now);
    for (const seq of v.sequences) {
      await check(seq.name, async () => {
        let store = null;
        for (const step of seq.steps) {
          const o = await openLink(v.packs[step.import].linkFragment);
          assert.equal(o.revisionHashB64u, v.packs[step.import].revisionHashB64u, `${step.import}: revision hash`);
          assert.equal(o.stopsOnly, v.packs[step.import].stopsOnly);
          const d = decideImport(o, store, now);
          assert.equal(d.decision, step.decision, `${step.import}: decision`);
          assert.equal(d.parentCheck ?? null, step.parentCheck, `${step.import}: parent check`);
          const entry = { rev: o.event.rev, revisionHashB64u: o.revisionHashB64u, stopsOnly: o.stopsOnly };
          if (d.decision === "new") store = { organizerPubB64u: o.organizerPubB64u, revisions: [entry] };
          else if (d.decision === "upgrade_to_full")
            store.revisions = store.revisions.map(r => r.rev === d.rev && r.revisionHashB64u === o.revisionHashB64u ? entry : r);
          else if (d.decision === "newer" || d.decision === "fork") store.revisions.push(entry);
          assert.deepEqual(store.revisions, step.storedAfter, `${step.import}: stored after`);
        }
      });
    }
  } else {
    const want = v.expectedError;
    if (v.eventJson && want !== "expired") await check(`eventJson → ${want}`, () =>
      assert.equal((() => { try { readEvent(JSON.parse(v.eventJson)); return "ok"; } catch (e) { return e.code; } })(), want));
    if (want === "expired") {
      await check("verifies, then decision expired", async () => {
        const o = await openLink(v.linkFragment);
        assert.equal(decide(o.event, new Date(v.now)), "expired");
      });
    } else {
      await check(`link → ${want}`, async () => assert.equal(await code(openLink(v.linkFragment)), want));
      if (v.link) await check(`full link → ${want}`, async () => assert.equal(await code(openLink(v.link)), want));
      if (v.fileB64u && v.secretCode) {
        const fileWant = want === "fragment_too_long" ? "ok" : want;
        await check(`file + secret code → ${fileWant}`, async () => {
          const r = await code(openFile(b64uDecode(v.fileB64u), v.secretCode));
          assert.equal(r, fileWant);
          if (v.fileEventId) assert.equal((await openFile(b64uDecode(v.fileB64u), v.secretCode)).event.id, v.fileEventId);
        });
      }
    }
  }
}

// Reader rules worth testing on every platform (vectors README).
console.log("reader rules");
await check("b64u is strict", () => {
  assert.equal(b64uDecode("AA=="), null); assert.equal(b64uDecode("AB"), null); assert.deepEqual(b64uDecode("AA"), new Uint8Array([0]));
  assert.equal(b64uDecode("A+"), null); assert.equal(b64uDecode("A"), null);
});
await check("inflate output is capped, not truncated", async () => {
  const { deflateRawSync } = await import("node:zlib");
  const big = deflateRawSync(new Uint8Array(3 * 1024 * 1024));
  assert.equal(await code(inflateRaw(big, 1024 * 1024)), "inflate_too_large");
  assert.equal((await inflateRaw(big, 4 * 1024 * 1024)).length, 3 * 1024 * 1024);
  assert.equal(await code(inflateRaw(new Uint8Array([0xff, 0xff, 0xff]))), "inflate");
});
await check("malformed links", async () => {
  assert.equal(await code(openLink("#2.a.b")), "unsupported_link_version");
  assert.equal(await code(openLink("#1.abc")), "malformed_link");
  assert.equal(await code(openLink("")), "malformed_link");
});
await check("polyline precision 6 (route-core vector)", () =>
  assert.deepEqual(decodePolyline("_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI"), [[38.5, -120.2], [40.7, -120.95], [43.252, -126.453]]));

await check("sha256 matches node:crypto (0..300 bytes) and the §7.3 zero-key safety code", () => {
  for (let n = 0; n <= 300; n++) {
    const d = Uint8Array.from({ length: n }, (_, i) => (i * 131 + n) & 255);
    assert.equal(Buffer.from(sha256(d)).toString("hex"), createHash("sha256").update(d).digest("hex"), `len ${n}`);
  }
  assert.equal(safetyCode(new Uint8Array(32)), "ETN9PD");
});

console.log(`\n${passed} passed, ${failed} failed (${files.length} vector files from ${dir})`);
process.exit(failed ? 1 : 0);
