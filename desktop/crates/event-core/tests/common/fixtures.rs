//! Fictional fixture events and fixed key material for the test vectors.
//! Shared by `examples/gen_vectors.rs` and the integration tests.
#![allow(dead_code)]

use event_core::{
    Event, GeometrySource, Lang, LatLng, OrganizerKey, PackKey, Route, RouteLeg, Session, Stop,
    StopKind, Template, TimeWindow, TravelMode, b64u, default_expiry,
};
use route_core::encode_polyline;
use sha2::{Digest, Sha256};

/// Arbitrary but fixed bytes: SHA-256 of a label.
pub fn fixed32(label: &str) -> [u8; 32] {
    Sha256::digest(label.as_bytes()).into()
}

pub fn fixed_id(label: &str, len: usize) -> String {
    b64u(&fixed32(label)[..len])
}

pub struct Material {
    pub seed: [u8; 32],
    pub key: [u8; 32],
    pub nonce: [u8; 24],
}

pub fn material(name: &str) -> Material {
    Material {
        seed: fixed32(&format!("manotu-vector:{name}:ed25519-seed")),
        key: fixed32(&format!("manotu-vector:{name}:key")),
        nonce: fixed32(&format!("manotu-vector:{name}:nonce"))[..24]
            .try_into()
            .unwrap(),
    }
}

impl Material {
    pub fn organizer(&self) -> OrganizerKey {
        OrganizerKey::from_seed(&self.seed)
    }
    pub fn pack_key(&self) -> PackKey {
        PackKey::from_bytes(self.key)
    }
}

fn t(s: &str) -> chrono::DateTime<chrono::FixedOffset> {
    chrono::DateTime::parse_from_rfc3339(s).unwrap()
}

fn utc(s: &str) -> chrono::DateTime<chrono::Utc> {
    s.parse().unwrap()
}

fn stop(label_id: &str, kind: StopKind, label: &str, lat: f64, lng: f64) -> Stop {
    Stop {
        id: fixed_id(label_id, 8),
        kind,
        label: label.into(),
        lat,
        lng,
        entrance: None,
        arrive: None,
        dwell_min: None,
        notes: None,
        audience: None,
    }
}

/// One-day kenduri: 3 stops (parking with a vehicle entrance), 2 sessions, no route.
pub fn kenduri() -> Event {
    let mut parking = stop(
        "kenduri/parking",
        StopKind::Parking,
        "Parkir tetamu",
        2.9871,
        101.793,
    );
    parking.entrance = Some(LatLng::new(2.9866, 101.7942));
    parking.arrive = Some(TimeWindow {
        from: t("2026-11-15T11:00:00+08:00"),
        to: t("2026-11-15T16:00:00+08:00"),
    });
    parking.notes = Some("Masuk ikut pagar belakang sekolah.".into());
    parking.audience = Some("Semua".into());

    let mut dropoff = stop(
        "kenduri/dropoff",
        StopKind::Dropoff,
        "Turun penumpang (OKU)",
        2.98805,
        101.79241,
    );
    dropoff.notes = Some("Kerusi roda disediakan di pintu sisi.".into());

    let mut venue = stop(
        "kenduri/venue",
        StopKind::Venue,
        "Dewan Serbaguna Taman Kenanga",
        2.9884,
        101.7919,
    );
    venue.dwell_min = Some(120);

    let start = t("2026-11-15T11:00:00+08:00");
    let end = t("2026-11-15T16:00:00+08:00");
    Event {
        id: fixed_id("kenduri/event", 16),
        rev: 1,
        parent: None,
        published_at: utc("2026-11-01T02:15:00Z"),
        template: Template::Kenduri,
        title: "Kenduri Aqil & Sofea".into(),
        description: Some("Majlis perkahwinan. Sila datang awal untuk parkir.".into()),
        lang: Lang::Ms,
        tz: "Asia/Kuala_Lumpur".into(),
        start,
        end,
        expires_at: default_expiry(&end),
        organizer: Some(event_core::Organizer {
            alias: Some("Keluarga Aqil".into()),
        }),
        sessions: vec![
            Session {
                id: fixed_id("kenduri/s1", 8),
                title: "Jamuan".into(),
                start: t("2026-11-15T11:30:00+08:00"),
                end: t("2026-11-15T16:00:00+08:00"),
                stop_id: Some(venue.id.clone()),
            },
            Session {
                id: fixed_id("kenduri/s2", 8),
                title: "Ketibaan pengantin".into(),
                start: t("2026-11-15T12:30:00+08:00"),
                end: t("2026-11-15T13:00:00+08:00"),
                stop_id: Some(venue.id.clone()),
            },
        ],
        stops: vec![parking, dropoff, venue],
        routes: vec![],
        variant: None,
    }
}

/// The kenduri held in September: expired by 24 Oct 2026.
pub fn kenduri_expired() -> Event {
    let mut e = kenduri();
    e.id = fixed_id("kenduri-expired/event", 16);
    e.published_at = utc("2026-09-01T02:15:00Z");
    e.start = t("2026-09-20T11:00:00+08:00");
    e.end = t("2026-09-20T16:00:00+08:00");
    e.expires_at = default_expiry(&e.end);
    e.stops[0].arrive = Some(TimeWindow {
        from: e.start,
        to: e.end,
    });
    e.sessions.clear();
    e
}

