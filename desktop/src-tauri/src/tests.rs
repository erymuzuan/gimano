//! Unit tests for the command handlers' pure logic: route editing, undo/redo, routing-result
//! application, event assembly (privacy of organizer notes), publishing and import.

use chrono::{DateTime, Duration, Utc};
use event_core::{StopKind, Template, TravelMode};
use route_core::LatLng;

use crate::assemble::{Severity, build_event, route_summary};
use crate::core::{Core, PublishTarget, Published};
use crate::doc::{Doc, LegState};
use crate::editing::{
    EditOp, History, JobKind, JobOutput, Selection, apply, apply_job, pending_jobs,
};
use crate::publish;
use crate::settings::Settings;
use crate::templates;

fn now() -> DateTime<Utc> {
    "2026-10-24T02:00:00Z".parse().unwrap()
}

const KL: LatLng = LatLng::new(3.139, 101.6869);

fn route_doc() -> (Doc, String) {
    let doc = templates::new_route(TravelMode::Bicycle, event_core::Lang::Ms, now());
    let rid = doc.routes[0].id.clone();
    (doc, rid)
}

fn add(doc: &mut Doc, rid: &str, lat: f64, lng: f64) {
    apply(
        doc,
        EditOp::AddPoint {
            route_id: rid.into(),
            lat,
            lng,
            stop_id: None,
        },
    )
    .unwrap();
}

/// Resolves every pending leg as if the routing server answered with a 3-point road.
fn route_all(doc: &mut Doc) {
    for job in pending_jobs(doc) {
        let JobKind::Route { from, to } = job.kind else {
            panic!("expected a point-to-point job");
        };
        let mid = LatLng::new((from.lat + to.lat) / 2.0 + 0.001, (from.lng + to.lng) / 2.0);
        assert!(apply_job(
            doc,
            &job,
            Ok(JobOutput::Routed {
                points: vec![from, mid, to],
                distance_m: 1000,
                duration_s: 240
            })
        ));
    }
}

fn states(doc: &Doc, rid: &str) -> Vec<LegState> {
    doc.route(rid)
        .unwrap()
        .legs
        .iter()
        .map(|l| l.state)
        .collect()
}

#[test]
fn points_create_pending_legs_never_straight_lines() {
    let (mut doc, rid) = route_doc();
    add(&mut doc, &rid, 3.10, 101.60);
    add(&mut doc, &rid, 3.11, 101.61);
    add(&mut doc, &rid, 3.12, 101.62);
    let r = doc.route(&rid).unwrap();
    assert!(r.is_consistent());
    assert_eq!(states(&doc, &rid), [LegState::Pending, LegState::Pending]);
    assert!(r.legs.iter().all(|l| l.geometry.is_empty()));
    let s = route_summary(r);
    assert_eq!((s.distance_m, s.unresolved_legs, s.source), (0, 2, None));
    assert_eq!(pending_jobs(&doc).len(), 2);
}

#[test]
fn sketch_mode_draws_labelled_straight_lines() {
    let (mut doc, rid) = route_doc();
    let r = doc.route(&rid).unwrap().clone();
    apply(
        &mut doc,
        EditOp::UpdateRoute {
            route_id: rid.clone(),
            title: r.title,
            day: r.day,
            mode: r.mode,
            follow_roads: false,
        },
    )
    .unwrap();
    add(&mut doc, &rid, 3.10, 101.60);
    add(&mut doc, &rid, 3.11, 101.60);
    assert_eq!(states(&doc, &rid), [LegState::Sketch]);
    assert!(pending_jobs(&doc).is_empty());
    let s = route_summary(doc.route(&rid).unwrap());
    assert_eq!(s.source, Some(event_core::GeometrySource::Sketch));
    assert!(!s.navigable);
    assert!((1100..1120).contains(&s.distance_m), "{}", s.distance_m);
    assert_eq!(s.duration_s, None);
}

