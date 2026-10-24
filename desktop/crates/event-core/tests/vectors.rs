//! Decodes the committed vectors in `tests/vectors/` (the cross-platform contract) and checks that
//! this implementation's writer reproduces every deterministic layer.

mod common;

use std::path::PathBuf;

use common::fixtures;
use event_core::{
    Envelope, Error, Event, FileSuite, ImportCandidate, ImportDecision, OrganizerKey, PackKey,
    ParentCheck, SealedPack, StoredEvent, StoredRevision, b64u, b64u_decode, bytes_hash,
    decide_import, decrypt, derive_file_key, encode_file, encode_link, encrypt, inflate_raw, open,
    open_file, open_link, parse_file, parse_link, parse_secret_code_v2, revision_hash_b64u,
    safety_code, seal_file_parts, seal_parts, stops_only_variant,
};
use serde_json::Value;

fn load(name: &str) -> Value {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/vectors")
        .join(name);
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

fn s<'a>(v: &'a Value, k: &str) -> &'a str {
    v[k].as_str().unwrap_or_else(|| panic!("missing {k}"))
}

fn bytes(v: &Value, k: &str) -> Vec<u8> {
    b64u_decode(s(v, k)).unwrap_or_else(|| panic!("bad b64u in {k}"))
}

fn arr<const N: usize>(v: &Value, k: &str) -> [u8; N] {
    bytes(v, k).try_into().unwrap()
}

fn check_positive(name: &str, fixture: Event) {
    let v = load(&format!("{name}.json"));
    let key = PackKey::from_bytes(arr(&v, "keyB64u"));
    let nonce: [u8; 24] = arr(&v, "nonceB64u");
    let event_bytes = bytes(&v, "eventB64u");
    assert_eq!(event_bytes, s(&v, "eventJson").as_bytes());

    // Reader direction: link, fragment and file all open to the exact signed bytes.
    let opened = open_link(s(&v, "link")).unwrap();
    assert_eq!(opened.event_bytes, event_bytes);
    assert_eq!(open_link(s(&v, "linkFragment")).unwrap(), opened);
    assert_eq!(
        open_file(&bytes(&v, "fileB64u"), s(&v, "secretCode")).unwrap(),
        opened
    );
    assert_eq!(opened.event, fixture, "decoded event equals the fixture");
    assert_eq!(opened.event, Event::from_bytes(&event_bytes).unwrap());
    assert_eq!(b64u(&opened.organizer_pub), s(&v, "organizerPubB64u"));
    assert_eq!(opened.safety_code(), s(&v, "safetyCode"));
    assert_eq!(b64u(&opened.revision_hash), s(&v, "revisionHashB64u"));
    assert_eq!(revision_hash_b64u(&event_bytes), s(&v, "revisionHashB64u"));
    // `expected.stopsOnly` is absent (false) on full-event vectors.
    assert_eq!(
        opened.event.is_stops_only(),
        v["expected"]["stopsOnly"].as_bool().unwrap_or(false)
    );

    let exp = &v["expected"];
    assert_eq!(opened.event.id, exp["id"].as_str().unwrap());
    assert_eq!(u64::from(opened.event.rev), exp["rev"].as_u64().unwrap());
    assert_eq!(opened.event.title, exp["title"].as_str().unwrap());
    assert_eq!(
        opened.event.stops.len() as u64,
        exp["stops"].as_u64().unwrap()
    );
    assert_eq!(
        opened.event.routes.len() as u64,
        exp["routes"].as_u64().unwrap()
    );
    let now = s(&v, "now").parse().unwrap();
    match decide_import(&ImportCandidate::from(&opened), None, now) {
        ImportDecision::New { safety_code: c } => assert_eq!(c, s(&v, "safetyCode")),
        d => panic!("{d:?}"),
    }

    // Every layer, step by step.
    let ciphertext = bytes(&v, "ciphertextB64u");
    let deflated = bytes(&v, "deflatedB64u");
    assert_eq!(decrypt(&key, &nonce, &ciphertext).unwrap(), deflated);
    let envelope_bytes = inflate_raw(&deflated, 1 << 20).unwrap();
    assert_eq!(envelope_bytes, bytes(&v, "envelopeB64u"));
    assert_eq!(envelope_bytes, s(&v, "envelopeJson").as_bytes());
    let env: Envelope = serde_json::from_slice(&envelope_bytes).unwrap();
    assert_eq!(env.sig, s(&v, "signatureB64u"));
    assert_eq!(PackKey::from_secret_code(s(&v, "secretCode")).unwrap(), key);
    assert_eq!(safety_code(&opened.organizer_pub), s(&v, "safetyCode"));

    // Writer direction. Ed25519 and the AEAD are deterministic: identical bytes are required.
    let organizer = OrganizerKey::from_seed(&arr(&v, "ed25519SeedB64u"));
    let parts = seal_parts(&fixture.to_bytes().unwrap(), &organizer, &key, nonce).unwrap();
    assert_eq!(parts.event_bytes, event_bytes);
    assert_eq!(b64u(&parts.signature), s(&v, "signatureB64u"));
    assert_eq!(parts.envelope_bytes, envelope_bytes);
    assert_eq!(encrypt(&key, &nonce, &deflated).unwrap(), ciphertext);
    // DEFLATE output may differ between libraries; it only has to round-trip.
    assert_eq!(
        inflate_raw(&parts.deflated, 1 << 20).unwrap(),
        envelope_bytes
    );
    let sealed = SealedPack {
        nonce,
        ciphertext: ciphertext.clone(),
    };
    assert_eq!(
        encode_link(s(&v, "site"), &key, &sealed).unwrap(),
        s(&v, "link")
    );
    // Suite-1 framing (read only now): MNTE 01 01 nonce ciphertext.
    let file_v1 = bytes(&v, "fileB64u");
    assert_eq!(&file_v1[..6], b"MNTE\x01\x01");
    assert_eq!(parse_file(&file_v1).unwrap().sealed, sealed);
    assert_eq!(open(&key, &parts.sealed).unwrap(), opened);

    if v.get("fileV2B64u").is_some() {
        check_file_v2(&v, &fixture, &organizer, &opened);
    }
}

