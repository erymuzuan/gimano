//! Regenerates `tests/vectors/*.json`: `cargo run -p event-core --example gen_vectors`.

#[path = "../tests/common/fixtures.rs"]
mod fixtures;

use std::path::PathBuf;

use event_core::{
    DEFAULT_SITE, DROP_ID_ALPHABET, DropSecret, drop_link, open_drop, parse_drop_link,
    seal_drop_parts,
};
use event_core::{
    Envelope, Event, FILE_KDF_MEMLIMIT_KIB, FILE_KDF_OPSLIMIT, ImportCandidate, ImportDecision,
    LEGACY_SITE, OpenedPack, PackKey, ParentCheck, SealedPack, StoredEvent, StoredRevision, b64u,
    bytes_hash, decide_import, deflate_raw, derive_file_key, encode_file, encode_open_file,
    encrypt, file_needs_code, link_fragment, open, open_file, open_file_without_code, open_link,
    parse_secret_code_v2, revision_hash_b64u, safety_code, seal_parts, stops_only_variant,
};
use fixtures::{FileMaterial, Material, file_material, material};
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

/// Suite-1 framing (`MNTE 01 01 nonce ciphertext`): no longer written by the core, kept so the
/// vectors still test readers on older files.
fn file_v1(sealed: &SealedPack) -> Vec<u8> {
    let mut out = b"MNTE\x01\x01".to_vec();
    out.extend_from_slice(&sealed.nonce);
    out.extend_from_slice(&sealed.ciphertext);
    out
}