#[test]
fn insert_and_move_recompute_only_affected_legs() {
    let (mut doc, rid) = route_doc();
    for i in 0..4 {
        add(&mut doc, &rid, 3.10 + f64::from(i) * 0.01, 101.60);
    }
    route_all(&mut doc);
    assert_eq!(states(&doc, &rid), [LegState::Routed; 3]);
    let gens: Vec<u64> = doc
        .route(&rid)
        .unwrap()
        .legs
        .iter()
        .map(|l| l.generation)
        .collect();

    // Insert on leg 1: legs 1 and 2 of the new list are new, the others untouched.
    let sel = apply(
        &mut doc,
        EditOp::InsertPoint {
            route_id: rid.clone(),
            leg_index: 1,
            lat: 3.115,
            lng: 101.605,
        },
    )
    .unwrap();
    assert_eq!(
        sel,
        Some(Selection::Point {
            route_id: rid.clone(),
            index: 2
        })
    );
    assert_eq!(
        states(&doc, &rid),
        [
            LegState::Routed,
            LegState::Pending,
            LegState::Pending,
            LegState::Routed
        ]
    );
    let r = doc.route(&rid).unwrap();
    assert_eq!((r.points.len(), r.legs.len()), (5, 4));
    assert_eq!(r.legs[0].generation, gens[0]);
    assert_eq!(r.legs[3].generation, gens[2]);
    route_all(&mut doc);

    // Moving the last point invalidates only the last leg.
    apply(
        &mut doc,
        EditOp::MovePoint {
            route_id: rid.clone(),
            index: 4,
            lat: 3.2,
            lng: 101.7,
        },
    )
    .unwrap();
    assert_eq!(
        states(&doc, &rid),
        [
            LegState::Routed,
            LegState::Routed,
            LegState::Routed,
            LegState::Pending
        ]
    );
    // A 10 m keyboard nudge on the first point invalidates only the first leg.
    route_all(&mut doc);
    let before = doc.route(&rid).unwrap().points[0].pos();
    apply(
        &mut doc,
        EditOp::NudgePoint {
            route_id: rid.clone(),
            index: 0,
            east_m: 0.0,
            north_m: 10.0,
        },
    )
    .unwrap();
    let after = doc.route(&rid).unwrap().points[0].pos();
    let moved = route_core::haversine_m(before, after);
    assert!((9.0..11.0).contains(&moved), "{moved}");
    assert_eq!(states(&doc, &rid)[0], LegState::Pending);
    assert_eq!(states(&doc, &rid)[1..], [LegState::Routed; 3]);
}

#[test]
fn delete_point_merges_neighbouring_legs() {
    let (mut doc, rid) = route_doc();
    for i in 0..4 {
        add(&mut doc, &rid, 3.10 + f64::from(i) * 0.01, 101.60);
    }
    route_all(&mut doc);
    apply(
        &mut doc,
        EditOp::DeletePoint {
            route_id: rid.clone(),
            index: 1,
        },
    )
    .unwrap();
    assert_eq!(states(&doc, &rid), [LegState::Pending, LegState::Routed]);
    apply(
        &mut doc,
        EditOp::DeletePoint {
            route_id: rid.clone(),
            index: 2,
        },
    )
    .unwrap();
    assert_eq!(states(&doc, &rid), [LegState::Pending]);
    apply(
        &mut doc,
        EditOp::DeletePoint {
            route_id: rid.clone(),
            index: 0,
        },
    )
    .unwrap();
    let r = doc.route(&rid).unwrap();
    assert_eq!((r.points.len(), r.legs.len()), (1, 0));
}

#[test]
fn reverse_recalculates_roads_but_keeps_sketches() {
    let (mut doc, rid) = route_doc();
    add(&mut doc, &rid, 3.10, 101.60);
    add(&mut doc, &rid, 3.11, 101.60);
    route_all(&mut doc);
    // Second leg as an explicit sketch.
    add(&mut doc, &rid, 3.12, 101.61);
    apply(
        &mut doc,
        EditOp::KeepLegAsSketch {
            route_id: rid.clone(),
            leg_index: 1,
        },
    )
    .unwrap();
    let first = doc.route(&rid).unwrap().points[0].pos();
    apply(
        &mut doc,
        EditOp::Reverse {
            route_id: rid.clone(),
        },
    )
    .unwrap();
    let r = doc.route(&rid).unwrap();
    assert_eq!(r.points.last().unwrap().pos(), first);
    // Directional restrictions: the road leg is routed again; the sketch is reversed in place.
    assert_eq!(states(&doc, &rid), [LegState::Sketch, LegState::Pending]);
    assert_eq!(r.legs[0].geometry[0], LatLng::new(3.12, 101.61));
}

