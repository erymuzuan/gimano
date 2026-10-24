//! Regenerates `tests/vectors/*.json`: `cargo run -p event-core --example gen_vectors`.

#[path = "../tests/common/fixtures.rs"]
mod fixtures;

use std::path::PathBuf;

use event_core::{
    Envelope, Event, ImportCandidate, ImportDecision, LEGACY_SITE, OpenedPack, PackKey,
    ParentCheck, SealedPack, StoredEvent, StoredRevision, b64u, bytes_hash, decide_import,
    deflate_raw, encode_file, encrypt, link_fragment, open, open_link, revision_hash_b64u,
    safety_code, seal_parts, stops_only_variant,
};
use fixtures::{Material, material};
use serde_json::{Value, json};

const NOW: &str = "2026-11-01T03:00:00Z";
const NOW_EXPIRED: &str = "2026-10-24T00:00:00Z";

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn fragment_unchecked(key: &[u8; 32], sealed: &SealedPack) -> String {
    let mut data = sealed.nonce.to_vec();
    data.extend_from_slice(&sealed.ciphertext);
    format!("1.{}.{}", b64u(key), b64u(&data))
}

fn link_of(fragment: &str) -> String {
    format!("{LEGACY_SITE}/a/#{fragment}")
}

fn positive(name: &str, description: &str, event: &Event, now: &str) -> Value {
    positive_bytes(
        name,
        description,
        &material(name),
        &event.to_bytes().unwrap(),
        now,
    )
}

fn positive_bytes(name: &str, description: &str, m: &Material, bytes: &[u8], now: &str) -> Value {
    let event = Event::from_bytes(bytes).unwrap();
    let bytes = bytes.to_vec();
    let parts = seal_parts(&bytes, &m.organizer(), &m.pack_key(), m.nonce).unwrap();
    let key = m.pack_key();
    let fragment = link_fragment(&key, &parts.sealed).unwrap();
    let file = encode_file(&parts.sealed).unwrap();
    let opened = open(&key, &parts.sealed).unwrap();
    let decision = decide_import(&ImportCandidate::from(&opened), None, now.parse().unwrap());
    let points: usize = event
        .routes
        .iter()
        .filter_map(|r| r.polyline.as_deref())
        .map(|p| route_core::decode_polyline(p, 50_000).unwrap().len())
        .sum();
    json!({
        "name": name,
        "kind": "positive",
        "description": description,
        "site": LEGACY_SITE,
        "now": now,
        "ed25519SeedHex": hex(&m.seed),
        "ed25519SeedB64u": b64u(&m.seed),
        "organizerPubB64u": b64u(&opened.organizer_pub),
        "keyHex": hex(&m.key),
        "keyB64u": b64u(&m.key),
        "nonceHex": hex(&m.nonce),
        "nonceB64u": b64u(&m.nonce),
        "eventJson": String::from_utf8(bytes.clone()).unwrap(),
        "eventB64u": b64u(&bytes),
        "signatureB64u": b64u(&parts.signature),
        "envelopeJson": String::from_utf8(parts.envelope_bytes.clone()).unwrap(),
        "envelopeB64u": b64u(&parts.envelope_bytes),
        "deflatedB64u": b64u(&parts.deflated),
        "ciphertextB64u": b64u(&parts.sealed.ciphertext),
        "linkFragment": fragment,
        "link": link_of(&fragment),
        "fileB64u": b64u(&file),
        "secretCode": key.to_secret_code(),
        "safetyCode": safety_code(&opened.organizer_pub),
        "revisionHashB64u": revision_hash_b64u(&bytes),
        "expected": {
            "decision": match decision {
                event_core::ImportDecision::New { .. } => "new",
                _ => unreachable!("fixture must be importable"),
            },
            "id": event.id,
            "rev": event.rev,
            "title": event.title,
            "stops": event.stops.len(),
            "sessions": event.sessions.len(),
            "routes": event.routes.len(),
            "polylinePoints": points,
        },
    })
}

fn negative(name: &str, description: &str, expected: &str, fields: Value) -> Value {
    let mut v = json!({
        "name": name,
        "kind": "negative",
        "description": description,
        "expectedError": expected,
    });
    v.as_object_mut()
        .unwrap()
        .extend(fields.as_object().unwrap().clone());
    v
}

fn sealed_of(m: &Material, event: &Event) -> SealedPack {
    let bytes = event.to_bytes().unwrap();
    seal_parts(&bytes, &m.organizer(), &m.pack_key(), m.nonce)
        .unwrap()
        .sealed
}

