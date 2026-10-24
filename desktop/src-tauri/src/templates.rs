//! New documents from templates. Coordinates start around the map centre the user is looking
//! at; every template stop is a placeholder to move.

use chrono::{DateTime, Duration, FixedOffset, NaiveTime, TimeZone, Utc};
use event_core::{Lang, Session, StopKind, Template, TravelMode, new_event_id, new_item_id};
use route_core::LatLng;

use crate::doc::{Doc, DocKind, EventMeta, RouteDraft, RoutePoint, StopDraft};
use crate::editing::{next_generation, offset};

pub const DEFAULT_TZ: &str = "Asia/Kuala_Lumpur";

fn myt() -> FixedOffset {
    FixedOffset::east_opt(8 * 3600).expect("valid offset")
}

fn at(day_offset: i64, h: u32, m: u32, now: DateTime<Utc>) -> DateTime<FixedOffset> {
    let date = now.with_timezone(&myt()).date_naive() + Duration::days(day_offset);
    let t = NaiveTime::from_hms_opt(h, m, 0).expect("valid time");
    myt()
        .from_local_datetime(&date.and_time(t))
        .single()
        .expect("fixed offset is unambiguous")
}

fn fmt(t: DateTime<FixedOffset>) -> String {
    t.to_rfc3339_opts(chrono::SecondsFormat::Secs, false)
}

fn item_id() -> String {
    new_item_id().unwrap_or_else(|_| format!("i{}", next_generation()))
}

fn stop(kind: StopKind, label: &str, p: LatLng) -> StopDraft {
    StopDraft {
        id: item_id(),
        kind,
        label: label.into(),
        lat: p.lat,
        lng: p.lng,
        entrance: None,
        arrive_from: String::new(),
        arrive_to: String::new(),
        dwell_min: None,
        notes: String::new(),
        audience: String::new(),
        organizer_note: String::new(),
    }
}

fn route_through(title: &str, mode: TravelMode, day: String, stops: &[&StopDraft]) -> RouteDraft {
    let mut r = RouteDraft::new(mode);
    r.title = title.into();
    r.day = day;
    for s in stops {
        if !r.points.is_empty() {
            r.legs.push(crate::doc::Leg::pending(next_generation()));
        }
        r.points.push(RoutePoint {
            id: item_id(),
            lat: s.lat,
            lng: s.lng,
            stop_id: Some(s.id.clone()),
        });
    }
    r
}

fn session(
    title: &str,
    start: DateTime<FixedOffset>,
    end: DateTime<FixedOffset>,
    stop: Option<&StopDraft>,
) -> Session {
    Session {
        id: item_id(),
        title: title.into(),
        start,
        end,
        stop_id: stop.map(|s| s.id.clone()),
    }
}

