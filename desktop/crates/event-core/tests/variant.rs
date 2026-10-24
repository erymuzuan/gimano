//! docs/events.md §5.4 (simplification before signing), §5.5 (stops-only variant) and §7.4
//! (variant vs full on import), end to end with real keys.

mod common;

use common::fixtures;
use event_core::{
    Error, Event, ImportCandidate, ImportDecision, OrganizerKey, PackKey, ParentCheck, StoredEvent,
    StoredRevision, Variant, b64u, bytes_hash, decide_import, encode_link, open, open_link,
    revision_hash, revision_hash_b64u, seal, seal_bytes, stops_only_variant,
};
use route_core::{LatLng, decode_polyline, encode_polyline};
use serde_json::{Value, json};

fn json_of(bytes: &[u8]) -> Value {
    serde_json::from_slice(bytes).unwrap()
}

fn from_json(v: &Value) -> Result<Event, Error> {
    Event::from_bytes(v.to_string().as_bytes())
}

#[test]
fn variant_is_the_full_event_without_polylines() {
    let full = fixtures::ride().to_bytes().unwrap();
    let var = stops_only_variant(&full).unwrap();
    assert_eq!(stops_only_variant(&full).unwrap(), var, "deterministic");

    let (f, v) = (json_of(&full), json_of(&var));
    for k in [
        "id",
        "rev",
        "parent",
        "publishedAt",
        "stops",
        "sessions",
        "title",
    ] {
        assert_eq!(f[k], v[k], "{k}");
    }
    let mut routes = f["routes"].clone();
    routes[0].as_object_mut().unwrap().remove("polyline");
    assert_eq!(v["routes"], routes, "every other route field is kept");
    assert_eq!(
        v["variant"],
        json!({"kind": "stops-only", "fullHash": b64u(&bytes_hash(&full))})
    );
    // `variant` is appended as the last field; the rest is byte-for-byte the full event's order.
    let text = String::from_utf8(var.clone()).unwrap();
    assert!(text.ends_with(&format!(
        r#","variant":{{"kind":"stops-only","fullHash":"{}"}}}}"#,
        b64u(&bytes_hash(&full))
    )));

    let e = Event::from_bytes(&var).unwrap();
    assert!(e.is_stops_only());
    assert!(e.routes.iter().all(|r| r.polyline.is_none()));
    // The revision hash of the variant is the full event's.
    assert_eq!(revision_hash(&var), bytes_hash(&full));
    assert_eq!(revision_hash(&full), bytes_hash(&full));
    assert_ne!(bytes_hash(&var), bytes_hash(&full));
    // Only from a full event.
    assert_eq!(stops_only_variant(&var).unwrap_err(), Error::NotFullEvent);
    // An event without routes still gets a (trivial) variant.
    let k = fixtures::kenduri().to_bytes().unwrap();
    assert!(
        Event::from_bytes(&stops_only_variant(&k).unwrap())
            .unwrap()
            .is_stops_only()
    );
}