fn main() {
    let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/vectors");
    std::fs::create_dir_all(&dir).unwrap();
    let write = |file: &str, v: &Value| {
        let mut s = serde_json::to_string_pretty(v).unwrap();
        s.push('\n');
        std::fs::write(dir.join(file), s).unwrap();
        println!("wrote tests/vectors/{file}");
    };

    let kenduri = fixtures::kenduri();
    write(
        "kenduri-one-day.json",
        &positive(
            "kenduri-one-day",
            "One-day kenduri: 3 stops (parking with vehicle entrance and arrival window, drop-off, venue), 2 sessions, no route.",
            &kenduri,
            NOW,
        ),
    );
    write(
        "ride-two-day.json",
        &positive(
            "ride-two-day",
            "Two-day bicycle ride: 5 stops, 2 sessions, 1 routed route (7-point precision-6 polyline, two legs, ascent/descent).",
            &fixtures::ride(),
            NOW,
        ),
    );

    let km = material("kenduri-one-day");
    let ks = sealed_of(&km, &kenduri);

    let mut tampered = ks.clone();
    tampered.ciphertext[10] ^= 0x01;
    write(
        "negative-tampered-ciphertext.json",
        &negative(
            "negative-tampered-ciphertext",
            "kenduri-one-day with bit 0 of ciphertext byte 10 flipped: authentication must fail.",
            "decrypt",
            json!({ "linkFragment": fragment_unchecked(&km.key, &tampered) }),
        ),
    );

    let bytes = kenduri.to_bytes().unwrap();
    let good = seal_parts(&bytes, &km.organizer(), &km.pack_key(), km.nonce).unwrap();
    let mut sig = good.signature;
    sig[0] ^= 0x01;
    let envelope = Envelope {
        v: 1,
        event: b64u(&bytes),
        organizer_pub: b64u(&km.organizer().public_key()),
        sig: b64u(&sig),
    };
    let env_bytes = serde_json::to_vec(&envelope).unwrap();
    let deflated = deflate_raw(&env_bytes);
    let bad_sig = SealedPack {
        nonce: km.nonce,
        ciphertext: encrypt(&km.pack_key(), &km.nonce, &deflated).unwrap(),
    };
    write(
        "negative-bad-signature.json",
        &negative(
            "negative-bad-signature",
            "kenduri-one-day envelope with bit 0 of signature byte 0 flipped, re-deflated and re-encrypted with the same key and nonce: decrypts, signature must fail.",
            "signature",
            json!({
                "envelopeJson": String::from_utf8(env_bytes).unwrap(),
                "deflatedB64u": b64u(&deflated),
                "linkFragment": fragment_unchecked(&km.key, &bad_sig),
            }),
        ),
    );

    let wrong = fixtures::fixed32("manotu-vector:wrong-key");
    write(
        "negative-wrong-key.json",
        &negative(
            "negative-wrong-key",
            "kenduri-one-day nonce and ciphertext with a different key, as a link and as a file + secret code.",
            "decrypt",
            json!({
                "linkFragment": fragment_unchecked(&wrong, &ks),
                "fileB64u": b64u(&encode_file(&ks).unwrap()),
                "secretCode": PackKey::from_bytes(wrong).to_secret_code(),
            }),
        ),
    );

    let em = material("kenduri-expired");
    let es = sealed_of(&em, &fixtures::kenduri_expired());
    write(
        "negative-expired.json",
        &negative(
            "negative-expired",
            "Valid, correctly signed kenduri whose expiresAt (2026-09-21T08:00:00Z) is before `now`: decrypts and verifies, import decision must be expired.",
            "expired",
            json!({
                "now": NOW_EXPIRED,
                "linkFragment": fragment_unchecked(&em.key, &es),
            }),
        ),
    );

    let lm = material("ride-long-route");
    let long_event = fixtures::ride_long_route();
    let ls = sealed_of(&lm, &long_event);
    let fragment = fragment_unchecked(&lm.key, &ls);
    assert!(fragment.len() > 16_000, "fixture must exceed the limit");
    write(
        "negative-oversized-fragment.json",
        &negative(
            "negative-oversized-fragment",
            "Ride with a 6000-point sketch route: the link fragment exceeds 16 000 characters and must be rejected before decoding. The same pack as a file with the secret code must open.",
            "fragment_too_long",
            json!({
                "now": NOW,
                "fragmentLength": fragment.len(),
                "linkFragment": fragment,
                "fileB64u": b64u(&encode_file(&ls).unwrap()),
                "secretCode": lm.pack_key().to_secret_code(),
                "fileEventId": long_event.id,
            }),
        ),
    );

    stops_only_vectors(&write);
}

