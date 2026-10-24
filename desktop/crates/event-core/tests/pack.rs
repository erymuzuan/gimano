mod common;

use common::fixtures::{self, fixed_id};
use event_core::{
    Error, Event, ImportCandidate, ImportDecision, LatLng, OrganizerKey, PackKey, ParentCheck,
    SealedPack, StoredEvent, StoredRevision, decide_import, encode_file, encode_link, inflate_raw,
    limits, new_event_id, new_item_id, open, open_file, open_link, parse_file, parse_link, seal,
};
use route_core::encode_polyline;

fn reason(e: &Event) -> (String, &'static str) {
    let err = e.validate().unwrap_err();
    (err.path, err.reason)
}

#[test]
fn seal_and_open_round_trip_with_random_keys() {
    let event = fixtures::ride();
    let organizer = OrganizerKey::generate().unwrap();
    let key = PackKey::generate().unwrap();
    let (_, sealed) = seal(&event, &organizer, &key).unwrap();

    let link = encode_link("https://www.bukitbunga.com/gimano/", &key, &sealed).unwrap();
    assert!(link.starts_with("https://www.bukitbunga.com/gimano/a/#1."));
    let opened = open_link(&link).unwrap();
    // `seal` signs the simplified event (§5.4).
    assert_eq!(opened.event, event.simplified());
    assert_eq!(opened.organizer_pub, organizer.public_key());

    let file = encode_file(&sealed).unwrap();
    assert_eq!(&file[..6], b"MNTE\x01\x01");
    assert_eq!(open_file(&file, &key.to_secret_code()).unwrap(), opened);

    let (_, other) = seal(&event, &organizer, &key).unwrap();
    assert_ne!(other.nonce, sealed.nonce, "fresh nonce per seal");
}

#[test]
fn random_ids_have_contract_lengths() {
    assert_eq!(new_event_id().unwrap().len(), 22);
    assert_eq!(new_item_id().unwrap().len(), 11);
    assert_ne!(new_event_id().unwrap(), new_event_id().unwrap());
}

#[test]
fn per_event_keys_are_distinct() {
    let a = OrganizerKey::generate().unwrap();
    let b = OrganizerKey::generate().unwrap();
    assert_ne!(a.public_key(), b.public_key());
    assert_eq!(
        OrganizerKey::from_seed(&a.to_seed()).public_key(),
        a.public_key()
    );
}

#[test]
fn seal_refuses_invalid_events() {
    let mut e = fixtures::kenduri();
    e.title.clear();
    let err = seal(
        &e,
        &OrganizerKey::generate().unwrap(),
        &PackKey::generate().unwrap(),
    );
    assert!(matches!(err, Err(Error::Invalid(_))));
}

#[test]
fn revisions_chain_and_decide() {
    let organizer = OrganizerKey::generate().unwrap();
    let key = PackKey::generate().unwrap();
    let r1 = fixtures::kenduri();
    let o1 = open(&key, &seal(&r1, &organizer, &key).unwrap().1).unwrap();
    let mut r2 = r1.next_revision(&o1.event_bytes, "2026-11-02T00:00:00Z".parse().unwrap());
    r2.title = "Kenduri Aqil & Sofea (dikemas kini)".into();
    let o2 = open(&key, &seal(&r2, &organizer, &key).unwrap().1).unwrap();
    assert_eq!(o2.event.rev, 2);

    let now = "2026-11-03T00:00:00Z".parse().unwrap();
    let stored = StoredEvent {
        organizer_pub: o1.organizer_pub,
        revisions: vec![StoredRevision {
            rev: 1,
            hash: o1.revision_hash,
            stops_only: false,
        }],
    };
    assert_eq!(
        decide_import(&ImportCandidate::from(&o2), Some(&stored), now),
        ImportDecision::Newer {
            stored_rev: 1,
            parent: ParentCheck::Matches
        }
    );
    assert_eq!(
        decide_import(&ImportCandidate::from(&o1), Some(&stored), now),
        ImportDecision::AlreadyImported
    );

    // A second device publishing rev 2 with the same organizer key forks.
    let mut fork = r2.clone();
    fork.title = "Kenduri (salinan lain)".into();
    let of = open(&key, &seal(&fork, &organizer, &key).unwrap().1).unwrap();
    let stored2 = StoredEvent {
        organizer_pub: o1.organizer_pub,
        revisions: vec![
            StoredRevision {
                rev: 1,
                hash: o1.revision_hash,
                stops_only: false,
            },
            StoredRevision {
                rev: 2,
                hash: o2.revision_hash,
                stops_only: false,
            },
        ],
    };
    assert_eq!(
        decide_import(&ImportCandidate::from(&of), Some(&stored2), now),
        ImportDecision::Fork { rev: 2 }
    );

    // Same event id signed by someone else.
    let impostor = OrganizerKey::generate().unwrap();
    let oi = open(&key, &seal(&r2, &impostor, &key).unwrap().1).unwrap();
    assert_eq!(
        decide_import(&ImportCandidate::from(&oi), Some(&stored), now),
        ImportDecision::DifferentOrganizer
    );
}