#[test]
fn variant_rules_have_their_own_codes() {
    let full = json_of(&fixtures::ride().to_bytes().unwrap());
    let var = json_of(&stops_only_variant(&fixtures::ride().to_bytes().unwrap()).unwrap());

    let mut v = var.clone();
    v["routes"][0]["polyline"] = full["routes"][0]["polyline"].clone();
    let err = from_json(&v).unwrap_err();
    assert_eq!(err, Error::VariantHasPolyline { route: 0 });
    assert_eq!(err.code(), "variant_has_polyline");

    let mut f = full.clone();
    f["routes"][0].as_object_mut().unwrap().remove("polyline");
    let err = from_json(&f).unwrap_err();
    assert_eq!(err, Error::MissingPolyline { route: 0 });
    assert_eq!(err.code(), "missing_polyline");
    // `null` reads as absent.
    f["routes"][0]["polyline"] = Value::Null;
    assert_eq!(from_json(&f).unwrap_err().code(), "missing_polyline");

    let mut u = var.clone();
    u["variant"]["kind"] = json!("thumbnail");
    let err = from_json(&u).unwrap_err();
    assert_eq!(err, Error::UnsupportedVariant("thumbnail".into()));
    assert_eq!(err.code(), "unsupported_variant");
    // Also on a full event that names an unknown variant.
    let mut u2 = full.clone();
    u2["variant"] = json!({"kind": "future", "fullHash": var["variant"]["fullHash"]});
    assert_eq!(from_json(&u2).unwrap_err().code(), "unsupported_variant");

    let mut h = var.clone();
    h["variant"]["fullHash"] = json!("AAAA");
    assert_eq!(from_json(&h).unwrap_err().code(), "invalid_event");

    // `variant: null` is a full event.
    let mut n = full.clone();
    n["variant"] = Value::Null;
    assert!(!from_json(&n).unwrap().is_stops_only());

    // `validate()` reports the same rules as paths.
    let mut e = fixtures::ride();
    e.routes[0].polyline = None;
    assert_eq!(e.validate().unwrap_err().path, "routes[0].polyline");
    e.variant = Some(Variant::stops_only([7; 32]));
    e.validate().unwrap();
    e.routes[0].polyline = fixtures::ride().routes[0].polyline.clone();
    assert_eq!(
        e.validate().unwrap_err().reason,
        "must be absent on a stops-only variant"
    );
}

/// A dense routed line along the ride: points every ~10 m on straight-ish segments.
fn dense(points: &[LatLng]) -> Vec<LatLng> {
    let mut out = Vec::new();
    for w in points.windows(2) {
        let n = (route_core::haversine_m(w[0], w[1]) / 10.0).ceil() as usize;
        for i in 0..n {
            let t = i as f64 / n as f64;
            out.push(
                LatLng::new(
                    w[0].lat + (w[1].lat - w[0].lat) * t,
                    w[0].lng + (w[1].lng - w[0].lng) * t,
                )
                .rounded6(),
            );
        }
    }
    out.push(*points.last().unwrap());
    out
}

#[test]
fn writer_simplifies_before_signing() {
    let mut e = fixtures::ride();
    let pts = dense(&fixtures::ride_polyline());
    assert!(pts.len() > 2000);
    e.routes[0].polyline = Some(encode_polyline(&pts).unwrap());
    let org = OrganizerKey::generate().unwrap();
    let key = PackKey::generate().unwrap();
    let (bytes, sealed) = seal(&e, &org, &key).unwrap();
    let opened = open(&key, &sealed).unwrap();
    assert_eq!(opened.event_bytes, bytes);
    let signed =
        decode_polyline(opened.event.routes[0].polyline.as_deref().unwrap(), 50_000).unwrap();
    // Only about the corners survive; routing values are untouched.
    assert!(signed.len() <= 8, "{} points", signed.len());
    assert_eq!(signed.first(), pts.first());
    assert_eq!(signed.last(), pts.last());
    assert_eq!(signed, route_core::simplify(&pts, 5.0));
    // The ride fixture's own last three points are collinear: 7 → 6.
    let corners = route_core::simplify(&fixtures::ride_polyline(), 5.0);
    assert_eq!(corners.len(), 6);
    assert!(!corners.contains(&LatLng::new(3.03, 101.965)));
    assert_eq!(opened.event.routes[0].distance_m, e.routes[0].distance_m);
    assert_eq!(opened.event.routes[0].duration_s, e.routes[0].duration_s);
    assert_eq!(opened.event, e.simplified());
    // Simplifying twice changes nothing.
    assert_eq!(e.simplified().simplified(), e.simplified());
}