#[test]
fn close_loop_and_out_and_back() {
    let (mut doc, rid) = route_doc();
    add(&mut doc, &rid, 3.10, 101.60);
    add(&mut doc, &rid, 3.11, 101.60);
    add(&mut doc, &rid, 3.12, 101.61);
    route_all(&mut doc);
    let mut loop_doc = doc.clone();
    apply(
        &mut loop_doc,
        EditOp::CloseLoop {
            route_id: rid.clone(),
        },
    )
    .unwrap();
    let r = loop_doc.route(&rid).unwrap();
    assert_eq!(r.points.len(), 4);
    assert_eq!(r.points[3].pos(), r.points[0].pos());
    assert_eq!(
        states(&loop_doc, &rid),
        [LegState::Routed, LegState::Routed, LegState::Pending]
    );
    assert!(
        apply(
            &mut loop_doc,
            EditOp::CloseLoop {
                route_id: rid.clone()
            }
        )
        .is_err()
    );

    apply(
        &mut doc,
        EditOp::OutAndBack {
            route_id: rid.clone(),
        },
    )
    .unwrap();
    let r = doc.route(&rid).unwrap();
    let lats: Vec<f64> = r.points.iter().map(|p| p.lat).collect();
    assert_eq!(lats, [3.10, 3.11, 3.12, 3.11, 3.10]);
    // The return legs are new routing requests, not reversed coordinates.
    assert_eq!(
        states(&doc, &rid),
        [
            LegState::Routed,
            LegState::Routed,
            LegState::Pending,
            LegState::Pending
        ]
    );
}

#[test]
fn split_by_day_keeps_continuity() {
    let mut doc = templates::new_event(Template::Trip, KL, event_core::Lang::Ms, now());
    let rid = doc.routes[0].id.clone();
    // Day 1 route goes start → accommodation; add two points after it.
    add(&mut doc, &rid, 3.3, 101.9);
    add(&mut doc, &rid, 3.4, 102.0);
    route_all(&mut doc);
    let day = doc.route(&rid).unwrap().day.clone();
    let sel = apply(
        &mut doc,
        EditOp::SplitAt {
            route_id: rid.clone(),
            index: 1,
        },
    )
    .unwrap();
    let Some(Selection::Route { route_id: bid }) = sel else {
        panic!("{sel:?}")
    };
    let a = doc.route(&rid).unwrap();
    let b = doc.route(&bid).unwrap();
    assert!(a.is_consistent() && b.is_consistent());
    assert_eq!((a.points.len(), b.points.len()), (2, 3));
    assert_eq!(a.points.last().unwrap().pos(), b.points[0].pos());
    assert_eq!(a.points.last().unwrap().stop_id, b.points[0].stop_id);
    assert!(b.legs.iter().all(|l| l.state == LegState::Routed));
    let next: chrono::NaiveDate = day.parse::<chrono::NaiveDate>().unwrap() + Duration::days(1);
    assert_eq!(b.day, next.format("%Y-%m-%d").to_string());
    assert!(
        apply(
            &mut doc,
            EditOp::SplitAt {
                route_id: rid,
                index: 0
            }
        )
        .is_err()
    );
}

#[test]
fn reorder_point_with_keyboard() {
    let (mut doc, rid) = route_doc();
    for i in 0..3 {
        add(&mut doc, &rid, 3.10 + f64::from(i) * 0.01, 101.60);
    }
    route_all(&mut doc);
    let sel = apply(
        &mut doc,
        EditOp::ReorderPoint {
            route_id: rid.clone(),
            index: 2,
            delta: -1,
        },
    )
    .unwrap();
    assert_eq!(
        sel,
        Some(Selection::Point {
            route_id: rid.clone(),
            index: 1
        })
    );
    let lats: Vec<f64> = doc
        .route(&rid)
        .unwrap()
        .points
        .iter()
        .map(|p| p.lat)
        .collect();
    assert_eq!(lats, [3.10, 3.12, 3.11]);
    assert_eq!(states(&doc, &rid), [LegState::Pending, LegState::Pending]);
    assert!(
        apply(
            &mut doc,
            EditOp::ReorderPoint {
                route_id: rid,
                index: 0,
                delta: -1
            }
        )
        .is_err()
    );
}

#[test]
fn stale_routing_result_is_discarded() {
    let (mut doc, rid) = route_doc();
    add(&mut doc, &rid, 3.10, 101.60);
    add(&mut doc, &rid, 3.11, 101.60);
    let job = pending_jobs(&doc).remove(0);
    // The user drags the point while the request is in flight.
    apply(
        &mut doc,
        EditOp::MovePoint {
            route_id: rid.clone(),
            index: 1,
            lat: 3.2,
            lng: 101.7,
        },
    )
    .unwrap();
    let out = JobOutput::Routed {
        points: vec![LatLng::new(3.1, 101.6), LatLng::new(3.11, 101.6)],
        distance_m: 1,
        duration_s: 1,
    };
    assert!(!apply_job(&mut doc, &job, Ok(out)));
    assert_eq!(states(&doc, &rid), [LegState::Pending]);
    // A failure marks the leg unresolved (BELUM), with nothing drawn.
    let job = pending_jobs(&doc).remove(0);
    assert!(apply_job(&mut doc, &job, Err("no_route".into())));
    let leg = &doc.route(&rid).unwrap().legs[0];
    assert_eq!((leg.state, leg.geometry.len()), (LegState::Unresolved, 0));
}