#[test]
fn link_parsing_errors() {
    let key = PackKey::from_bytes([3; 32]);
    let sealed = SealedPack {
        nonce: [4; 24],
        ciphertext: vec![5; 40],
    };
    let link = encode_link("https://x.test", &key, &sealed).unwrap();
    let (k, s) = parse_link(&link).unwrap();
    assert_eq!((k, s), (key, sealed));
    assert_eq!(
        parse_link("https://x.test/a/#2.AAAA.BBBB").unwrap_err(),
        Error::UnsupportedLinkVersion
    );
    assert_eq!(
        parse_link("https://x.test/a/#1.AAAA").unwrap_err(),
        Error::MalformedLink
    );
    assert_eq!(parse_link("1.a.b.c").unwrap_err(), Error::MalformedLink);
    assert_eq!(parse_link("1.AAAA.AAAA").unwrap_err(), Error::MalformedLink);
    let pad = link.replace("#1.", "#1.=");
    assert_eq!(parse_link(&pad).unwrap_err(), Error::MalformedLink);
}

#[test]
fn file_parsing_errors() {
    let sealed = SealedPack {
        nonce: [4; 24],
        ciphertext: vec![5; 40],
    };
    let file = encode_file(&sealed).unwrap();
    assert_eq!(parse_file(&file).unwrap(), sealed);
    let mut bad = file.clone();
    bad[0] = b'X';
    assert_eq!(parse_file(&bad).unwrap_err(), Error::MalformedFile);
    let mut bad = file.clone();
    bad[4] = 2;
    assert_eq!(
        parse_file(&bad).unwrap_err(),
        Error::UnsupportedFileFormat(2)
    );
    let mut bad = file.clone();
    bad[5] = 9;
    assert_eq!(parse_file(&bad).unwrap_err(), Error::UnsupportedSuite(9));
    assert_eq!(parse_file(&file[..30]).unwrap_err(), Error::MalformedFile);
    let huge = vec![0u8; limits::MAX_FILE_BYTES + 1];
    assert!(matches!(parse_file(&huge), Err(Error::FileTooLarge { .. })));
    assert!(matches!(
        open_file(&file, "not-a-code"),
        Err(Error::InvalidSecretCode(_))
    ));
}

#[test]
fn inflate_is_bounded() {
    let bomb = event_core::deflate_raw(&vec![b'a'; 3 * 1024 * 1024]);
    assert!(bomb.len() < 10_000);
    assert_eq!(
        inflate_raw(&bomb, 1024 * 1024).unwrap_err(),
        Error::InflateTooLarge { max: 1024 * 1024 }
    );
    assert_eq!(
        inflate_raw(&bomb, 3 * 1024 * 1024).unwrap().len(),
        3 * 1024 * 1024
    );
    assert_eq!(
        inflate_raw(&[0xff, 0xff, 0xff], 100).unwrap_err(),
        Error::Inflate
    );
}

#[test]
fn oversized_decompression_inside_pack_is_rejected() {
    let key = PackKey::from_bytes([1; 32]);
    let nonce = [2; 24];
    let bomb = event_core::deflate_raw(&vec![b' '; event_core::MAX_INFLATED_BYTES + 1]);
    let sealed = SealedPack {
        nonce,
        ciphertext: event_core::encrypt(&key, &nonce, &bomb).unwrap(),
    };
    assert!(matches!(
        open(&key, &sealed),
        Err(Error::InflateTooLarge { .. })
    ));
}

