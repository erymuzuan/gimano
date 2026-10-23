// End-to-end check of "Share my drive" with the same vendored libsodium the viewer uses.
//   node site/gimano/s/test/e2e-share.mjs http://100.101.150.9:5080
// Creates a device token, creates a share, encrypts a position (XChaCha20-Poly1305, AD = shareId), PUTs it,
// GETs it back, decrypts and compares; then checks auth (401), size limit (413) and the end (DELETE → 410).
import _sodium from "../vendor/libsodium-wrappers.mjs";
import assert from "node:assert/strict";

const api = (process.argv[2] || "http://localhost:5080").replace(/\/$/, "");
await _sodium.ready;
const sodium = _sodium;
const j = async (method, path, body, headers = {}) => {
  const r = await fetch(api + path, { method, headers: { "content-type": "application/json", ...headers }, body: body ? JSON.stringify(body) : undefined });
  const text = await r.text();
  return { status: r.status, body: text ? JSON.parse(text) : null, headers: r.headers };
};

const auth = await j("POST", "/api/v1/auth/device", { deviceId: "share-e2e-" + Date.now(), platform: "android" });
assert.equal(auth.status, 200);
const bearer = { authorization: `Bearer ${auth.body.token}` };

const created = await j("POST", "/api/v1/shares", { expiresInMinutes: 30 }, bearer);
assert.equal(created.status, 201);
const { shareId, writeToken } = created.body;
const key = sodium.crypto_aead_xchacha20poly1305_ietf_keygen();
const link = `https://www.bukitbunga.com/gimano/s/#${shareId}.${sodium.to_base64(key, sodium.base64_variants.URLSAFE_NO_PADDING)}`;

const position = { lat: 3.1180, lng: 101.6190, heading: 90, speedKmh: 42, etaIso: new Date(Date.now() + 900_000).toISOString(),
  remainingM: 11289, destinationName: "Mid Valley", status: "driving", ts: new Date().toISOString() };
const nonce = sodium.randombytes_buf(sodium.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES);
const ct = sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(sodium.from_string(JSON.stringify(position)), sodium.from_string(shareId), null, nonce, key);
const b64 = b => sodium.to_base64(b, sodium.base64_variants.ORIGINAL);
const share = { authorization: `Share ${writeToken}` };

assert.equal((await j("GET", `/api/v1/shares/${shareId}`)).body.ciphertext, null, "no blob before the first PUT");
assert.equal((await j("PUT", `/api/v1/shares/${shareId}`, { nonce: b64(nonce), ciphertext: b64(ct) }, { authorization: "Share wrong" })).status, 401);
assert.equal((await j("PUT", `/api/v1/shares/${shareId}`, { nonce: b64(nonce), ciphertext: b64(ct) }, share)).status, 204);

const got = await j("GET", `/api/v1/shares/${shareId}`, null, { origin: "https://www.bukitbunga.com" });
assert.equal(got.status, 200);
const plain = sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(null, sodium.from_base64(got.body.ciphertext, sodium.base64_variants.ORIGINAL),
  sodium.from_string(shareId), sodium.from_base64(got.body.nonce, sodium.base64_variants.ORIGINAL), key);
assert.deepEqual(JSON.parse(sodium.to_string(plain)), position);
console.log("decrypted ok; CORS:", got.headers.get("access-control-allow-origin"));

const big = sodium.randombytes_buf(1100);
assert.equal((await j("PUT", `/api/v1/shares/${shareId}`, { nonce: b64(nonce), ciphertext: b64(big) }, share)).status, 413);

assert.equal((await j("DELETE", `/api/v1/shares/${shareId}`, null, share)).status, 204);
assert.equal((await j("GET", `/api/v1/shares/${shareId}`)).status, 410);
console.log("lifecycle ok (401, 204, 200, 413, 204, 410). Viewer link format:", link.replace(/\.[^.]+$/, ".<key>"));