/// The suite-2 file of these event bytes with the vector's fixed code, salt and nonce.
fn file_v2(bytes: &[u8], m: &Material, f: &FileMaterial) -> (PackKey, Vec<u8>) {
    let key = derive_file_key(&f.code, &f.salt).unwrap();
    let sealed = seal_parts(bytes, &m.organizer(), &key, f.nonce)
        .unwrap()
        .sealed;
    (key.clone(), encode_file(&f.salt, &sealed).unwrap())
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
    let file = file_v1(&parts.sealed);
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

/// The suite-3 file (§5.2) of a link fragment: `MNTE 01 03 key nonce ciphertext`, the link's
/// own bytes.
fn file_v3_of_fragment(fragment: &str) -> Vec<u8> {
    let (key, sealed) = event_core::parse_link(fragment).unwrap();
    encode_open_file(&key, &sealed).unwrap()
}

/// Adds the suite-2 file fields (§5.2) after `secretCode`, then the suite-3 file after
/// `fileV2B64u`; every other field is unchanged.
fn with_file_v2(name: &str, v: Value) -> Value {
    let m = material(name);
    let f = file_material(name);
    let bytes = event_core::b64u_decode(v["eventB64u"].as_str().unwrap()).unwrap();
    let (key, file) = file_v2(&bytes, &m, &f);
    let opened = open_file(&file, &f.code).unwrap();
    assert_eq!(opened.event_bytes, bytes);
    let file_v3 = file_v3_of_fragment(v["linkFragment"].as_str().unwrap());
    assert!(!file_needs_code(&file_v3).unwrap());
    assert_eq!(open_file_without_code(&file_v3).unwrap().event_bytes, bytes);
    let mut out = serde_json::Map::new();
    for (k, val) in v.as_object().unwrap() {
        out.insert(k.clone(), val.clone());
        if k == "secretCode" {
            out.insert("secretCodeV2".into(), json!(f.code));
            out.insert("fileSaltB64u".into(), json!(b64u(&f.salt)));
            out.insert("fileNonceV2B64u".into(), json!(b64u(&f.nonce)));
            out.insert("fileKeyV2B64u".into(), json!(b64u(key.as_bytes())));
            out.insert("fileV2B64u".into(), json!(b64u(&file)));
            out.insert("fileV3B64u".into(), json!(b64u(&file_v3)));
        }
    }
    Value::Object(out)
}

/// Fixed short-link inputs (§5.6): a 10-character base58 dropId (normally chosen by the server),
/// the 16-byte secret and the blob's nonce.
fn drop_material(name: &str) -> (String, DropSecret, [u8; 24]) {
    let alphabet = DROP_ID_ALPHABET.as_bytes();
    let id: String = fixtures::fixed32(&format!("manotu-vector:{name}:drop-id"))[..10]
        .iter()
        .map(|b| alphabet[usize::from(*b) % alphabet.len()] as char)
        .collect();
    let secret = DropSecret::from_bytes(
        fixtures::fixed32(&format!("manotu-vector:{name}:drop-secret"))[..16]
            .try_into()
            .unwrap(),
    );
    let nonce = fixtures::fixed32(&format!("manotu-vector:{name}:drop-nonce"))[..24]
        .try_into()
        .unwrap();
    (id, secret, nonce)
}

/// Adds the short-link fields (§5.6) after `fileV3B64u`; every other field is unchanged.
fn with_drop(name: &str, v: Value) -> Value {
    let m = material(name);
    let (id, secret, nonce) = drop_material(name);
    let bytes = event_core::b64u_decode(v["eventB64u"].as_str().unwrap()).unwrap();
    let (parts, blob) = seal_drop_parts(&bytes, &m.organizer(), &secret, nonce).unwrap();
    // The blob carries the same deflated envelope as the link, under the derived key.
    assert_eq!(b64u(&parts.deflated), v["deflatedB64u"].as_str().unwrap());
    assert_eq!(open_drop(&blob, &secret).unwrap().event_bytes, bytes);
    let link = drop_link(&id, secret.clone()).unwrap();
    assert_eq!(parse_drop_link(&link.to_link(DEFAULT_SITE)).unwrap(), link);
    assert_eq!(parse_drop_link(&link.to_app_link()).unwrap(), link);
    let mut out = serde_json::Map::new();
    for (k, val) in v.as_object().unwrap() {
        out.insert(k.clone(), val.clone());
        if k == "fileV3B64u" {
            out.insert("dropId".into(), json!(id));
            out.insert("secretB64u".into(), json!(secret.to_b64u()));
            out.insert("dropKeyB64u".into(), json!(b64u(secret.key().as_bytes())));
            out.insert("dropNonceB64u".into(), json!(b64u(&nonce)));
            out.insert("dropBlobB64u".into(), json!(b64u(&blob)));
            out.insert("dropFragment".into(), json!(link.fragment()));
            out.insert("dropLink".into(), json!(link.to_link(DEFAULT_SITE)));
            out.insert("dropAppLink".into(), json!(link.to_app_link()));
        }
    }
    Value::Object(out)
}

/// §5.6 short links: malformed links, truncated blobs and a wrong secret, all on the kenduri drop.
fn drop_vectors(write: &dyn Fn(&str, &Value), kenduri_v: &Value) {
    let id = kenduri_v["dropId"].as_str().unwrap();
    let secret = kenduri_v["secretB64u"].as_str().unwrap();
    let blob = event_core::b64u_decode(kenduri_v["dropBlobB64u"].as_str().unwrap()).unwrap();
    let id9 = &id[..9];
    let mut cases: Vec<(String, &str)> = vec![
        (format!("{id9}0.{secret}"), "dropId has 0 (not base58)"),
        (format!("{id9}O.{secret}"), "dropId has O (not base58)"),
        (format!("{id9}I.{secret}"), "dropId has I (not base58)"),
        (format!("{id9}l.{secret}"), "dropId has l (not base58)"),
        (format!("{id9}-.{secret}"), "dropId has -"),
        (format!("{id9}.{secret}"), "dropId has 9 characters"),
        (format!("{id}2.{secret}"), "dropId has 11 characters"),
        (format!("{id}.{}", b64u(&[7u8; 15])), "secret is 15 bytes"),
        (format!("{id}.{}", b64u(&[7u8; 17])), "secret is 17 bytes"),
        (format!("{id}.{secret}=="), "secret has padding"),
        (
            format!("{id}.{}+", &secret[..21]),
            "secret has a character outside base64url",
        ),
        (format!("{id}."), "secret missing"),
        (id.to_string(), "no secret and no separator"),
        (
            format!("https://manotu.app/a/#{id}.{secret}"),
            "short-link fragment under /a/",
        ),
        (format!("manotu://event#{secret}"), "app link without drop="),
    ];
    // Non-canonical trailing bits: 16 bytes leave 4 unused bits in the 22nd character.
    let last = secret.as_bytes()[21];
    let alphabet = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let pos = alphabet.iter().position(|c| *c == last).unwrap();
    let noncanon = alphabet[pos ^ 1] as char;
    let noncanon_link = format!("{id}.{}{noncanon}", &secret[..21]);
    cases.push((noncanon_link, "secret has non-zero trailing bits"));
    for (l, why) in &cases {
        assert_eq!(
            parse_drop_link(l).unwrap_err().code(),
            "malformed_drop_link",
            "{why}: {l}"
        );
    }
    write(
        "negative-drop-link.json",
        &negative(
            "negative-drop-link",
            "Short links (§5.6) with a dropId that is not exactly 10 base58 characters, or a secret that is not exactly 16 bytes of strict base64url: each must fail with malformed_drop_link before any request is made.",
            "malformed_drop_link",
            json!({
                "links": cases.iter().map(|(l, _)| l).collect::<Vec<_>>(),
                "reasons": cases.iter().map(|(_, r)| r).collect::<Vec<_>>(),
            }),
        ),
    );

    let lengths = [0usize, 23, 24, 39];
    let secret_v = DropSecret::from_b64u(secret).unwrap();
    for n in lengths {
        assert_eq!(
            open_drop(&blob[..n], &secret_v).unwrap_err().code(),
            "malformed_drop"
        );
    }
    write(
        "negative-drop-blob-truncated.json",
        &negative(
            "negative-drop-blob-truncated",
            "kenduri-one-day dropBlobB64u cut to 0, 23, 24 and 39 bytes (shorter than the 24-byte nonce plus the 16-byte tag): each must fail with malformed_drop before any decryption.",
            "malformed_drop",
            json!({
                "secretB64u": secret,
                "blobsB64u": lengths.iter().map(|n| b64u(&blob[..*n])).collect::<Vec<_>>(),
                "lengths": lengths,
            }),
        ),
    );

    let wrong = DropSecret::from_bytes(
        fixtures::fixed32("manotu-vector:negative-drop-wrong-secret:drop-secret")[..16]
            .try_into()
            .unwrap(),
    );
    assert_eq!(open_drop(&blob, &wrong).unwrap_err().code(), "decrypt");
    let wrong_link = drop_link(id, wrong.clone()).unwrap();
    write(
        "negative-drop-wrong-secret.json",
        &negative(
            "negative-drop-wrong-secret",
            "kenduri-one-day dropBlobB64u with a well-formed but different secret: derive the key with HKDF, then decryption must fail.",
            "decrypt",
            json!({
                "dropId": id,
                "secretB64u": wrong.to_b64u(),
                "dropKeyB64u": b64u(wrong.key().as_bytes()),
                "dropLink": wrong_link.to_link(DEFAULT_SITE),
                "dropBlobB64u": b64u(&blob),
            }),
        ),
    );
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
    let kenduri_v = with_drop(
        "kenduri-one-day",
        with_file_v2(
            "kenduri-one-day",
            positive(
                "kenduri-one-day",
                "One-day kenduri: 3 stops (parking with vehicle entrance and arrival window, drop-off, venue), 2 sessions, 2 info blocks (dress, bring), no route.",
                &kenduri,
                NOW,
            ),
        ),
    );
    write("kenduri-one-day.json", &kenduri_v);
    write(
        "ride-two-day.json",
        &with_file_v2(
            "ride-two-day",
            positive(
                "ride-two-day",
                "Two-day bicycle ride: 5 stops, 2 sessions, 1 routed route (7-point precision-6 polyline, two legs, ascent/descent).",
                &fixtures::ride(),
                NOW,
            ),
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
                "fileB64u": b64u(&file_v1(&ks)),
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
                "fileB64u": b64u(&file_v1(&ls)),
                "secretCode": lm.pack_key().to_secret_code(),
                "fileEventId": long_event.id,
            }),
        ),
    );

    file_code_vectors(&write, &kenduri_v);
    drop_vectors(&write, &kenduri_v);
    info_vectors(&write, &kenduri, &kenduri_v);
    stops_only_vectors(&write);
}