#[test]
fn unknown_fields_are_ignored_and_null_optionals_accepted() {
    let mut v: serde_json::Value =
        serde_json::from_slice(&fixtures::kenduri().to_bytes().unwrap()).unwrap();
    v["futureField"] = serde_json::json!({"x": 1});
    v["stops"][0]["futureField"] = serde_json::json!(true);
    v["description"] = serde_json::Value::Null;
    v["template"] = serde_json::json!("picnic");
    let e = Event::from_bytes(v.to_string().as_bytes()).unwrap();
    assert_eq!(e.description, None);
    assert_eq!(e.template, event_core::Template::Other);
    let mut v2 = v.clone();
    v2["lang"] = serde_json::json!("fr");
    assert!(matches!(
        Event::from_bytes(v2.to_string().as_bytes()),
        Err(Error::MalformedEvent(_))
    ));
}

#[test]
fn organizer_only_notes_cannot_enter_a_pack() {
    let mut v: serde_json::Value =
        serde_json::from_slice(&fixtures::kenduri().to_bytes().unwrap()).unwrap();
    v["privateNotes"] = serde_json::json!("jangan kongsi");
    let e = Event::from_bytes(v.to_string().as_bytes()).unwrap();
    let out = String::from_utf8(e.to_bytes().unwrap()).unwrap();
    assert!(!out.contains("privateNotes") && !out.contains("jangan kongsi"));
}

// ---- limits (docs/events.md §4) ----

#[test]
fn fixtures_are_valid() {
    fixtures::kenduri().validate().unwrap();
    fixtures::ride().validate().unwrap();
    fixtures::ride_long_route().validate().unwrap();
}

#[test]
fn limit_event_span_14_days() {
    let mut e = fixtures::kenduri();
    e.end = e.start + chrono::Duration::days(14);
    e.validate().unwrap();
    e.end += chrono::Duration::seconds(1);
    assert_eq!(reason(&e).0, "end");
    let mut e = fixtures::kenduri();
    e.end = e.start - chrono::Duration::seconds(1);
    assert_eq!(reason(&e), ("end".into(), "ends before it starts"));
}

#[test]
fn limit_text_lengths() {
    let mut e = fixtures::kenduri();
    e.title = "a".repeat(120);
    e.validate().unwrap();
    e.title = "é".repeat(121);
    assert_eq!(reason(&e), ("title".into(), "is too long"));
    e.title = "   ".into();
    assert_eq!(reason(&e), ("title".into(), "is required"));

    let mut e = fixtures::kenduri();
    e.description = Some("x".repeat(2001));
    assert_eq!(reason(&e).0, "description");

    let mut e = fixtures::kenduri();
    e.stops[1].label = "x".repeat(81);
    assert_eq!(reason(&e).0, "stops[1].label");
    let mut e = fixtures::kenduri();
    e.stops[0].notes = Some("x".repeat(501));
    assert_eq!(reason(&e).0, "stops[0].notes");
    let mut e = fixtures::kenduri();
    e.stops[0].audience = Some("x".repeat(41));
    assert_eq!(reason(&e).0, "stops[0].audience");
    let mut e = fixtures::ride();
    e.routes[0].title = Some("x".repeat(81));
    assert_eq!(reason(&e).0, "routes[0].title");
}

#[test]
fn limit_counts() {
    let base = fixtures::kenduri();
    let mut e = base.clone();
    e.stops = (0..200)
        .map(|i| {
            let mut s = base.stops[0].clone();
            s.id = fixed_id(&format!("many/{i}"), 8);
            s
        })
        .collect();
    e.sessions.clear();
    e.validate().unwrap();
    let mut extra = e.stops[0].clone();
    extra.id = fixed_id("many/extra", 8);
    e.stops.push(extra);
    assert_eq!(reason(&e).0, "stops");

    let mut e = base.clone();
    e.sessions = (0..51)
        .map(|i| {
            let mut s = base.sessions[0].clone();
            s.id = fixed_id(&format!("ses/{i}"), 8);
            s
        })
        .collect();
    assert_eq!(reason(&e).0, "sessions");
    e.sessions.pop();
    e.validate().unwrap();

    let ride = fixtures::ride();
    let mut e = ride.clone();
    e.routes = (0..21)
        .map(|i| {
            let mut r = ride.routes[0].clone();
            r.id = fixed_id(&format!("route/{i}"), 8);
            r
        })
        .collect();
    assert_eq!(reason(&e).0, "routes");
    e.routes.pop();
    e.validate().unwrap();
}