/// Suite-2 file (§5.2): Argon2id key from the 8-character code and salt, own nonce.
fn check_file_v2(
    v: &Value,
    fixture: &Event,
    organizer: &OrganizerKey,
    opened: &event_core::OpenedPack,
) {
    let file = bytes(v, "fileV2B64u");
    let code = s(v, "secretCodeV2");
    let salt: [u8; 16] = arr(v, "fileSaltB64u");
    let nonce: [u8; 24] = arr(v, "fileNonceV2B64u");
    assert_eq!(code.len(), 9);
    assert_eq!(&file[..6], b"MNTE\x01\x02");
    assert_eq!(file[6..22], salt);
    assert_eq!(file[22..46], nonce);

    // Reader direction.
    assert_eq!(open_file(&file, code).unwrap(), *opened);
    assert_eq!(
        open_file(&file, &code.to_lowercase().replace('-', " ")).unwrap(),
        *opened
    );
    let key = derive_file_key(code, &salt).unwrap();
    assert_eq!(b64u(key.as_bytes()), s(v, "fileKeyV2B64u"));
    let parsed = parse_file(&file).unwrap();
    assert_eq!(parsed.suite, FileSuite::Code { salt });
    // The same deflated envelope as the link, under the file key.
    assert_eq!(
        decrypt(&key, &nonce, &parsed.sealed.ciphertext).unwrap(),
        bytes(v, "deflatedB64u")
    );
    // The file key is independent of the link key.
    assert_ne!(s(v, "fileKeyV2B64u"), s(v, "keyB64u"));

    // Writer direction: identical bytes with the vector's code, salt and nonce.
    let ct = encrypt(&key, &nonce, &bytes(v, "deflatedB64u")).unwrap();
    assert_eq!(ct, parsed.sealed.ciphertext);
    assert_eq!(
        encode_file(
            &salt,
            &SealedPack {
                nonce,
                ciphertext: ct
            }
        )
        .unwrap(),
        file
    );
    let (parts, _) =
        seal_file_parts(&fixture.to_bytes().unwrap(), organizer, code, salt, nonce).unwrap();
    assert_eq!(
        inflate_raw(&parts.deflated, 1 << 20).unwrap(),
        bytes(v, "envelopeB64u")
    );
}

#[test]
fn kenduri_one_day() {
    check_positive("kenduri-one-day", fixtures::kenduri());
}

#[test]
fn ride_two_day() {
    let e = fixtures::ride();
    assert_eq!(e.stops.len(), 5);
    assert_eq!(e.routes[0].legs.len(), 2);
    check_positive("ride-two-day", e);
}

fn negative(name: &str) -> Value {
    let v = load(&format!("{name}.json"));
    assert_eq!(s(&v, "kind"), "negative");
    v
}

#[test]
fn negative_tampered_ciphertext() {
    let v = negative("negative-tampered-ciphertext");
    let err = open_link(s(&v, "linkFragment")).unwrap_err();
    assert_eq!(err, Error::Decrypt);
    assert_eq!(err.code(), s(&v, "expectedError"));
}

#[test]
fn negative_bad_signature() {
    let v = negative("negative-bad-signature");
    let (key, sealed) = parse_link(s(&v, "linkFragment")).unwrap();
    assert_eq!(
        decrypt(&key, &sealed.nonce, &sealed.ciphertext).unwrap(),
        bytes(&v, "deflatedB64u")
    );
    let err = open(&key, &sealed).unwrap_err();
    assert_eq!(err, Error::BadSignature);
    assert_eq!(err.code(), s(&v, "expectedError"));
}