/// §4.1 info blocks and templates, and §5.2 suite-3 framing errors.
fn info_vectors(write: &dyn Fn(&str, &Value), kenduri: &Event, kenduri_v: &Value) {
    // Invalid events, signed and sealed as a link and as a suite-3 file.
    let neg = |name: &str, desc: &str, path: &str, e: &Event| {
        let bytes = serde_json::to_vec(e).unwrap();
        match Event::from_bytes(&bytes).unwrap_err() {
            event_core::Error::Invalid(v) => assert_eq!(v.path, path, "{name}"),
            other => panic!("{name}: {other:?}"),
        }
        let m = material(name);
        let sealed = seal_raw(&bytes, &m);
        let fragment = fragment_unchecked(&m.key, &sealed);
        write(
            &format!("{name}.json"),
            &negative(
                name,
                desc,
                "invalid_event",
                json!({
                    "now": NOW,
                    "expectedPath": path,
                    "eventJson": String::from_utf8(bytes).unwrap(),
                    "linkFragment": fragment,
                    "fileV3B64u": b64u(&file_v3_of_fragment(&fragment)),
                }),
            ),
        );
    };
    let mut e = kenduri.clone();
    e.info[1].text = "Bawa payung. ".repeat(77) + "Ya";
    assert_eq!(e.info[1].text.chars().count(), 1003);
    neg(
        "negative-info-text-too-long",
        "kenduri-one-day with info[1].text of 1003 code points (limit 1000), correctly signed: the event must be rejected as invalid_event at info[1].text.",
        "info[1].text",
        &e,
    );
    let mut e = kenduri.clone();
    e.info = (0..13)
        .map(|i| event_core::InfoBlock {
            kind: event_core::InfoKind::Other,
            text: format!("Nota {}", i + 1),
        })
        .collect();
    neg(
        "negative-too-many-info",
        "kenduri-one-day with 13 info blocks (limit 12), correctly signed: the event must be rejected as invalid_event at info.",
        "info",
        &e,
    );

    // Truncated suite-3 files.
    let file = event_core::b64u_decode(kenduri_v["fileV3B64u"].as_str().unwrap()).unwrap();
    let cuts = [6usize, 6 + 31, 6 + 32 + 23, 6 + 32 + 24 + 15];
    let truncated: Vec<String> = cuts
        .iter()
        .map(|&n| {
            let f = &file[..n];
            assert_eq!(
                event_core::parse_file(f).unwrap_err().code(),
                "malformed_file"
            );
            assert_eq!(file_needs_code(f).unwrap_err().code(), "malformed_file");
            b64u(f)
        })
        .collect();
    write(
        "negative-file-v3-truncated.json",
        &negative(
            "negative-file-v3-truncated",
            "kenduri-one-day fileV3B64u cut to 6 bytes (header only), 37 (key incomplete), 61 (nonce incomplete) and 77 bytes (ciphertext shorter than the 16-byte tag): each must fail with malformed_file before any decryption.",
            "malformed_file",
            json!({ "filesB64u": truncated, "lengths": cuts }),
        ),
    );

    // Unknown template and info kind read as `other`; signed bytes kept exactly as written.
    let name = "unknown-template-and-info-kind";
    let json = String::from_utf8(kenduri.to_bytes().unwrap()).unwrap();
    let json = json
        .replacen("\"template\":\"kenduri\"", "\"template\":\"gala\"", 1)
        .replacen("\"kind\":\"bring\"", "\"kind\":\"weather\"", 1);
    assert!(json.contains("\"gala\"") && json.contains("\"weather\""));
    let parsed = Event::from_bytes(json.as_bytes()).unwrap();
    assert_eq!(parsed.template, event_core::Template::Other);
    let kinds: Vec<Value> = parsed
        .info
        .iter()
        .map(|b| serde_json::to_value(b.kind).unwrap())
        .collect();
    assert_eq!(kinds, vec![json!("dress"), json!("other")]);
    let m = material(name);
    let parts = seal_parts(json.as_bytes(), &m.organizer(), &m.pack_key(), m.nonce).unwrap();
    let fragment = link_fragment(&m.pack_key(), &parts.sealed).unwrap();
    let file = encode_open_file(&m.pack_key(), &parts.sealed).unwrap();
    let opened = open_file(&file, "").unwrap();
    assert_eq!(opened.event, parsed);
    assert_eq!(opened.revision_hash, bytes_hash(json.as_bytes()));
    write(
        &format!("{name}.json"),
        &json!({
            "name": name,
            "kind": "parse",
            "description": "kenduri-one-day with template \"gala\" and info[1].kind \"weather\" (both unknown to this version), correctly signed: the event opens, template reads as other and info[1].kind as other; the signed bytes (and the revision hash) are kept exactly as received.",
            "now": NOW,
            "eventJson": json,
            "linkFragment": fragment,
            "fileV3B64u": b64u(&file),
            "revisionHashB64u": revision_hash_b64u(json.as_bytes()),
            "expected": {
                "template": "other",
                "infoKinds": kinds,
                "infoTexts": parsed.info.iter().map(|b| b.text.clone()).collect::<Vec<_>>(),
            },
        }),
    );
}

