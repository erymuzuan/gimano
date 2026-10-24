//! Regenerates `tests/vectors/*.json`: `cargo run -p event-core --example gen_vectors`.

#[path = "../tests/common/fixtures.rs"]
mod fixtures;

use std::path::PathBuf;

use event_core::{
    DEFAULT_SITE, Envelope, Event, ImportCandidate, PackKey, SealedPack, b64u, decide_import,
    deflate_raw, encode_file, encrypt, link_fragment, open, revision_hash_b64u, safety_code,
    seal_parts,
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
    format!("{DEFAULT_SITE}/a/#{fragment}")
}

fn positive(name: &str, description: &str, event: &Event, now: &str) -> Value {
    let m = material(name);
    let bytes = event.to_bytes().unwrap();
    let parts = seal_parts(&bytes, &m.organizer(), &m.pack_key(), m.nonce).unwrap();
    let key = m.pack_key();
    let fragment = link_fragment(&key, &parts.sealed).unwrap();
    let file = encode_file(&parts.sealed).unwrap();
    let opened = open(&key, &parts.sealed).unwrap();
    let decision = decide_import(&ImportCandidate::from(&opened), None, now.parse().unwrap());
    let points: usize = event
        .routes
        .iter()
        .map(|r| {
            route_core::decode_polyline(&r.polyline, 50_000)
                .unwrap()
                .len()
        })
        .sum();
    json!({
        "name": name,
        "kind": "positive",
        "description": description,
        "site": DEFAULT_SITE,
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
}