#[test]
fn negative_wrong_key() {
    let v = negative("negative-wrong-key");
    let err = open_link(s(&v, "linkFragment")).unwrap_err();
    assert_eq!(err.code(), s(&v, "expectedError"));
    let err = open_file(&bytes(&v, "fileB64u"), s(&v, "secretCode")).unwrap_err();
    assert_eq!(err.code(), s(&v, "expectedError"));
}

#[test]
fn negative_wrong_secret_code() {
    let v = negative("negative-wrong-secret-code");
    let kenduri = load("kenduri-one-day.json");
    assert_eq!(s(&v, "fileB64u"), s(&kenduri, "fileV2B64u"));
    assert_ne!(s(&v, "secretCode"), s(&kenduri, "secretCodeV2"));
    let err = open_file(&bytes(&v, "fileB64u"), s(&v, "secretCode")).unwrap_err();
    assert_eq!(err, Error::Decrypt);
    assert_eq!(err.code(), s(&v, "expectedError"));
}

#[test]
fn negative_invalid_secret_code() {
    let v = negative("negative-invalid-secret-code");
    let file = bytes(&v, "fileB64u");
    let codes = v["secretCodes"].as_array().unwrap();
    assert!(!codes.is_empty());
    for c in codes {
        let c = c.as_str().unwrap();
        assert!(c.contains(['O', 'o', '0', 'I', 'i', '1']), "{c}");
        let err = open_file(&file, c).unwrap_err();
        assert!(matches!(err, Error::InvalidSecretCode(_)), "{c}");
        assert_eq!(err.code(), s(&v, "expectedError"));
    }
}

#[test]
fn argon2_known_answers() {
    let v = load("argon2-kat.json");
    assert_eq!(s(&v, "kind"), "kat");
    let p = &v["params"];
    assert_eq!(p["opslimit"].as_u64(), Some(3));
    assert_eq!(p["memlimitBytes"].as_u64(), Some(64 * 1024 * 1024));
    let kat = v["argon2Kat"].as_array().unwrap();
    assert!(kat.len() >= 5);
    for k in kat {
        let code = s(k, "code");
        assert_eq!(
            parse_secret_code_v2(code).unwrap().as_str(),
            s(k, "password")
        );
        let salt: [u8; 16] = arr(k, "saltB64u");
        let key = derive_file_key(code, &salt).unwrap();
        assert_eq!(b64u(key.as_bytes()), s(k, "keyB64u"), "{code}");
    }
}

#[test]
fn negative_expired() {
    let v = negative("negative-expired");
    let opened = open_link(s(&v, "linkFragment")).unwrap();
    assert_eq!(opened.event, fixtures::kenduri_expired());
    let now = s(&v, "now").parse().unwrap();
    assert_eq!(
        decide_import(&ImportCandidate::from(&opened), None, now),
        ImportDecision::Expired
    );
    assert_eq!(s(&v, "expectedError"), "expired");
}

#[test]
fn negative_oversized_fragment() {
    let v = negative("negative-oversized-fragment");
    let fragment = s(&v, "linkFragment");
    assert!(fragment.len() > 16_000);
    let err = parse_link(fragment).unwrap_err();
    assert_eq!(err.code(), s(&v, "expectedError"));
    let err = parse_link(&format!("https://example.test/a/#{fragment}")).unwrap_err();
    assert!(matches!(err, Error::FragmentTooLong { max: 16_000, .. }));

    // The same pack travels fine as a file.
    let file = bytes(&v, "fileB64u");
    let opened = open_file(&file, s(&v, "secretCode")).unwrap();
    assert_eq!(opened.event.id, s(&v, "fileEventId"));
    assert_eq!(opened.event, fixtures::ride_long_route());
    // And the writer refuses to build that link.
    let key = PackKey::from_secret_code(s(&v, "secretCode")).unwrap();
    let err = encode_link("https://x.test", &key, &parse_file(&file).unwrap().sealed).unwrap_err();
    assert!(matches!(err, Error::FragmentTooLong { .. }));
}

// ---- §5.5 / §7.4 stops-only variant ----

