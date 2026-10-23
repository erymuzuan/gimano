# Contributor identity, reputation and vetting

**Principle:** Gimano never knows *who* you are. Your public contributions are credited to a random
**pseudonym** that only your phone, and your own backup, can sign for. Reputation describes
your public work, never you. (Design boards: canvas page "Vetting (idea)".)

## 1. Identity: a pseudonym plus an Ed25519 key on the phone

- First launch: the phone generates a random 32-byte **seed**, derives an **Ed25519 key pair**
  (libsodium `crypto_sign_seed_keypair`), and registers the **public key** with `POST /auth/register`.
  The server returns a random `userId`, an auto-generated display name ("Rusa 2368") and a JWT.
- The seed lives in the app's private storage, encrypted with a key from Android Keystore /
  iOS Keychain. It is included in the user's **backup** (`identity` table, see
  [privacy-and-backup.md](privacy-and-backup.md)), so restoring on a new phone restores the pseudonym
  and its reputation. A passphrase-protected backup encrypts it; without a passphrase it sits in the
  user's *own* Drive/iCloud/file, and the app says so.
- **Recovery code:** an optional 24-word code (BIP-39 English wordlist encoding of the 32-byte seed
  + checksum) shown once in Settings → Account, for users who don't back up.
- **No** email, phone, name, contacts, Google/Apple login or device identifier is ever sent or stored.
- Legacy `POST /auth/device` accounts are upgraded on first launch of the new app: the app
  generates a key and calls `POST /me/keys` (authenticated with the old JWT) to attach it.

## 2. Signed contributions

Contribution endpoints require a request signature in addition to the JWT:

```
X-Gimano-Ts:  <unix seconds>                      (±300 s of server time)
X-Gimano-Sig: base64(ed25519_sign(seed, "GIMANO1\n" + METHOD + "\n" + PATH_AND_QUERY + "\n" + ts + "\n" + hex(sha256(body))))
```

The server verifies the signature against the user's current public key and keeps a 10-minute replay cache of
`(userId, ts, sig)`.

Signed endpoints: `POST /places`, `PATCH /places/{id}`, `POST /places/{id}/verify`,
`POST /places/{id}/suggest-edit`, `POST /places/{id}/reviews`, `PATCH|DELETE /reviews/{id}`,
`POST /photos`, `POST /reports`, `POST /reports/{id}/votes`.

## 3. Reputation ledger (server, per pseudonym)

| Kept | Never kept |
|---|---|
| userId, displayName, publicKey(s), createdAt, lastActiveDay | name, email, phone, device id |
| tier, points | favourites, recents, private places, trips |
| placesAccepted, placesRejected, placesDisputedUpheld | location history (including where you voted) |
| verifierVotes, verifierAgreed (votes that matched the final outcome) | |
| the public places, reviews, photos and reports themselves | |

`accuracy = (placesAccepted + verifierAgreed) / (placesAccepted + placesRejected + verifierVotes)`,
shown as a percentage. It is `null` until there are 5 or more counted items.

### Tiers

| Tier | Requirement | Effect |
|---|---|---|
| **baharu** (new) | default | places need weighted **3** confirmations; own votes weigh 1 (0 while the account is under 7 days); max 5 public places/day |
| **perak** (silver) | placesAccepted ≥ 10 and accuracy ≥ 90 % | places need weighted **2**; votes weigh 1.5 |
| **emas** (gold) | placesAccepted ≥ 50, accuracy ≥ 95 %, account ≥ 90 days, no upheld dispute in 90 days | places publish **immediately** (badge "Emas"); 10 % are randomly also sent for local audit; votes weigh 2 |

Tiers are recomputed nightly and on each resolution. **Demotion:** 2 upheld disputes within 90 days remove
Emas (back to Perak); repeated spam (3 rejected places in 7 days) suspends contributions for 7 days.

## 4. Vetting a public place

```
POST /places (signed)          → status pending (or published if emas)
                                  automated checks unchanged (duplicates 100 m, profanity, water, rate limits)
GET  /places/pending-nearby?cell=<geohash6>
                               → pending places in that cell and its 8 neighbours (excludes the caller's own places)
POST /places/{id}/verify (signed)
  { "answer": "yes"|"no"|"unsure",
    "nameCorrect": true|false|null, "locationCorrect": true|false|null,
    "proximity": { "cell": "<geohash6>", "withinM": 1000 },   // computed ON THE PHONE
    "attestation": { "platform": "android"|"ios", "token": "<Play Integrity token | App Attest assertion>" } }
  → 200 { status, confirmationsWeighted, required }
```