#[test]
fn drawn_line_is_matched_and_unmatched_ranges_block_publish() {
    let mut doc = templates::new_event(Template::Ride, KL, event_core::Lang::Ms, now());
    let rid = doc.routes[0].id.clone();
    route_all(&mut doc);
    let drawn: Vec<LatLng> = (0..10)
        .map(|i| LatLng::new(3.2 + f64::from(i) * 0.001, 101.75))
        .collect();
    apply(
        &mut doc,
        EditOp::AddDrawnLeg {
            route_id: rid.clone(),
            points: drawn.clone(),
        },
    )
    .unwrap();
    let jobs = pending_jobs(&doc);
    assert_eq!(jobs.len(), 1);
    let JobKind::Match { points } = &jobs[0].kind else {
        panic!()
    };
    assert_eq!(
        points.len(),
        drawn.len() + 1,
        "starts at the previous point"
    );
    apply_job(
        &mut doc,
        &jobs[0],
        Ok(JobOutput::Matched {
            points: points.clone(),
            distance_m: 900,
            duration_s: 200,
            unmatched: vec![[3, 5]],
        }),
    );
    let (ev, issues) = build_event(&doc, 1, None, now());
    assert!(ev.is_none());
    assert!(
        issues
            .iter()
            .any(|i| i.code == "leg_unmatched" && i.path == "routes[0].legs[2]")
    );
    apply(
        &mut doc,
        EditOp::KeepLegAsSketch {
            route_id: rid.clone(),
            leg_index: 2,
        },
    )
    .unwrap();
    let (ev, issues) = build_event(&doc, 1, None, now());
    let ev = ev.expect("publishable");
    assert_eq!(ev.routes[0].source, event_core::GeometrySource::Sketch);
    assert!(
        issues
            .iter()
            .any(|i| i.code == "route_not_navigable" && i.severity == Severity::Warning)
    );
}

#[test]
fn moving_a_stop_point_moves_the_stop_everywhere() {
    let mut doc = templates::new_event(Template::Trip, KL, event_core::Lang::Ms, now());
    route_all(&mut doc);
    let (r1, r2) = (doc.routes[0].id.clone(), doc.routes[1].id.clone());
    let hotel = doc.routes[0].points[1].stop_id.clone().unwrap();
    assert_eq!(
        doc.routes[1].points[0].stop_id.as_deref(),
        Some(hotel.as_str())
    );
    apply(
        &mut doc,
        EditOp::MovePoint {
            route_id: r1.clone(),
            index: 1,
            lat: 3.5,
            lng: 102.1,
        },
    )
    .unwrap();
    assert_eq!(doc.stop(&hotel).unwrap().pos(), LatLng::new(3.5, 102.1));
    assert_eq!(
        doc.route(&r2).unwrap().points[0].pos(),
        LatLng::new(3.5, 102.1)
    );
    assert_eq!(states(&doc, &r2), [LegState::Pending]);
    // Keyboard nudge of the stop itself (Shift = 100 m).
    apply(
        &mut doc,
        EditOp::NudgeStop {
            id: hotel.clone(),
            east_m: 100.0,
            north_m: 0.0,
        },
    )
    .unwrap();
    assert_eq!(
        doc.route(&r1).unwrap().points[1].pos(),
        doc.stop(&hotel).unwrap().pos()
    );
}

#[test]
fn undo_redo_stack() {
    let (mut doc, rid) = route_doc();
    let mut h = History::default();
    assert!(!h.can_undo());
    for i in 0..3 {
        h.record(doc.clone());
        add(&mut doc, &rid, 3.1 + f64::from(i) * 0.01, 101.6);
    }
    assert_eq!(doc.route(&rid).unwrap().points.len(), 3);
    assert!(h.undo(&mut doc));
    assert!(h.undo(&mut doc));
    assert_eq!(doc.route(&rid).unwrap().points.len(), 1);
    assert!(h.redo(&mut doc));
    assert_eq!(doc.route(&rid).unwrap().points.len(), 2);
    // A new edit clears the redo stack.
    h.record(doc.clone());
    apply(
        &mut doc,
        EditOp::Reverse {
            route_id: rid.clone(),
        },
    )
    .unwrap();
    assert!(!h.can_redo());
    assert!(!h.redo(&mut doc));
    while h.undo(&mut doc) {}
    assert_eq!(doc.route(&rid).unwrap().points.len(), 0);
    // Bounded.
    for _ in 0..(crate::editing::HISTORY_LIMIT + 20) {
        h.record(doc.clone());
    }
    let mut n = 0;
    while h.undo(&mut doc) {
        n += 1;
    }
    assert_eq!(n, crate::editing::HISTORY_LIMIT);
}