#[test]
fn ride_two_day_stops_only() {
    let full = load("ride-two-day.json");
    let v = load("ride-two-day-stops-only.json");
    let full_bytes = bytes(&full, "eventB64u");
    let var_bytes = stops_only_variant(&full_bytes).unwrap();
    // The reference writer's variant is deterministic from the full bytes.
    assert_eq!(var_bytes, s(&v, "eventJson").as_bytes());
    assert_eq!(s(&v, "fullVector"), "ride-two-day");
    assert_eq!(s(&v, "fullHashB64u"), b64u(&bytes_hash(&full_bytes)));
    assert_eq!(s(&v, "revisionHashB64u"), s(&full, "revisionHashB64u"));
    assert_eq!(s(&v, "eventBytesHashB64u"), b64u(&bytes_hash(&var_bytes)));
    // Same organizer seed and pack key as the full vector.
    assert_eq!(s(&v, "ed25519SeedB64u"), s(&full, "ed25519SeedB64u"));
    assert_eq!(s(&v, "keyB64u"), s(&full, "keyB64u"));
    assert_eq!(v["expected"]["stopsOnly"], Value::Bool(true));
    assert_eq!(v["expected"]["polylinePoints"].as_u64(), Some(0));

    check_positive(
        "ride-two-day-stops-only",
        Event::from_bytes(&var_bytes).unwrap(),
    );
    let opened = open_link(s(&v, "link")).unwrap();
    assert!(opened.event.routes.iter().all(|r| r.polyline.is_none()));
    assert_eq!(opened.event.routes[0].distance_m, 20_100);
}

#[test]
fn negative_variant_rules() {
    for (name, code) in [
        ("negative-variant-with-polyline", "variant_has_polyline"),
        ("negative-missing-polyline", "missing_polyline"),
        ("negative-unknown-variant", "unsupported_variant"),
    ] {
        let v = negative(name);
        assert_eq!(s(&v, "expectedError"), code);
        let err = open_link(s(&v, "linkFragment")).unwrap_err();
        assert_eq!(err.code(), code, "{name}");
        let err = Event::from_bytes(s(&v, "eventJson").as_bytes()).unwrap_err();
        assert_eq!(err.code(), code, "{name}");
    }
}

#[test]
fn import_stops_only_sequence() {
    let v = load("import-stops-only-sequence.json");
    assert_eq!(s(&v, "kind"), "import-sequence");
    let now = s(&v, "now").parse().unwrap();
    let packs = &v["packs"];
    for seq in v["sequences"].as_array().unwrap() {
        let mut store: Option<StoredEvent> = None;
        for step in seq["steps"].as_array().unwrap() {
            let name = s(step, "import");
            let o = open_link(s(&packs[name], "linkFragment")).unwrap();
            assert_eq!(b64u(&o.revision_hash), s(&packs[name], "revisionHashB64u"));
            assert_eq!(
                Some(o.event.is_stops_only()),
                packs[name]["stopsOnly"].as_bool()
            );
            let d = decide_import(&ImportCandidate::from(&o), store.as_ref(), now);
            let entry = StoredRevision {
                rev: o.event.rev,
                hash: o.revision_hash,
                stops_only: o.event.is_stops_only(),
            };
            let (got, parent) = match &d {
                ImportDecision::New { .. } => {
                    store = Some(StoredEvent {
                        organizer_pub: o.organizer_pub,
                        revisions: vec![entry],
                    });
                    ("new", None)
                }
                ImportDecision::UpgradeToFull { rev } => {
                    for r in &mut store.as_mut().unwrap().revisions {
                        if r.rev == *rev && r.hash == o.revision_hash {
                            *r = entry;
                        }
                    }
                    ("upgrade_to_full", None)
                }
                ImportDecision::Newer { parent, .. } => {
                    store.as_mut().unwrap().revisions.push(entry);
                    let p = match parent {
                        ParentCheck::Matches => "matches",
                        ParentCheck::MissedRevisions => "missed_revisions",
                        ParentCheck::Mismatch => "mismatch",
                    };
                    ("newer", Some(p))
                }
                ImportDecision::AlreadyImported => ("already_imported", None),
                d => panic!("unexpected {d:?}"),
            };
            let what = format!("{} / {name}", s(seq, "name"));
            assert_eq!(got, s(step, "decision"), "{what}");
            assert_eq!(parent, step["parentCheck"].as_str(), "{what}");
            let after: Vec<_> = store
                .as_ref()
                .unwrap()
                .revisions
                .iter()
                .map(|r| (u64::from(r.rev), b64u(&r.hash), r.stops_only))
                .collect();
            let want: Vec<_> = step["storedAfter"]
                .as_array()
                .unwrap()
                .iter()
                .map(|r| {
                    (
                        r["rev"].as_u64().unwrap(),
                        s(r, "revisionHashB64u").to_string(),
                        r["stopsOnly"].as_bool().unwrap(),
                    )
                })
                .collect();
            assert_eq!(after, want, "{what}");
        }
    }
    // Rev 2's parent is the full rev 1's hash, which is also the variant's fullHash.
    let r2 = Event::from_bytes(s(&packs["rev2"], "eventJson").as_bytes()).unwrap();
    assert_eq!(
        r2.parent.as_deref(),
        Some(s(&packs["variant"], "revisionHashB64u"))
    );
}