/// Signs, deflates and encrypts arbitrary event bytes (also invalid ones, for negatives).
fn seal_raw(bytes: &[u8], m: &Material) -> SealedPack {
    use ed25519_dalek::Signer;
    let sk = ed25519_dalek::SigningKey::from_bytes(&m.seed);
    let mut msg = event_core::SIGNATURE_PREFIX.to_vec();
    msg.extend_from_slice(bytes);
    let envelope = Envelope {
        v: 1,
        event: b64u(bytes),
        organizer_pub: b64u(&sk.verifying_key().to_bytes()),
        sig: b64u(&sk.sign(&msg).to_bytes()),
    };
    let deflated = deflate_raw(&serde_json::to_vec(&envelope).unwrap());
    SealedPack {
        nonce: m.nonce,
        ciphertext: encrypt(&m.pack_key(), &m.nonce, &deflated).unwrap(),
    }
}

/// The ride-two-day organizer key and pack key with a nonce of its own.
fn ride_material(label: &str) -> Material {
    let ride = material("ride-two-day");
    Material {
        seed: ride.seed,
        key: ride.key,
        nonce: material(label).nonce,
    }
}

/// §5.4/§5.5/§7.4 vectors: the stops-only variant of ride-two-day, its negatives, and an import
/// sequence across variant, full and rev 2.
fn stops_only_vectors(write: &dyn Fn(&str, &Value)) {
    let full_bytes = fixtures::ride().to_bytes().unwrap();
    let full_hash = bytes_hash(&full_bytes);
    let var_bytes = stops_only_variant(&full_bytes).unwrap();
    let vm = ride_material("ride-two-day-stops-only");
    let mut v = positive_bytes(
        "ride-two-day-stops-only",
        "Stops-only variant (docs/events.md §5.5) of ride-two-day: the same rev with routes[0].polyline removed and `variant` appended, signed with the ride-two-day seed and encrypted with its key (own nonce). Its revision hash is variant.fullHash = SHA-256(ride-two-day eventJson).",
        &vm,
        &var_bytes,
        NOW,
    );
    let o = v.as_object_mut().unwrap();
    o.insert("fullVector".into(), json!("ride-two-day"));
    o.insert("fullHashB64u".into(), json!(b64u(&full_hash)));
    o.insert(
        "eventBytesHashB64u".into(),
        json!(b64u(&bytes_hash(&var_bytes))),
    );
    o["expected"]
        .as_object_mut()
        .unwrap()
        .insert("stopsOnly".into(), json!(true));
    assert_eq!(o["revisionHashB64u"], json!(b64u(&full_hash)));
    write("ride-two-day-stops-only.json", &v);

    // Negatives are edited as `Event`s so their JSON keeps the writer's key order.
    let neg = |name: &str, desc: &str, code: &str, bytes: Vec<u8>| {
        assert_eq!(
            Event::from_bytes(&bytes).unwrap_err().code(),
            code,
            "{name}"
        );
        let m = ride_material(name);
        let sealed = seal_raw(&bytes, &m);
        write(
            &format!("{name}.json"),
            &negative(
                name,
                desc,
                code,
                json!({
                    "now": NOW,
                    "eventJson": String::from_utf8(bytes).unwrap(),
                    "linkFragment": fragment_unchecked(&m.key, &sealed),
                }),
            ),
        );
    };
    let mut e = Event::from_bytes(&var_bytes).unwrap();
    e.routes[0].polyline = fixtures::ride().routes[0].polyline.clone();
    neg(
        "negative-variant-with-polyline",
        "ride-two-day-stops-only with routes[0].polyline put back, correctly signed: a stops-only variant must not carry a polyline.",
        "variant_has_polyline",
        serde_json::to_vec(&e).unwrap(),
    );
    let mut e = fixtures::ride();
    e.routes[0].polyline = None;
    neg(
        "negative-missing-polyline",
        "ride-two-day without routes[0].polyline and without `variant`, correctly signed: a full event needs a polyline on every route.",
        "missing_polyline",
        serde_json::to_vec(&e).unwrap(),
    );
    let mut e = Event::from_bytes(&var_bytes).unwrap();
    e.variant.as_mut().unwrap().kind = "thumbnail".into();
    neg(
        "negative-unknown-variant",
        "ride-two-day-stops-only with variant.kind \"thumbnail\", correctly signed: unknown variant kinds are rejected.",
        "unsupported_variant",
        serde_json::to_vec(&e).unwrap(),
    );

    // Rev 2 of the ride, drafted from the full rev 1, published as a full event.
    let mut r2 =
        fixtures::ride().next_revision(&full_bytes, "2026-11-12T08:00:00Z".parse().unwrap());
    r2.title = "Jelajah Basikal Dua Hari (dikemas kini)".into();
    let r2_bytes = r2.to_bytes().unwrap();
    let r2m = ride_material("ride-two-day-rev2");
    let r2_sealed = seal_parts(&r2_bytes, &r2m.organizer(), &r2m.pack_key(), r2m.nonce)
        .unwrap()
        .sealed;
    let r2_fragment = link_fragment(&r2m.pack_key(), &r2_sealed).unwrap();

    let ride_vec: Value = {
        let path =
            PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/vectors/ride-two-day.json");
        serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
    };
    let packs = json!({
        "full": {"vector": "ride-two-day", "linkFragment": ride_vec["linkFragment"],
                 "rev": 1, "stopsOnly": false, "revisionHashB64u": b64u(&full_hash)},
        "variant": {"vector": "ride-two-day-stops-only", "linkFragment": v["linkFragment"],
                    "rev": 1, "stopsOnly": true, "revisionHashB64u": b64u(&full_hash)},
        "rev2": {"linkFragment": r2_fragment, "eventJson": String::from_utf8(r2_bytes.clone()).unwrap(),
                 "rev": 2, "stopsOnly": false, "parent": b64u(&full_hash),
                 "revisionHashB64u": revision_hash_b64u(&r2_bytes)},
    });
    let now: chrono::DateTime<chrono::Utc> = NOW.parse().unwrap();
    let opened = |name: &str| -> OpenedPack {
        open_link(packs[name]["linkFragment"].as_str().unwrap()).unwrap()
    };
    let decision_name = |d: &ImportDecision| match d {
        ImportDecision::New { .. } => "new",
        ImportDecision::AlreadyImported => "already_imported",
        ImportDecision::UpgradeToFull { .. } => "upgrade_to_full",
        ImportDecision::Newer { .. } => "newer",
        ImportDecision::Older { .. } => "older",
        ImportDecision::Fork { .. } => "fork",
        ImportDecision::DifferentOrganizer => "different_organizer",
        ImportDecision::Expired => "expired",
    };
    // Runs a sequence against an empty store, applying each decision like an app would.
    let run = |steps: &[&str]| -> Value {
        let mut store: Option<StoredEvent> = None;
        let mut out = Vec::new();
        for name in steps {
            let o = opened(name);
            let d = decide_import(&ImportCandidate::from(&o), store.as_ref(), now);
            let parent = match &d {
                ImportDecision::Newer { parent, .. } => Some(match parent {
                    ParentCheck::Matches => "matches",
                    ParentCheck::MissedRevisions => "missed_revisions",
                    ParentCheck::Mismatch => "mismatch",
                }),
                _ => None,
            };
            let entry = StoredRevision {
                rev: o.event.rev,
                hash: o.revision_hash,
                stops_only: o.event.is_stops_only(),
            };
            match &d {
                ImportDecision::New { .. } => {
                    store = Some(StoredEvent {
                        organizer_pub: o.organizer_pub,
                        revisions: vec![entry],
                    });
                }
                ImportDecision::UpgradeToFull { rev } => {
                    let s = store.as_mut().unwrap();
                    for r in &mut s.revisions {
                        if r.rev == *rev && r.hash == o.revision_hash {
                            *r = entry;
                        }
                    }
                }
                ImportDecision::Newer { .. } | ImportDecision::Fork { .. } => {
                    store.as_mut().unwrap().revisions.push(entry);
                }
                _ => {}
            }
            let s = store.as_ref().unwrap();
            out.push(json!({
                "import": name,
                "decision": decision_name(&d),
                "parentCheck": parent,
                "storedAfter": s.revisions.iter().map(|r| json!({
                    "rev": r.rev, "revisionHashB64u": b64u(&r.hash), "stopsOnly": r.stops_only,
                })).collect::<Vec<_>>(),
            }));
        }
        Value::Array(out)
    };
    let sequences = json!([
        {"name": "variant then full", "steps": run(&["variant", "full"])},
        {"name": "full then variant", "steps": run(&["full", "variant"])},
        {"name": "variant then rev 2", "steps": run(&["variant", "rev2"])},
        {"name": "variant, full, variant", "steps": run(&["variant", "full", "variant"])},
    ]);
    write(
        "import-stops-only-sequence.json",
        &json!({
            "name": "import-stops-only-sequence",
            "kind": "import-sequence",
            "description": "docs/events.md §7.4: a stops-only variant and its full event are not a fork. Start each sequence with an empty store, import the packs in order at `now`, and apply each decision: new → store; upgrade_to_full → replace the stored variant of that rev with the full event, silently; newer/fork → add; already_imported/older → nothing. The parent check of rev 2 uses the stored variant's revision hash (its fullHash). Every pack opens with the ride-two-day key; rev2 is signed with the same seed.",
            "now": NOW,
            "packs": packs,
            "sequences": sequences,
        }),
    );
}