#[test]
fn organizer_notes_never_reach_the_pack() {
    let mut doc = templates::new_event(Template::Kenduri, KL, event_core::Lang::Ms, now());
    let mut stop = doc.stops[0].clone();
    stop.organizer_note = "RAHSIA-PENGANJUR kunci pagar 4821".into();
    stop.notes = "Masuk ikut pagar belakang".into();
    apply(&mut doc, EditOp::UpdateStop { stop }).unwrap();
    let (ev, issues) = build_event(&doc, 1, None, now());
    let ev = ev.unwrap_or_else(|| panic!("{issues:?}"));
    let bytes = ev.to_bytes().unwrap();
    let text = String::from_utf8(bytes).unwrap();
    assert!(text.contains("Masuk ikut pagar belakang"));
    assert!(!text.contains("RAHSIA-PENGANJUR"));
    assert!(!text.contains("4821"));
}

#[test]
fn route_legs_span_stops_and_shaping_points_stay_out() {
    let mut doc = templates::new_event(Template::Ride, KL, event_core::Lang::Ms, now());
    let rid = doc.routes[0].id.clone();
    // A shaping point between start and the rest stop.
    apply(
        &mut doc,
        EditOp::InsertPoint {
            route_id: rid.clone(),
            leg_index: 0,
            lat: 3.15,
            lng: 101.70,
        },
    )
    .unwrap();
    route_all(&mut doc);
    let (ev, issues) = build_event(&doc, 1, None, now());
    let ev = ev.unwrap_or_else(|| panic!("{issues:?}"));
    let r = &ev.routes[0];
    assert_eq!(r.stop_ids.len(), 3);
    assert_eq!(r.legs.len(), 2);
    assert_eq!(r.legs[0].distance_m, 2000, "two sub-legs of 1000 m");
    assert_eq!(r.legs[0].duration_s, Some(480));
    assert_eq!(r.source, event_core::GeometrySource::Routed);
    assert_eq!(
        r.ascent_m, None,
        "no elevation data means unavailable, never 0"
    );
    ev.validate().unwrap();
}

#[test]
fn validation_paths_point_at_fields() {
    let mut doc = templates::new_event(Template::Kenduri, KL, event_core::Lang::Ms, now());
    let mut stop = doc.stops[1].clone();
    stop.label = "   ".into();
    apply(&mut doc, EditOp::UpdateStop { stop }).unwrap();
    let (_, issues) = build_event(&doc, 1, None, now());
    assert!(
        issues.iter().any(|i| i.path == "stops[1].label"),
        "{issues:?}"
    );
    let mut m = doc.meta.clone();
    m.start = "esok".into();
    apply(&mut doc, EditOp::SetMeta { meta: m }).unwrap();
    let (_, issues) = build_event(&doc, 1, None, now());
    assert!(issues.iter().any(|i| i.path == "start"));
    // Unresolved legs are reported per leg.
    let doc = templates::new_event(Template::Convoy, KL, event_core::Lang::Ms, now());
    let (ev, issues) = build_event(&doc, 1, None, now());
    assert!(ev.is_none());
    assert_eq!(
        issues.iter().filter(|i| i.code == "leg_unresolved").count(),
        2
    );
}

fn core_with_store() -> Core {
    let mut store = local_store::Store::open_in_memory().unwrap();
    store
        .initialize_with_key(local_store::DataKey::from_bytes([3; 32]))
        .unwrap();
    Core::new(
        store,
        Settings::default(),
        std::env::temp_dir().join("manotu-test-settings.json"),
    )
}