#[test]
fn long_route_falls_back_to_a_stops_only_link() {
    let long = fixtures::ride_long_route();
    let org = OrganizerKey::generate().unwrap();
    let key = PackKey::generate().unwrap();
    let (full, sealed) = seal(&long, &org, &key).unwrap();
    assert!(matches!(
        encode_link("https://x.test", &key, &sealed),
        Err(Error::FragmentTooLong { max: 16_000, .. })
    ));
    let var = stops_only_variant(&full).unwrap();
    let link = encode_link(
        "https://x.test",
        &key,
        &seal_bytes(&var, &org, &key).unwrap(),
    )
    .unwrap();
    assert!(link.len() < 3000, "{}", link.len());
    let opened = open_link(&link).unwrap();
    assert!(opened.event.is_stops_only());
    assert_eq!(opened.event.routes.len(), 1);
    assert_eq!(opened.event.routes[0].distance_m, long.routes[0].distance_m);
    assert_eq!(opened.revision_hash, bytes_hash(&full));
    assert_eq!(opened.event_bytes, var);
}

fn stored(entries: &[(&event_core::OpenedPack, u32)]) -> StoredEvent {
    StoredEvent {
        organizer_pub: entries[0].0.organizer_pub,
        revisions: entries
            .iter()
            .map(|(o, rev)| StoredRevision {
                rev: *rev,
                hash: o.revision_hash,
                stops_only: o.event.is_stops_only(),
            })
            .collect(),
    }
}

#[test]
fn import_variant_then_full_then_next_revision() {
    let org = OrganizerKey::generate().unwrap();
    let key = PackKey::generate().unwrap();
    let now = "2026-11-12T00:00:00Z".parse().unwrap();
    let (full_bytes, full_sealed) = seal(&fixtures::ride(), &org, &key).unwrap();
    let full = open(&key, &full_sealed).unwrap();
    let var_bytes = stops_only_variant(&full_bytes).unwrap();
    let var = open(&key, &seal_bytes(&var_bytes, &org, &key).unwrap()).unwrap();
    assert_eq!(var.revision_hash, full.revision_hash);

    // Variant first, then the full event: upgrade, silently.
    let after_variant = stored(&[(&var, 1)]);
    assert_eq!(
        decide_import(&ImportCandidate::from(&full), Some(&after_variant), now),
        ImportDecision::UpgradeToFull { rev: 1 }
    );
    // Full first, then the variant: already imported.
    let after_full = stored(&[(&full, 1)]);
    assert_eq!(
        decide_import(&ImportCandidate::from(&var), Some(&after_full), now),
        ImportDecision::AlreadyImported
    );

    // Rev 2 (made from the full event) checks its parent against the stored variant.
    let mut r2 =
        fixtures::ride().next_revision(&full_bytes, "2026-11-11T00:00:00Z".parse().unwrap());
    r2.title = "Jelajah Basikal Dua Hari (dikemas kini)".into();
    let (r2_bytes, r2_sealed) = seal(&r2, &org, &key).unwrap();
    let o2 = open(&key, &r2_sealed).unwrap();
    assert_eq!(
        o2.event.parent.as_deref(),
        Some(revision_hash_b64u(&var_bytes).as_str())
    );
    let expect = ImportDecision::Newer {
        stored_rev: 1,
        parent: ParentCheck::Matches,
    };
    assert_eq!(
        decide_import(&ImportCandidate::from(&o2), Some(&after_variant), now),
        expect
    );
    // A rev-2 variant too, and a next revision drafted from variant bytes still points at the full.
    let o2v = open(
        &key,
        &seal_bytes(&stops_only_variant(&r2_bytes).unwrap(), &org, &key).unwrap(),
    )
    .unwrap();
    assert_eq!(
        decide_import(&ImportCandidate::from(&o2v), Some(&after_variant), now),
        expect
    );
    let from_var = Event::from_bytes(&var_bytes)
        .unwrap()
        .next_revision(&var_bytes, "2026-11-11T00:00:00Z".parse().unwrap());
    assert_eq!(from_var.parent, Some(b64u(&bytes_hash(&full_bytes))));
    assert_eq!(from_var.variant, None);
}