/// §5.2 suite 2: wrong / malformed codes for the kenduri file, and Argon2id known answers.
fn file_code_vectors(write: &dyn Fn(&str, &Value), kenduri_v: &Value) {
    let file_b64u = kenduri_v["fileV2B64u"].as_str().unwrap();
    let file = event_core::b64u_decode(file_b64u).unwrap();
    let right = kenduri_v["secretCodeV2"].as_str().unwrap();
    let wrong = file_material("negative-wrong-secret-code").code;
    assert_ne!(wrong, right);
    assert_eq!(open_file(&file, &wrong).unwrap_err().code(), "decrypt");
    write(
        "negative-wrong-secret-code.json",
        &negative(
            "negative-wrong-secret-code",
            "kenduri-one-day suite-2 file (fileV2B64u) with a well-formed but different 8-character code: Argon2id runs, decryption must fail.",
            "decrypt",
            json!({ "fileB64u": file_b64u, "secretCode": wrong }),
        ),
    );

    // One look-alike per code, each in a different position, plus otherwise valid characters.
    let invalid = [
        "O7M4-QX9P",
        "K0M4-QX9P",
        "K7M4-QI9P",
        "K7M4-QX91",
        "k7m4-qx9o",
    ];
    for c in invalid {
        assert_eq!(
            open_file(&file, c).unwrap_err().code(),
            "invalid_secret_code"
        );
    }
    write(
        "negative-invalid-secret-code.json",
        &negative(
            "negative-invalid-secret-code",
            "kenduri-one-day suite-2 file with codes containing O, 0, I or 1 (not in the code alphabet): each must be rejected as invalid_secret_code before any key derivation.",
            "invalid_secret_code",
            json!({ "fileB64u": file_b64u, "secretCodes": invalid }),
        ),
    );

    let mut kat = Vec::new();
    let mut add = |code: &str, salt: [u8; 16]| {
        let password = parse_secret_code_v2(code).unwrap();
        let key = derive_file_key(code, &salt).unwrap();
        kat.push(json!({
            "code": code,
            "password": password.as_str(),
            "saltHex": hex(&salt),
            "saltB64u": b64u(&salt),
            "keyHex": hex(key.as_bytes()),
            "keyB64u": b64u(key.as_bytes()),
        }));
    };
    add("2222-2222", [0u8; 16]);
    add("ZZZZ-ZZZZ", [0xff; 16]);
    add("k7m4 qx9p", std::array::from_fn(|i| i as u8));
    for name in ["kenduri-one-day", "ride-two-day"] {
        let f = file_material(name);
        add(&f.code, f.salt);
    }
    write(
        "argon2-kat.json",
        &json!({
            "name": "argon2-kat",
            "kind": "kat",
            "description": "docs/events.md §5.2 suite-2 file key: Argon2id v1.3 (libsodium crypto_pwhash, crypto_pwhash_ALG_ARGON2ID13) with opslimit 3, memlimit 67108864 bytes (64 MiB = m_cost 65536 KiB), 1 lane, 32-byte output. The password is the code read leniently (`password`: upper case, no separator, 8 ASCII characters); the salt is 16 bytes.",
            "params": {
                "alg": "argon2id13",
                "opslimit": FILE_KDF_OPSLIMIT,
                "memlimitBytes": u64::from(FILE_KDF_MEMLIMIT_KIB) * 1024,
                "parallelism": 1,
                "outlen": 32,
            },
            "argon2Kat": kat,
        }),
    );
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