#[test]
fn publish_revisions_link_and_file_round_trip() {
    let mut core = core_with_store();
    let doc = templates::new_event(Template::Kenduri, KL, event_core::Lang::Ms, now());
    let id = doc.id.clone();
    core.open_new(doc, now()).unwrap();

    let preview = core.publish_preview(false, now()).unwrap();
    assert!(preview.ok, "{:?}", preview.issues);
    assert_eq!(preview.rev, 1);
    assert!(preview.fragment_len > 50 && preview.fragment_len < preview.fragment_max);
    assert!(
        !preview
            .event
            .as_ref()
            .unwrap()
            .to_string()
            .contains("organizerNote")
    );

    let Published::Link(link) = core
        .publish(PublishTarget::Link { stops_only: false }, now())
        .unwrap()
    else {
        panic!()
    };
    assert!(link.starts_with("https://www.bukitbunga.com/gimano/a/#1."));
    let opened = event_core::open_link(&link).unwrap();
    assert_eq!((opened.event.rev, opened.event.parent.clone()), (1, None));
    let rev1 = opened.event_bytes.clone();

    // Unchanged content re-seals the same revision.
    let Published::File(file) = core.publish(PublishTarget::File, now()).unwrap() else {
        panic!()
    };
    let code = core.secret_code(now()).unwrap();
    let opened = event_core::open_file(&file, &code).unwrap();
    assert_eq!(opened.event_bytes, rev1);
    assert_eq!(core.ws.as_ref().unwrap().doc.published.len(), 1);

    // An edit publishes rev 2 whose parent is the hash of rev 1 (next_revision).
    let mut m = core.ws.as_ref().unwrap().doc.meta.clone();
    m.title = "Kenduri Aqil & Sofea".into();
    core.edit(EditOp::SetMeta { meta: m }, now()).unwrap();
    let later = now() + Duration::hours(1);
    let preview = core.publish_preview(false, later).unwrap();
    assert_eq!((preview.rev, preview.previous_rev), (2, Some(1)));
    assert!(
        preview
            .changes
            .iter()
            .any(|c| c.what == "event" && c.fields.contains(&"title".to_string()))
    );
    let Published::Link(link) = core
        .publish(PublishTarget::Link { stops_only: false }, later)
        .unwrap()
    else {
        panic!()
    };
    let opened = event_core::open_link(&link).unwrap();
    assert_eq!(opened.event.rev, 2);
    assert_eq!(
        opened.event.parent,
        Some(event_core::revision_hash_b64u(&rev1))
    );
    // The same organizer key signs every revision of this event.
    let first = event_core::open_link(&link).unwrap().organizer_pub;
    assert_eq!(opened.organizer_pub, first);

    // Drafts survive a reload from the encrypted store.
    core.close();
    let v = core.open(crate::core::KIND_EVENT, &id, later).unwrap();
    assert_eq!(v.doc.meta.title, "Kenduri Aqil & Sofea");
    assert_eq!(v.next_rev, 3);
    assert!(
        v.doc.published.iter().all(|p| p.event_bytes.is_empty()),
        "bytes stay in Rust"
    );
}

#[test]
fn import_link_then_newer_revision() {
    // Organizer side.
    let mut org = core_with_store();
    org.open_new(
        templates::new_event(Template::Dinner, KL, event_core::Lang::Ms, now()),
        now(),
    )
    .unwrap();
    let Published::Link(l1) = org
        .publish(PublishTarget::Link { stops_only: false }, now())
        .unwrap()
    else {
        panic!()
    };
    let mut m = org.ws.as_ref().unwrap().doc.meta.clone();
    m.description = "Pakaian: batik".into();
    org.edit(EditOp::SetMeta { meta: m }, now()).unwrap();
    let Published::Link(l2) = org
        .publish(PublishTarget::Link { stops_only: false }, now())
        .unwrap()
    else {
        panic!()
    };

    // Another desktop imports.
    let mut guest = core_with_store();
    let p = guest
        .import_preview(event_core::open_link(&l1).unwrap(), now())
        .unwrap();
    assert_eq!(p.decision, "new");
    assert_eq!(p.safety_code.len(), 6);
    guest.import_confirm(now()).unwrap();
    let p = guest
        .import_preview(event_core::open_link(&l1).unwrap(), now())
        .unwrap();
    assert_eq!((p.decision, p.can_import), ("already", false));
    let p = guest
        .import_preview(event_core::open_link(&l2).unwrap(), now())
        .unwrap();
    assert_eq!((p.decision, p.parent_check), ("newer", Some("matches")));
    guest.import_confirm(now()).unwrap();
    // Rev 1 is still stored, so re-opening its link is "already imported", not a downgrade.
    let p = guest
        .import_preview(event_core::open_link(&l1).unwrap(), now())
        .unwrap();
    assert_eq!(p.decision, "already");

    let lib = guest.library(now()).unwrap();
    assert_eq!(lib.len(), 1);
    assert!(lib[0].imported);
    assert_eq!(lib[0].published_rev, Some(2));
    // Read-only, and expired imports disappear.
    let v = guest
        .open(crate::core::KIND_IMPORTED, &lib[0].id, now())
        .unwrap();
    assert!(v.readonly);
    assert!(guest.edit(EditOp::AddRoute { mode: None }, now()).is_err());
    let after_expiry = now() + Duration::days(30);
    assert_eq!(guest.after_unlock(after_expiry).unwrap(), 1);
    assert!(guest.library(after_expiry).unwrap().is_empty());
}