#[test]
fn limit_total_polyline_points() {
    let mut e = fixtures::ride();
    let pts = |n: usize| -> String {
        let v: Vec<_> = (0..n)
            .map(|i| {
                LatLng::new(
                    3.0 + (i % 1000) as f64 * 1e-5,
                    101.0 + (i / 1000) as f64 * 1e-3,
                )
            })
            .collect();
        encode_polyline(&v).unwrap()
    };
    let mut r2 = e.routes[0].clone();
    r2.id = fixed_id("route/second", 8);
    e.routes[0].polyline = Some(pts(30_000));
    r2.polyline = Some(pts(20_000));
    e.routes.push(r2);
    e.validate().unwrap();
    e.routes[1].polyline = Some(pts(20_001));
    assert_eq!(
        reason(&e),
        (
            "routes[1].polyline".into(),
            "routes have more than 50 000 polyline points in total"
        )
    );
    e.routes[1].polyline = Some("!!".into());
    assert_eq!(reason(&e).1, "is not a valid precision-6 polyline");
    e.routes[1].polyline = Some(pts(1));
    assert_eq!(reason(&e).1, "needs at least 2 points");
}

#[test]
fn ids_and_references() {
    let mut e = fixtures::kenduri();
    e.id = fixed_id("short", 8);
    assert_eq!(reason(&e).0, "id");

    let mut e = fixtures::kenduri();
    e.stops[1].id = e.stops[0].id.clone();
    assert_eq!(reason(&e), ("stops[1].id".into(), "is duplicated"));

    let mut e = fixtures::kenduri();
    e.sessions[0].stop_id = Some(fixed_id("nowhere", 8));
    assert_eq!(reason(&e).0, "sessions[0].stopId");

    let mut e = fixtures::ride();
    e.routes[0].legs[1].to_stop_id = fixed_id("nowhere", 8);
    assert_eq!(reason(&e).0, "routes[0].legs[1].toStopId");
    let mut e = fixtures::ride();
    e.routes[0].stop_ids.push(fixed_id("nowhere", 8));
    assert_eq!(reason(&e).0, "routes[0].stopIds[3]");
    let mut e = fixtures::ride();
    e.routes[0].day = Some("2026-02-30".into());
    assert_eq!(reason(&e).0, "routes[0].day");
}

#[test]
fn revision_parent_rules() {
    let mut e = fixtures::kenduri();
    e.parent = Some(fixed_id("p", 32));
    assert_eq!(reason(&e), ("parent".into(), "must be null for rev 1"));
    e.rev = 2;
    e.validate().unwrap();
    e.parent = None;
    assert_eq!(reason(&e), ("parent".into(), "is required after rev 1"));
    e.parent = Some(fixed_id("p", 16));
    assert_eq!(reason(&e).1, "is not a SHA-256 hash");
    e.rev = 0;
    assert_eq!(reason(&e).0, "rev");
}

#[test]
fn coordinates_and_times() {
    let mut e = fixtures::kenduri();
    e.stops[0].lat = 91.0;
    assert_eq!(reason(&e).1, "coordinate out of range");
    let mut e = fixtures::kenduri();
    e.stops[0].lng = 101.123_456_7;
    assert_eq!(reason(&e).1, "coordinate has more than 6 decimals");
    let mut e = fixtures::kenduri();
    e.stops[0].entrance = Some(LatLng::new(2.0, 181.0));
    assert_eq!(reason(&e).0, "stops[0].entrance");
    let mut e = fixtures::kenduri();
    let a = e.stops[0].arrive.as_mut().unwrap();
    std::mem::swap(&mut a.from, &mut a.to);
    assert_eq!(reason(&e).0, "stops[0].arrive");
    let mut e = fixtures::kenduri();
    e.stops[2].dwell_min = Some(limits::MAX_DWELL_MIN + 1);
    assert_eq!(reason(&e).0, "stops[2].dwellMin");
    let mut e = fixtures::kenduri();
    e.tz = "../etc/passwd".into();
    assert_eq!(reason(&e).0, "tz");
    e.tz = "America/Argentina/Buenos_Aires".into();
    e.validate().unwrap();
}

#[test]
fn time_formats_on_the_wire() {
    let json = String::from_utf8(fixtures::kenduri().to_bytes().unwrap()).unwrap();
    assert!(json.contains(r#""publishedAt":"2026-11-01T02:15:00Z""#));
    assert!(json.contains(r#""expiresAt":"2026-11-16T08:00:00Z""#));
    assert!(json.contains(r#""start":"2026-11-15T11:00:00+08:00""#));
    assert!(json.contains(r#""parent":null"#));
    assert!(!json.contains(r#""routes""#));
}
