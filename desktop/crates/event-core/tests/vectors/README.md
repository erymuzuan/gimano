# Manotu event pack test vectors

Shared by every implementation of docs/events.md: the desktop Rust core, the web viewer
(`site/manotu/a/`), Android and iOS. All data is fictional. Regenerate with
`cargo run -p event-core --example gen_vectors` (from `desktop/`), and update docs/events.md in the same
change if the format changes.

Key material is fixed bytes (SHA-256 of a label, see `tests/common/fixtures.rs`). Do not use it for
anything except tests.

## Files

| File | Kind | Reader must |
|---|---|---|
| `kenduri-one-day.json` | positive | open the link/fragment and the file + secret code to `eventJson`; decision `new` |
| `ride-two-day.json` | positive | same, with 1 route (7 points, 2 legs, ascent/descent) |
| `negative-tampered-ciphertext.json` | negative | fail with `decrypt` |
| `negative-bad-signature.json` | negative | decrypt and inflate, then fail with `signature` |
| `negative-wrong-key.json` | negative | fail with `decrypt` (link, and file + `secretCode`) |
| `negative-expired.json` | negative | decrypt and verify, then decide `expired` at `now` and import nothing |
| `negative-oversized-fragment.json` | negative | reject `linkFragment` (> 16 000 chars) with `fragment_too_long` **before decoding**; the same pack in `fileB64u` + `secretCode` must open |

## Positive vector fields

All binary values are base64url without padding (`…B64u`); some are also given as hex.

| Field | Meaning |
|---|---|
| `ed25519SeedB64u` / `Hex` | 32-byte Ed25519 seed (libsodium `crypto_sign_seed_keypair`) |
| `organizerPubB64u` | 32-byte public key (`pub` in the envelope) |
| `keyB64u` / `Hex` | 32-byte XChaCha20-Poly1305 key |
| `nonceB64u` / `Hex` | 24-byte nonce |
| `eventJson`, `eventB64u` | the signed event bytes (UTF-8) |
| `signatureB64u` | Ed25519 over `"manotu-event-v1\n" ‖ eventBytes` |
| `envelopeJson`, `envelopeB64u` | `{"v":1,"event":…,"pub":…,"sig":…}` bytes |
| `deflatedB64u` | raw DEFLATE (RFC 1951) of the envelope bytes |
| `ciphertextB64u` | XChaCha20-Poly1305-IETF(key, nonce, AD `manotu-event-v1`) of the deflated bytes: ciphertext ‖ 16-byte tag, **without** the nonce |
| `linkFragment` | `1.<b64u(key)>.<b64u(nonce ‖ ciphertext)>` (no `#`) |
| `link` | `<site>/a/#<linkFragment>` |
| `fileB64u` | `MNTE` ‖ `01` ‖ `01` ‖ nonce ‖ ciphertext |
| `secretCode` | Crockford base32 of the key, 52 chars in groups of 4 |
| `safetyCode` | first 8 bytes of SHA-256(pub), upper-case hex, 4 groups of 4 |
| `revisionHashB64u` | SHA-256(eventBytes), the next revision's `parent` |
| `now` | time to use for the import decision |
| `expected` | summary of the decoded event and the import decision |

Negative vectors carry `expectedError` (the stable code from `event_core::Error::code()`, or `expired`
for the import decision) plus only the inputs they need.

## What must be byte-identical, and what must not

Raw DEFLATE output is **not** unique: zlib, miniz, Apple `Compression` and browser
`CompressionStream` may compress the same envelope differently, and all are correct. Therefore:

- **Readers** must accept the given `deflatedB64u` / `ciphertextB64u` / `linkFragment` / `fileB64u`
  exactly as committed (decode direction), and reproduce `eventJson`, `organizerPubB64u`,
  `safetyCode` and `revisionHashB64u`.
- **Writers** must reproduce byte-for-byte every deterministic layer: the Ed25519 public key and
  `signatureB64u` from the seed, `envelopeJson` from the event bytes, `ciphertextB64u` when encrypting the
  vector's `deflatedB64u` with the vector key and nonce, `fileB64u` / `linkFragment` framing, and the
  secret and safety codes. Their own DEFLATE output only has to **round-trip** (inflate back to the same
  envelope bytes); a writer's link or file for the same event may differ from the vector.
- Event JSON bytes are not canonicalised: readers keep and hash the signed bytes as received and
  never re-serialize them.

The desktop core is checked against these rules in `tests/vectors.rs`; the vectors were also
cross-checked with the vendored libsodium-wrappers and Node `zlib.inflateRawSync`.

## Reading rules worth testing on every platform

- Base64url decoding is strict: no padding, no non-canonical trailing bits.
- Ed25519 verification is strict (`verify_strict` / libsodium `crypto_sign_verify_detached`).
- Inflate output is bounded to 20 MiB; a larger output is an error, not a truncation.
- The secret code decoder ignores `-` and whitespace, is case-insensitive, reads `I`/`L` as `1` and
  `O` as `0`, and rejects non-zero padding bits in the last character.
- Text limits count Unicode scalar values (code points), not UTF-16 units or bytes.
- Unknown JSON fields are ignored; unknown `template` / stop `kind` values read as `other`.
