//! Decodes the committed vectors in `tests/vectors/` (the cross-platform contract) and checks that
//! this implementation's writer reproduces every deterministic layer.

mod common;

use std::path::PathBuf;

use common::fixtures;
use event_core::{
    Envelope, Error, Event, ImportCandidate, ImportDecision, OrganizerKey, PackKey, SealedPack,
    b64u, b64u_decode, decide_import, decrypt, encode_file, encode_link, encrypt, inflate_raw,
    open, open_file, open_link, parse_file, parse_link, revision_hash_b64u, safety_code,
    seal_parts,
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
    assert_eq!(encode_file(&sealed).unwrap(), bytes(&v, "fileB64u"));
    assert_eq!(open(&key, &parts.sealed).unwrap(), opened);
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
    let err = encode_link("https://x.test", &key, &parse_file(&file).unwrap()).unwrap_err();
    assert!(matches!(err, Error::FragmentTooLong { .. }));
}