pub fn new_event(template: Template, center: LatLng, lang: Lang, now: DateTime<Utc>) -> Doc {
    let c = center.rounded6();
    let near = |e: f64, n: f64| offset(c, e, n);
    let en = lang == Lang::En;
    let t = |ms: &'static str, en_s: &'static str| if en { en_s } else { ms };
    let (title, start, end) = match template {
        Template::Kenduri => (
            t("Kenduri baharu", "New kenduri"),
            at(14, 11, 0, now),
            at(14, 16, 0, now),
        ),
        Template::Dinner => (
            t("Makan malam", "Dinner"),
            at(14, 20, 0, now),
            at(14, 23, 0, now),
        ),
        Template::Company => (
            t("Majlis syarikat", "Company event"),
            at(14, 9, 0, now),
            at(14, 17, 0, now),
        ),
        Template::Ride => (
            t("Kayuhan", "Bicycle ride"),
            at(14, 7, 0, now),
            at(14, 12, 0, now),
        ),
        Template::Convoy => (
            t("Konvoi motosikal", "Motorcycle convoy"),
            at(14, 8, 0, now),
            at(14, 18, 0, now),
        ),
        Template::Trip => (
            t("Lawatan berhari", "Multi-day trip"),
            at(14, 8, 0, now),
            at(15, 18, 0, now),
        ),
        Template::Meeting => (
            t("Mesyuarat", "Meeting"),
            at(14, 9, 0, now),
            at(14, 12, 0, now),
        ),
        Template::Seminar => (
            t("Seminar", "Seminar"),
            at(14, 9, 0, now),
            at(14, 17, 0, now),
        ),
        Template::Picnic => (t("Piknik", "Picnic"), at(14, 9, 0, now), at(14, 14, 0, now)),
        Template::Hike => (t("Hiking", "Hike"), at(14, 7, 0, now), at(14, 12, 0, now)),
        Template::Other => (
            t("Acara baharu", "New event"),
            at(14, 10, 0, now),
            at(14, 12, 0, now),
        ),
    };
    let day1 = start.date_naive().format("%Y-%m-%d").to_string();
    let day2 = (start.date_naive() + Duration::days(1))
        .format("%Y-%m-%d")
        .to_string();
    let mut stops = Vec::new();
    let mut sessions = Vec::new();
    let mut routes = Vec::new();
    match template {
        Template::Kenduri
        | Template::Dinner
        | Template::Company
        | Template::Meeting
        | Template::Seminar => {
            let venue = stop(StopKind::Venue, t("Dewan", "Hall"), c);
            let parking = stop(
                StopKind::Parking,
                t("Parkir tetamu", "Guest parking"),
                near(-180.0, -120.0),
            );
            let meal_title = match template {
                Template::Company | Template::Meeting | Template::Seminar => {
                    t("Sesi utama", "Main session")
                }
                _ => t("Jamuan", "Meal"),
            };
            sessions.push(session(
                meal_title,
                start + Duration::hours(1),
                end - Duration::hours(1),
                Some(&venue),
            ));
            stops.push(parking);
            stops.push(venue);
        }
        Template::Ride | Template::Convoy => {
            let (mode, far) = if template == Template::Ride {
                (TravelMode::Bicycle, 3000.0)
            } else {
                (TravelMode::Motorcycle, 15000.0)
            };
            let a = stop(StopKind::Start, t("Titik mula", "Start"), c);
            let mid_kind = if template == Template::Ride {
                StopKind::Water
            } else {
                StopKind::Rest
            };
            let b = stop(
                mid_kind,
                t("Hentian rehat", "Rest stop"),
                near(far, far * 0.3),
            );
            let z = stop(StopKind::Finish, t("Tamat", "Finish"), near(far * 2.0, 0.0));
            routes.push(route_through(
                t("Laluan utama", "Main route"),
                mode,
                day1.clone(),
                &[&a, &b, &z],
            ));
            sessions.push(session(
                t("Taklimat", "Briefing"),
                start,
                start + Duration::minutes(30),
                Some(&a),
            ));
            stops.extend([a, b, z]);
        }
        Template::Trip => {
            let a = stop(StopKind::Start, t("Titik mula", "Start"), c);
            let h = stop(
                StopKind::Accommodation,
                t("Penginapan", "Accommodation"),
                near(40000.0, 10000.0),
            );
            let z = stop(
                StopKind::Finish,
                t("Tamat", "Finish"),
                near(5000.0, -3000.0),
            );
            routes.push(route_through(
                t("Hari 1", "Day 1"),
                TravelMode::Car,
                day1,
                &[&a, &h],
            ));
            routes.push(route_through(
                t("Hari 2", "Day 2"),
                TravelMode::Car,
                day2,
                &[&h, &z],
            ));
            stops.extend([a, h, z]);
        }
        Template::Hike => {
            stops.push(stop(StopKind::Start, t("Titik mula", "Start"), c));
        }
        Template::Picnic | Template::Other => {
            stops.push(stop(StopKind::Venue, t("Lokasi", "Venue"), c));
        }
    }
    Doc {
        id: new_event_id().unwrap_or_else(|_| format!("e{}", next_generation())),
        kind: DocKind::Event,
        updated_at: now,
        meta: EventMeta {
            template,
            title: title.into(),
            description: String::new(),
            lang,
            tz: DEFAULT_TZ.into(),
            start: fmt(start),
            end: fmt(end),
            expires_at: String::new(),
            alias: String::new(),
        },
        stops,
        sessions,
        info: Vec::new(),
        routes,
        published: Vec::new(),
        imported: None,
    }
}

pub fn new_route(mode: TravelMode, lang: Lang, now: DateTime<Utc>) -> Doc {
    let mut r = RouteDraft::new(mode);
    r.title = if lang == Lang::En {
        "Route".into()
    } else {
        "Laluan".into()
    };
    Doc {
        id: new_event_id().unwrap_or_else(|_| format!("r{}", next_generation())),
        kind: DocKind::Route,
        updated_at: now,
        meta: EventMeta {
            template: Template::Other,
            title: if lang == Lang::En {
                "New route".into()
            } else {
                "Laluan baharu".into()
            },
            description: String::new(),
            lang,
            tz: DEFAULT_TZ.into(),
            start: String::new(),
            end: String::new(),
            expires_at: String::new(),
            alias: String::new(),
        },
        stops: Vec::new(),
        sessions: Vec::new(),
        info: Vec::new(),
        routes: vec![r],
        published: Vec::new(),
        imported: None,
    }
}

/// A standalone route turned into a private event draft (nothing is published).
pub fn route_to_event(route_doc: &Doc, lang: Lang, now: DateTime<Utc>) -> Doc {
    let center = route_doc
        .routes
        .first()
        .and_then(|r| r.points.first())
        .map_or(LatLng::new(3.139, 101.6869), |p| p.pos());
    let mut ev = new_event(Template::Ride, center, lang, now);
    ev.meta.title = route_doc.meta.title.clone();
    ev.stops = route_doc.stops.clone();
    ev.routes = route_doc.routes.clone();
    ev.sessions.clear();
    ev
}
