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