pub fn ride_polyline() -> Vec<LatLng> {
    vec![
        LatLng::new(2.95, 101.85),
        LatLng::new(2.962, 101.866),
        LatLng::new(2.975, 101.881),
        LatLng::new(2.99, 101.9),
        LatLng::new(3.01, 101.93),
        LatLng::new(3.03, 101.965),
        LatLng::new(3.05, 102.0),
    ]
}

/// Two-day bicycle ride: 5 stops, 1 routed route with two legs and ascent/descent.
pub fn ride() -> Event {
    let start_stop = stop(
        "ride/start",
        StopKind::Start,
        "Mula: Dataran Kayuh",
        2.95,
        101.85,
    );
    let mut cp = stop(
        "ride/cp1",
        StopKind::Checkpoint,
        "CP1 Air & regroup",
        2.99,
        101.9,
    );
    cp.arrive = Some(TimeWindow {
        from: t("2026-11-21T08:15:00+08:00"),
        to: t("2026-11-21T09:00:00+08:00"),
    });
    cp.dwell_min = Some(15);
    cp.audience = Some("Penunggang".into());
    let mut lunch = stop(
        "ride/lunch",
        StopKind::Meal,
        "Makan tengah hari",
        3.02,
        101.95,
    );
    lunch.dwell_min = Some(60);
    let mut stay = stop(
        "ride/stay",
        StopKind::Accommodation,
        "Homestay Bukit Fiksyen",
        3.05,
        102.0,
    );
    stay.entrance = Some(LatLng::new(3.0503, 102.0011));
    stay.notes = Some("Basikal disimpan di stor belakang.".into());
    let finish = stop(
        "ride/finish",
        StopKind::Finish,
        "Tamat: Dataran Kayuh",
        2.951,
        101.851,
    );

    let route = Route {
        id: fixed_id("ride/route1", 8),
        title: Some("Hari 1".into()),
        mode: TravelMode::Bicycle,
        day: Some("2026-11-21".into()),
        stop_ids: vec![start_stop.id.clone(), cp.id.clone(), stay.id.clone()],
        polyline: Some(encode_polyline(&ride_polyline()).unwrap()),
        source: GeometrySource::Routed,
        distance_m: 20_100,
        duration_s: Some(4_520),
        ascent_m: Some(420),
        descent_m: Some(310),
        legs: vec![
            RouteLeg {
                from_stop_id: start_stop.id.clone(),
                to_stop_id: cp.id.clone(),
                distance_m: 7_050,
                duration_s: Some(1_590),
                source: GeometrySource::Routed,
            },
            RouteLeg {
                from_stop_id: cp.id.clone(),
                to_stop_id: stay.id.clone(),
                distance_m: 13_050,
                duration_s: Some(2_930),
                source: GeometrySource::Routed,
            },
        ],
    };

    let start = t("2026-11-21T07:00:00+08:00");
    let end = t("2026-11-22T13:00:00+08:00");
    Event {
        id: fixed_id("ride/event", 16),
        rev: 1,
        parent: None,
        published_at: utc("2026-11-10T09:30:00Z"),
        template: Template::Ride,
        title: "Jelajah Basikal Dua Hari".into(),
        description: Some("Two-day social ride, about 20 km on day 1. Helmets required.".into()),
        lang: Lang::En,
        tz: "Asia/Kuala_Lumpur".into(),
        start,
        end,
        expires_at: default_expiry(&end),
        organizer: Some(event_core::Organizer {
            alias: Some("Kelab Kayuh Fiktif".into()),
        }),
        sessions: vec![
            Session {
                id: fixed_id("ride/s1", 8),
                title: "Flag off".into(),
                start: t("2026-11-21T07:30:00+08:00"),
                end: t("2026-11-21T07:45:00+08:00"),
                stop_id: Some(start_stop.id.clone()),
            },
            Session {
                id: fixed_id("ride/s2", 8),
                title: "Dinner & briefing".into(),
                start: t("2026-11-21T20:00:00+08:00"),
                end: t("2026-11-21T21:00:00+08:00"),
                stop_id: Some(stay.id.clone()),
            },
        ],
        stops: vec![start_stop, cp, lunch, stay, finish],
        routes: vec![route],
        variant: None,
    }
}

/// Ride with a long, noisy sketch route: its link fragment exceeds 16 000 characters.
pub fn ride_long_route() -> Event {
    let mut e = ride();
    e.id = fixed_id("ride-long/event", 16);
    let mut state: u64 = 0x5eed;
    let mut next = || {
        state = state
            .wrapping_mul(6_364_136_223_846_793_005)
            .wrapping_add(1_442_695_040_888_963_407);
        ((state >> 33) % 1001) as i64 - 500
    };
    let (mut lat, mut lng) = (2_950_000i64, 101_850_000i64);
    let mut pts = Vec::with_capacity(6000);
    for _ in 0..6000 {
        lat += next();
        lng += next() + 120;
        pts.push(LatLng::new(lat as f64 / 1e6, lng as f64 / 1e6));
    }
    let r = &mut e.routes[0];
    r.polyline = Some(encode_polyline(&pts).unwrap());
    r.source = GeometrySource::Sketch;
    r.duration_s = None;
    r.ascent_m = None;
    r.descent_m = None;
    for l in &mut r.legs {
        l.source = GeometrySource::Sketch;
        l.duration_s = None;
    }
    e
}
