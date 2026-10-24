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
| `kenduri-one-day.json` | positive | open the link/fragment, the suite-3 file (`fileV3B64u`, no code), the suite-2 file (`fileV2B64u` + `secretCodeV2`), the suite-1 file (`fileB64u` + `secretCode`) and the short-link blob (`dropBlobB64u` + `secretB64u`, §5.6) to `eventJson`; decision `new`. Its event has 2 `info` blocks (`dress`, `bring`) |
| `ride-two-day.json` | positive | same, with 1 route (7 points, 2 legs, ascent/descent) and no `info` |
| `unknown-template-and-info-kind.json` | parse | open `linkFragment` and `fileV3B64u` (no code) to `eventJson` (`template` `"gala"`, `info[1].kind` `"weather"`): `expected.template` is `other`, `expected.infoKinds` is `["dress","other"]`, texts as `expected.infoTexts`; keep the signed bytes (`revisionHashB64u`) |
| `negative-info-text-too-long.json` | negative | decrypt and verify (link, or suite-3 `fileV3B64u`), then reject the event with `invalid_event` at `expectedPath` `info[1].text` (1003 code points) |
| `negative-too-many-info.json` | negative | same, 13 info blocks: `invalid_event` at `info` |
| `negative-file-v3-truncated.json` | negative | every entry of `filesB64u` (suite-3 files cut to `lengths` 6, 37, 61, 77 bytes) fails with `malformed_file` before any decryption |
| `negative-drop-link.json` | negative | every entry of `links` (bad dropId character or length, secret not 16 bytes of strict b64u, wrong path or no `drop=`; `reasons` says which) fails with `malformed_drop_link` before any request |
| `negative-drop-blob-truncated.json` | negative | every entry of `blobsB64u` (the kenduri drop blob cut to `lengths` 0, 23, 24, 39 bytes) fails with `malformed_drop` before any decryption |
| `negative-drop-wrong-secret.json` | negative | the kenduri drop blob with a well-formed different secret (`dropKeyB64u` is its HKDF key): fail with `decrypt` |
| `negative-tampered-ciphertext.json` | negative | fail with `decrypt` |
| `negative-bad-signature.json` | negative | decrypt and inflate, then fail with `signature` |
| `negative-wrong-key.json` | negative | fail with `decrypt` (link, and suite-1 file + `secretCode`) |
| `negative-wrong-secret-code.json` | negative | the kenduri suite-2 file with a well-formed but different 8-character `secretCode`: derive, then fail with `decrypt` |
| `negative-invalid-secret-code.json` | negative | the kenduri suite-2 file with each of `secretCodes` (each has an `O`, `0`, `I` or `1`): fail with `invalid_secret_code` **before** running Argon2id |
| `argon2-kat.json` | kat | for every `argon2Kat` entry, read `code` leniently to `password` and derive `key` from it and the salt (suite-2 file key, below) |
| `negative-expired.json` | negative | decrypt and verify, then decide `expired` at `now` and import nothing |
| `negative-oversized-fragment.json` | negative | reject `linkFragment` (> 16 000 chars) with `fragment_too_long` **before decoding**; the same pack in the suite-1 `fileB64u` + `secretCode` must open |
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
| `fileB64u` | **suite 1** (read only): `MNTE` ‖ `01` ‖ `01` ‖ nonce ‖ ciphertext, opened with `secretCode` |
| `secretCode` | suite 1: Crockford base32 of the link key, 52 chars in groups of 4 |
| `secretCodeV2` | suite 2: the 8-character file code `XXXX-XXXX` (alphabet `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`) |
| `fileSaltB64u` | suite 2: the file's 16-byte Argon2id salt |
| `fileNonceV2B64u` | suite 2: the file's own 24-byte nonce |
| `fileKeyV2B64u` | suite 2: Argon2id13(`secretCodeV2` upper case without `-`, `fileSaltB64u`, opslimit 3, 64 MiB) — independent of `keyB64u` |
| `fileV3B64u` | **suite 3** (default share): `MNTE` ‖ `01` ‖ `03` ‖ key ‖ nonce ‖ ciphertext, where key = `keyB64u`, nonce = `nonceB64u`, ciphertext = `ciphertextB64u`: exactly the link's bytes. Opens with no code |
| `fileV2B64u` | **suite 2**: `MNTE` ‖ `01` ‖ `02` ‖ salt ‖ nonce ‖ XChaCha20-Poly1305(`fileKeyV2B64u`, nonce, AD `manotu-event-v1`) of `deflatedB64u` |
| `dropId` | short link (§5.6): 10 characters of base58 `123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz` (chosen by the server; fixed here) |
| `secretB64u` | short link: the 16-byte secret (22 b64u characters) |
| `dropKeyB64u` | short link: HKDF-SHA256(ikm = secret, salt = empty, info = ASCII `manotu-drop-v1`, L = 32), so HKDF can be checked alone |
| `dropNonceB64u` | short link: the blob's 24-byte nonce |
| `dropBlobB64u` | short link: nonce ‖ XChaCha20-Poly1305(`dropKeyB64u`, nonce, AD `manotu-event-v1`) of `deflatedB64u`, the bytes `GET /api/v1/drops/{dropId}` returns |
| `dropFragment`, `dropLink`, `dropAppLink` | `<dropId>.<secretB64u>`, `https://manotu.app/j/#<dropFragment>` and `manotu://event?drop=<dropId>#<secretB64u>` |
| `safetyCode` | first 30 bits of SHA-256(pub) as 6 chars of `23456789ABCDEFGHJKLMNPQRSTUVWXYZ` (docs/events.md §7.3) |
| `revisionHashB64u` | SHA-256(eventBytes), the next revision's `parent` |
| `now` | time to use for the import decision |
| `expected` | summary of the decoded event and the import decision |

