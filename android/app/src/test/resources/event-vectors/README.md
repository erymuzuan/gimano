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
| `ride-two-day-stops-only.json` | positive | open like any positive vector; it is the stops-only variant (docs/events.md §5.5) of `ride-two-day`: `expected.stopsOnly` is true, no route has a `polyline`, and `revisionHashB64u` is `variant.fullHash` (= `fullHashB64u` = `ride-two-day`'s `revisionHashB64u`), **not** SHA-256 of its own bytes (`eventBytesHashB64u`) |
| `negative-variant-with-polyline.json` | negative | decrypt and verify, then reject the event with `variant_has_polyline` |
| `negative-missing-polyline.json` | negative | decrypt and verify, then reject the event with `missing_polyline` (a full event without `routes[0].polyline`) |
| `negative-unknown-variant.json` | negative | decrypt and verify, then reject the event with `unsupported_variant` (`variant.kind` `"thumbnail"`) |
| `import-stops-only-sequence.json` | import-sequence | replay each sequence against an empty store and get every `decision`, `parentCheck` and `storedAfter` (docs/events.md §7.4) |

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
| `safetyCode` | first 30 bits of SHA-256(pub) as 6 chars of `23456789ABCDEFGHJKLMNPQRSTUVWXYZ` (docs/events.md §7.3) |
| `revisionHashB64u` | SHA-256(eventBytes), the next revision's `parent` |
| `now` | time to use for the import decision |
| `expected` | summary of the decoded event and the import decision |

Negative vectors carry `expectedError` (the stable code from `event_core::Error::code()`, or `expired`
for the import decision) plus only the inputs they need. The three variant negatives also carry the
signed `eventJson`, so a reader can test its event parser without the crypto layers.

## Stops-only variant (§5.5) and import sequence (§7.4)

`ride-two-day-stops-only.json` is signed with the **same** Ed25519 seed and encrypted with the same key
as `ride-two-day.json` (its own nonce). Extra fields: `fullVector` (`"ride-two-day"`), `fullHashB64u`
(SHA-256 of the full `eventJson`, also in `variant.fullHash`), `eventBytesHashB64u` (SHA-256 of the
variant's own bytes, which is **not** its revision hash) and `expected.stopsOnly: true`. Full-event
vectors have no `stopsOnly` (read it as false).

The reference writer (`event_core::stops_only_variant`) makes the variant deterministically from the
full bytes: parse, drop every route's `polyline`, append `"variant":{"kind":"stops-only","fullHash":…}`
as the last field, serialize in the writer's own field order. Other writers do not have to reproduce
these bytes; any valid variant with the right `fullHash`, signed with the event's key, is correct.

Error codes for the variant rules: `variant_has_polyline` (a stops-only variant with a `polyline` on
some route), `missing_polyline` (a full event without one), `unsupported_variant` (`variant.kind` other
than `stops-only`). A `variant.fullHash` that is not 32 bytes of b64u is `invalid_event`;
`variant: null` reads as a full event.

`import-stops-only-sequence.json` has `packs` (`full` = the ride-two-day link, `variant` = the
ride-two-day-stops-only link, `rev2` = a full rev 2 drafted from the full rev 1, so its `parent` is
`fullHash`) and `sequences`. For each sequence start with an empty store and import the packs in order at
`now`, applying each decision the way an app does:

| `decision` | Store change |
|---|---|
| `new` | store the pack (pin `pub`) |
| `upgrade_to_full` | replace the stored stops-only revision of that `rev` (same revision hash) with the full event, silently ("laluan penuh ditambah") |
| `newer`, `fork` | add the revision |
| `already_imported`, `older` | nothing |

Decisions compare **revision hashes** (`fullHash` for a variant), so a variant and its full event are the
same revision, not a fork: full over a stored variant → `upgrade_to_full`; variant over the stored full →
`already_imported`; the same variant again → `already_imported`. The `parent` check of a later revision
uses the stored side's revision hash, so rev 2 over a stored variant gives `parentCheck: "matches"`.
`storedAfter` lists the store after each step as `{rev, revisionHashB64u, stopsOnly}`.

## Simplification (§5.4)

Writers simplify each route's polyline with Douglas–Peucker at 5 m before signing
(`route_core::simplify`, `Event::simplified`; `event_core::seal` applies it). The output is not
required to be byte-identical across implementations, and readers never re-simplify, so no vector pins
it. The `ride-two-day` and `negative-oversized-fragment` events are reader fixtures signed as written
(`Event::to_bytes`, no simplification): `ride-two-day`'s last three points are collinear, so a writer
would publish 6 of its 7 points.

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