#[test]
fn recovery_payload_restores_drafts_and_keys() {
    let mut a = core_with_store();
    a.open_new(
        templates::new_event(Template::Company, KL, event_core::Lang::En, now()),
        now(),
    )
    .unwrap();
    let Published::Link(l1) = a
        .publish(PublishTarget::Link { stops_only: false }, now())
        .unwrap()
    else {
        panic!()
    };
    let payload = a.recovery_payload(now()).unwrap();
    let sealed = local_store::seal_recovery(
        &payload,
        "pemulihan rahsia",
        local_store::KdfParams::INSECURE_FOR_TESTS,
    )
    .unwrap();

    let mut b = core_with_store();
    let plain = local_store::open_recovery(&sealed, "pemulihan rahsia").unwrap();
    assert_eq!(b.recovery_restore(&plain, now()).unwrap(), 1);
    assert_eq!(
        b.recovery_restore(&plain, now()).unwrap(),
        0,
        "no duplicates"
    );
    let id = b.library(now()).unwrap()[0].id.clone();
    b.open(crate::core::KIND_EVENT, &id, now()).unwrap();
    let mut m = b.ws.as_ref().unwrap().doc.meta.clone();
    m.title = "Moved to a new laptop".into();
    b.edit(EditOp::SetMeta { meta: m }, now()).unwrap();
    let Published::Link(l2) = b
        .publish(PublishTarget::Link { stops_only: false }, now())
        .unwrap()
    else {
        panic!()
    };
    let (o1, o2) = (
        event_core::open_link(&l1).unwrap(),
        event_core::open_link(&l2).unwrap(),
    );
    assert_eq!(
        o1.organizer_pub, o2.organizer_pub,
        "same organizer key after recovery"
    );
    assert_eq!(o2.event.rev, 2);
}

#[test]
fn gpx_import_keeps_geometry_or_waits_for_snap() {
    let gpx = br#"<?xml version="1.0"?><gpx version="1.1" creator="t" xmlns="http://www.topografix.com/GPX/1/1">
      <wpt lat="3.1" lon="101.6"><name>Kedai</name></wpt>
      <trk><name>Pagi</name><trkseg>
        <trkpt lat="3.100" lon="101.600"><ele>10</ele><time>2026-01-01T00:00:00Z</time></trkpt>
        <trkpt lat="3.101" lon="101.601"><ele>30</ele><time>2026-01-01T00:01:00Z</time></trkpt>
        <trkpt lat="3.102" lon="101.602"><ele>20</ele><time>2026-01-01T00:02:00Z</time></trkpt>
      </trkseg></trk></gpx>"#;
    let parsed = route_core::parse_gpx(gpx, &route_core::GpxLimits::default()).unwrap();
    let mut core = core_with_store();
    let preview = core.gpx_loaded(parsed.clone());
    assert_eq!(preview.paths[0].point_count, 3);
    assert!(preview.has_timestamps);
    let v = core
        .gpx_apply(
            crate::gpx_io::GpxApply {
                path_index: 0,
                mode: TravelMode::Bicycle,
                snap: false,
                strip_timestamps: true,
                waypoints_as_stops: true,
            },
            now(),
        )
        .unwrap();
    let r = &v.doc.routes[0];
    assert_eq!(r.legs[0].state, LegState::Gpx);
    assert!(r.legs[0].times.is_none(), "timestamps stripped");
    assert_eq!(v.routes[0].ascent_m, Some(20));
    assert_eq!(v.routes[0].descent_m, Some(10));
    assert_eq!(v.doc.stops[0].label, "Kedai");

    // GPX export (plaintext) never carries organizer notes or times unless asked.
    let req = crate::gpx_io::GpxExportRequest {
        acknowledged_plaintext: true,
        include_stops: true,
        include_elevation: true,
        include_timestamps: false,
    };
    let text = crate::gpx_io::export(&v.doc, r, &req).unwrap();
    assert!(
        text.contains("<ele>30</ele>") || text.contains("<ele>30.0</ele>"),
        "{text}"
    );
    assert!(!text.contains("<time>"));

    core.gpx_loaded(parsed);
    let v = core
        .gpx_apply(
            crate::gpx_io::GpxApply {
                path_index: 0,
                mode: TravelMode::Bicycle,
                snap: true,
                strip_timestamps: true,
                waypoints_as_stops: false,
            },
            now(),
        )
        .unwrap();
    assert_eq!(v.doc.routes.len(), 2, "added to the open route document");
    assert_eq!(v.doc.routes[1].legs[0].state, LegState::Pending);
    assert!(matches!(
        pending_jobs(&v.doc).last().unwrap().kind,
        JobKind::Match { .. }
    ));
}