Negative vectors carry `expectedError` (the stable code from `event_core::Error::code()`, or `expired`
for the import decision) plus only the inputs they need. The three variant negatives and the two info
negatives also carry the signed `eventJson`, so a reader can test its event parser without the crypto
layers. Validation failures are all `invalid_event`; the info negatives add `expectedPath`, the field the
reference core reports (`ValidationError.path`).

## File suites (§5.2) and Argon2id

Writers produce **suite 3** (default share) and **suite 2** (sensitive events); readers open suites 1, 2 and
3, dispatching on byte 5 of the file. Any other suite byte is `unsupported_suite`.

- Suite 3 needs no code: bytes 6..38 are the pack key, 38..62 the nonce, the rest ciphertext ‖ tag. A file shorter
  than 6 + 32 + 24 + 16 bytes is `malformed_file`. Build it in tests from a link: decode the fragment
  `1.<key>.<nonce ‖ ciphertext>` and write `MNTE 01 03 key nonce ciphertext`; it must equal `fileV3B64u`. It is
  always a full event (never a stops-only variant) and has no 16 000-character limit, only 20 MiB. Apps decide
  whether to prompt for a code from byte 5 (reference: `file_needs_code`), never by trying.

- Suite 2 key: libsodium `crypto_pwhash(32, password, salt, opslimit 3, memlimit 67 108 864,
  crypto_pwhash_ALG_ARGON2ID13)`, i.e. Argon2id v1.3, t = 3, m = 65 536 KiB, 1 lane, 32-byte output
  (RustCrypto `argon2`: `Params::new(65536, 3, 1, Some(32))`). `password` is the code read leniently: upper
  case, spaces and `-` removed, exactly 8 characters of `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`. Any other
  character (including `0`, `O`, `1`, `I`) is `invalid_secret_code`, checked before deriving; a wrong length is
  also `invalid_secret_code`; a well-formed wrong code is `decrypt`.
- The ciphertext is the same deflated envelope as the link, under the file key and the file's own nonce. So
  `fileV2B64u` is reproducible byte for byte from `deflatedB64u`, `fileKeyV2B64u`, `fileNonceV2B64u` and
  `fileSaltB64u`.
- Writers use a fresh salt and nonce per file and the event's stable code; the vectors fix them (SHA-256 of
  `manotu-vector:<name>:file-code|file-salt|file-nonce`, the code from the first 40 bits).
- `argon2-kat.json` has `params` and `argon2Kat` entries `{code, password, saltHex, saltB64u, keyHex, keyB64u}`;
  `code` may be lower case or contain spaces, `password` is what goes into Argon2id. The last two entries are the
  kenduri and ride file keys.
- A suite-1 file opened with an 8-character code, or a suite-2 file opened with a 52-character code, fails with
  `invalid_secret_code`.

## Short links (§5.6)

The fixed short-link inputs are SHA-256 of `manotu-vector:kenduri-one-day:drop-id|drop-secret|drop-nonce`
(the dropId maps each of the first 10 bytes to the alphabet by `byte mod 58`). The blob is reproducible byte for
byte from `deflatedB64u`, `dropKeyB64u` and `dropNonceB64u`. Readers parse the three link forms (`dropLink`, also
on any site ending in `/j/`; `dropAppLink`, also `gimano://` and with other query parameters such as `stop`; the bare
`dropFragment`), and an `/a/` link (`#1.<key>.<data>`) is never a short link. Error codes: `malformed_drop_link` (dropId
not exactly 10 base58 characters, or the secret not exactly 16 bytes of strict b64u), `malformed_drop` (blob shorter
than 24 + 16 bytes), `drop_too_large` (blob over 256 KiB, no vector), then the link codes (`decrypt`, `signature`, …).
Writers seal only full events (`not_full_event` for a stops-only variant) and use a fresh nonce for every upload.

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
cross-checked with the vendored libsodium-wrappers and Node `zlib.inflateRawSync`. The suite-2 vectors and
`argon2-kat.json` were cross-checked with `libsodium-wrappers-sumo` 0.8.4 (libsodium 1.0.22, `crypto_pwhash`)
and Node's `crypto.argon2Sync`; the vendored `site/manotu/s/vendor/` build is not sumo and has no
`crypto_pwhash`.

## Reading rules worth testing on every platform

- Base64url decoding is strict: no padding, no non-canonical trailing bits.
- Ed25519 verification is strict (`verify_strict` / libsodium `crypto_sign_verify_detached`).
- Inflate output is bounded to 20 MiB; a larger output is an error, not a truncation.
- The suite-1 secret code decoder ignores `-` and whitespace, is case-insensitive, reads `I`/`L` as `1` and
  `O` as `0`, and rejects non-zero padding bits in the last character.
- The suite-2 code reader ignores `-` and whitespace, is case-insensitive, and **rejects** `0`, `O`, `1`, `I`
  and any other character outside its alphabet (no look-alike mapping).
- Text limits count Unicode scalar values (code points), not UTF-16 units or bytes.
- Unknown JSON fields are ignored; unknown `template` / stop `kind` / `info[].kind` values read as `other`.
- `info` (§4.1) is optional, at most 12 blocks of `{kind, text}`, text 1–1000 code points (blank is
  invalid, newlines allowed); writers put it after `sessions` and before `routes` and omit it when empty.
  Templates now include `meeting`, `seminar`, `picnic`, `hike`.