- **Proximity:** the phone compares its own GPS fix with the place and sends only `withinM`
  (1000 in towns, 5000 where the place's `rural` flag is set) and a geohash-6 cell (~1.2 × 0.6 km). The server
  checks the cell matches the place's cell or one of its neighbours, then discards it. It is never stored.
- **Eligibility:** not the contributor; one vote per pseudonym per place; ≤ 20 votes/day; attestation verdict
  OK (when `Attestation:Mode=enforce`).
- **Weights** per the tier table. `unsure` counts 0 but is recorded for UX.
- **Resolution:** weighted yes ≥ required → **published**; weighted no ≥ 3 → **hidden** (moderator queue);
  30 days without resolving → **expired** (contributor notified via Inbox).
- **Unlinking after resolution:** pending votes are stored as `(placeId, voterId, answer, weight)` only to
  prevent double votes. When the place resolves, each row becomes
  `(placeId, hmac_sha256(perPlaceSalt, voterId), answer, weight, day)`, the per-place salt is deleted after
  30 days, and the voter's `verifierVotes` / `verifierAgreed` counters are updated. Verifier identities are never
  returned by any API.
- **Gold audit:** for an audited Emas place, "no" votes reaching 3 → hidden plus a dispute against the
  contributor, upheld by a moderator.

## 5. Device attestation

`Attestation:Mode = off | log | enforce` (dev: `log`, prod: `log` at first, then `enforce`).

- Android: Play Integrity standard request. The verdict is decoded server-side via Google's
  `decodeIntegrityToken` (service account in config). We require `MEETS_DEVICE_INTEGRITY` and the app's
  package/cert match.
- iOS: App Attest. The key is attested once (`POST /me/attest`), then each vote carries an assertion.
- We store only the boolean verdict with the pending vote, never device identifiers. Requests per attested
  key are rate-limited in memory.

## 6. Account deletion

`DELETE /me` deletes the pseudonym's public keys, counters and tier. Places, reviews and photos stay, credited
to "Pemandu Gimano". Pending votes by the user are removed; resolved votes are already unlinkable.

## 7. Implementation notes (server)

- **Problem types** (401 `application/problem+json`, `type` = `https://gimano.bukitbunga.com/problems/<code>`):
  `signature-missing`, `signature-expired` (outside ±300 s), `signature-invalid`, `signature-replayed`,
  `no-key` (legacy account without a key → `POST /me/keys`), `auth-failed` (`POST /auth/token`), `rotation-unsigned`.
- `Identity:RequireSignatures` defaults to **true in Production** and false elsewhere (dev turns it on once both apps ship).
- **PATH_AND_QUERY** is the request target exactly as sent on the request line (`/api/v1/...` plus `?query` if any,
  percent-encoding untouched). Body = the exact bytes sent (multipart included); an empty body hashes to `e3b0c442…b855`.
- **Key rotation:** `sigByOldKey = Ed25519(currentKey, "GIMANO-KEY\n" + userId + "\n" + newPublicKeyBase64)`, where the base64 is
  exactly the string sent as `publicKey`. Legacy device accounts without a key attach one with the JWT alone.
- **Attestation client data** (both platforms): `"GIMANO-ATTEST\n" + placeId + "\n" + ts`, where `ts` = the request's
  `X-Gimano-Ts`. Android: pass `hex(sha256(clientData))` as the Play Integrity **requestHash**. iOS: get a challenge
  (`GET /me/attest/challenge`), attest once (`POST /me/attest {keyId, attestation}`), and send each vote's
  `attestation.token` as `base64(keyId) + "." + base64(assertion)`, with `clientDataHash = sha256(clientData)`.
- Verdicts are stored only as a boolean on the pending vote. `Attestation:Mode=log` records the verdict without blocking.
- `POST /places/{id}/verify` still accepts the old `{exists, correctLocation}` body from pre-vetting apps (no proximity). New apps
  must send `answer` + `proximity`. Errors: 403 when the voter is the contributor, too far away, or the device check fails
  in enforce mode; 409 when the voter already voted or the place is closed; 429 for more than 20 votes/day.
- Places that expire after 30 days become `status: "expired"` (Inbox kind `place_expired`).

## 8. Test vectors

Both apps must reproduce these exactly (Ed25519 is deterministic). The server's unit tests
(`SignatureVectorTests`, `GeohashTests`) check the same values. `\n` means a single LF byte (0x0A).

| | value |
|---|---|
| seed (hex) | `000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f` |
| public key (hex) | `03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8` |
| public key (base64) | `A6EHv/POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg=` |

**Request 1:** `POST /api/v1/places/c01a166b7f9c67924976aff1beb56c2cd/verify`, `X-Gimano-Ts: 1792713600`, body (UTF-8, exactly):
`{"answer":"yes","nameCorrect":true,"locationCorrect":true,"proximity":{"cell":"w283f8","withinM":420}}`

- sha256(body) = `6c091ee7b6a05d273cedfd0ada1a4a1c113a2ef99a1ed14c35f8d87f36019821`
- canonical = `GIMANO1\nPOST\n/api/v1/places/c01a166b7f9c67924976aff1beb56c2cd/verify\n1792713600\n6c091ee7b6a05d273cedfd0ada1a4a1c113a2ef99a1ed14c35f8d87f36019821`
- X-Gimano-Sig = `CPm5nQ7WU1z/tiBHSciXop7u2uc/M6Rt4/AgExSarydCkuW1iUQ99XT+gFyonROf7816WI+u2h901BueYupVAQ==`

**Request 2 (empty body):** `DELETE /api/v1/reviews/01a16676-ce9e-78db-842e-55ffa03ef190`, ts `1792713600`

- sha256(body) = `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- X-Gimano-Sig = `62H79zlWTyZDvgEzOFxiHLBA29cFklC0eto7hkBYoWdsgHaoXp54+ygVAvS7hyS/38WD2XYwOK+XQr6RE4B+Cw==`

**Request 3 (query string):** `POST /api/v1/reports?source=app`, ts `1792713601`, body
`{"type":"hazard","subtype":"pothole","lat":3.118,"lng":101.619,"heading":90}`

- sha256(body) = `e7880fafa70c2dcc331adee63b54a5c4362d39a02d0ec08341d7ac06b9713980`
- X-Gimano-Sig = `CVlQVQXZSUEbSZDJ3pEkhmeAKHgPbZh2MyAkEwcTXo9C5d+drAlcNC2X7zdrBBXfSAJN9jq3VQo+ukoTiRLJDw==`

**Signature login:** userId `01a16676-0000-7000-8000-000000000001`, ts `1792713600`

- canonical = `GIMANO-AUTH\n01a16676-0000-7000-8000-000000000001\n1792713600`
- sig = `aApOheYmDSVKSLICJI8tuRW9Cp06hzkHJQKwdLab1N5sYCPAwvKFum8s+fnw6+6QsktcXji/+OLZzEQqijW+Cw==`

**Key rotation:** new seed `202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f` → new public key
`Kay64UG8yvCyLhqU000LxzYeUm0L/hLIl5S8kyKWbdc=`

- canonical = `GIMANO-KEY\n01a16676-0000-7000-8000-000000000001\nKay64UG8yvCyLhqU000LxzYeUm0L/hLIl5S8kyKWbdc=`
- sigByOldKey (signed with the first seed) = `y+8fzyom8LvtpxVQGE3Hebps7dovTSlGQYQhrgqO8hAekGT4pX0PXw282mqNZc8vMCOtLmmwfDbgVqlBKYsBDg==`

**Attestation client data:** `GIMANO-ATTEST\nc01a166b7f9c67924976aff1beb56c2cd\n1792713600` → sha256 hex
`ff664bac6820f2ce7bb5823ae9a2b33b103e47bca5bb6aa4ec11af46a9aa78be`

**Geohash-6 cells:** (3.1180, 101.6190) → `w2838r` · (3.1579, 101.7116) → `w283fw` · (1.3521, 103.8198) → `w21zdq` ·
(4.9031, 114.9398) → `w8cezs`