#[test]
fn template_events_are_valid_once_routed() {
    for t in [
        Template::Kenduri,
        Template::Dinner,
        Template::Company,
        Template::Ride,
        Template::Convoy,
        Template::Trip,
        Template::Other,
    ] {
        let mut doc = templates::new_event(t, KL, event_core::Lang::Ms, now());
        route_all(&mut doc);
        let (ev, issues) = build_event(&doc, 1, None, now());
        assert!(ev.is_some(), "{t:?}: {issues:?}");
        assert!(doc.stops.iter().all(|s| s.kind != StopKind::Other) || t == Template::Other);
    }
    let _ = publish::EventSecrets::generate().unwrap();
}

#[test]
fn stops_only_link_is_the_same_revision_and_the_file_upgrades_it() {
    let mut org = core_with_store();
    let mut doc = templates::new_event(Template::Ride, KL, event_core::Lang::Ms, now());
    route_all(&mut doc);
    org.open_new(doc, now()).unwrap();

    let full = org.publish_preview(false, now()).unwrap();
    assert!(full.ok, "{:?}", full.issues);
    assert!(!full.stops_only);
    // With this short template route the `variant` field outweighs the polyline it removes;
    // only the lengths' presence matters here.
    let short = full.stops_only_fragment_len.expect("the ride has routes");
    assert!(short > 50 && full.full_fragment_len > 50);
    let preview = org.publish_preview(true, now()).unwrap();
    assert!(preview.stops_only);
    assert_eq!(preview.fragment_len, short);
    assert_eq!(preview.full_fragment_len, full.full_fragment_len);
    let shown = preview.event.unwrap();
    assert_eq!(shown["variant"]["kind"], "stops-only");
    assert!(shown["routes"][0].get("polyline").is_none());
    assert!(shown["routes"][0]["distanceM"].as_u64().unwrap() > 0);

    // The stops-only link carries rev 1; the draft records the full bytes as rev 1.
    let Published::Link(link) = org
        .publish(PublishTarget::Link { stops_only: true }, now())
        .unwrap()
    else {
        panic!()
    };
    let variant = event_core::open_link(&link).unwrap();
    assert!(variant.event.is_stops_only());
    assert_eq!(variant.event.rev, 1);
    let published = &org.ws.as_ref().unwrap().doc.published;
    assert_eq!(published.len(), 1);
    assert_eq!(
        published[0].hash,
        event_core::b64u(&variant.revision_hash),
        "the variant's revision hash is the recorded full revision"
    );
    // The file of the same revision carries the full event.
    let Published::File(file) = org.publish(PublishTarget::File, now()).unwrap() else {
        panic!()
    };
    let code = org.secret_code(now()).unwrap();
    let full_pack = event_core::open_file(&file, &code).unwrap();
    assert!(!full_pack.event.is_stops_only());
    assert_eq!(full_pack.event.rev, 1);
    assert!(full_pack.event.routes.iter().all(|r| r.polyline.is_some()));
    assert_eq!(full_pack.revision_hash, variant.revision_hash);
    assert_eq!(org.ws.as_ref().unwrap().doc.published.len(), 1);

    // A guest opens the short link first, then the file: the full route is added silently.
    let mut guest = core_with_store();
    let p = guest.import_preview(variant.clone(), now()).unwrap();
    assert_eq!((p.decision, p.stops_only), ("new", true));
    guest.import_confirm(now()).unwrap();
    let id = variant.event.id.clone();
    let v = guest.open(crate::core::KIND_IMPORTED, &id, now()).unwrap();
    assert!(
        v.doc.routes.iter().all(|r| r.legs.is_empty()),
        "no geometry yet"
    );
    guest.close();

    let p = guest.import_preview(full_pack.clone(), now()).unwrap();
    assert_eq!(
        (p.decision, p.can_import, p.stops_only),
        ("upgraded", false, false)
    );
    let v = guest.open(crate::core::KIND_IMPORTED, &id, now()).unwrap();
    assert!(
        v.doc.routes.iter().all(|r| !r.legs.is_empty()),
        "full route added"
    );
    guest.close();
    // Either one again: already imported.
    for again in [variant, full_pack] {
        let p = guest.import_preview(again, now()).unwrap();
        assert_eq!((p.decision, p.can_import), ("already", false));
    }
    assert_eq!(guest.library(now()).unwrap().len(), 1);
}
