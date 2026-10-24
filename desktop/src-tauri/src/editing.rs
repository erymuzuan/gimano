//! Pure editing operations on a [`Doc`] and the undo/redo history.
//!
//! Rules (docs/design/festival/spec.md, route builder):
//! - An edit only invalidates the legs it touches ("recompute affected legs only").
//! - An invalidated leg is `Pending` until routed; it is never drawn as a straight line unless
//!   "Ikut jalan" is off, in which case it becomes an explicit `Sketch`.
//! - Stop order is never changed implicitly.

use std::sync::OnceLock;
use std::sync::atomic::{AtomicU64, Ordering};

use chrono::{DateTime, FixedOffset, NaiveDate};
use event_core::{Session, StopKind, TravelMode, new_item_id};
use route_core::geo::round6;
use route_core::{LatLng, haversine_m, path_length_m};
use serde::{Deserialize, Serialize};

use crate::doc::{Doc, EventMeta, Leg, LegState, RouteDraft, RoutePoint, StopDraft};

static GEN: AtomicU64 = AtomicU64::new(0);
static GEN_BASE: OnceLock<u64> = OnceLock::new();

/// Unique across restarts (time-based base), so a stale routing result never matches.
pub fn next_generation() -> u64 {
    let base = *GEN_BASE.get_or_init(|| {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(1, |d| d.as_micros() as u64)
    });
    base + GEN.fetch_add(1, Ordering::Relaxed)
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "op", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum EditOp {
    SetMeta {
        meta: EventMeta,
    },
    AddStop {
        lat: f64,
        lng: f64,
        #[serde(default)]
        kind: Option<StopKind>,
        #[serde(default)]
        label: Option<String>,
    },
    UpdateStop {
        stop: StopDraft,
    },
    DeleteStop {
        id: String,
    },
    ReorderStop {
        id: String,
        delta: i32,
    },
    AddSession {
        title: String,
        start: String,
        end: String,
        #[serde(default)]
        stop_id: Option<String>,
    },
    UpdateSession {
        session: Session,
    },
    DeleteSession {
        id: String,
    },
    AddRoute {
        #[serde(default)]
        mode: Option<TravelMode>,
    },
    UpdateRoute {
        route_id: String,
        title: String,
        day: String,
        mode: TravelMode,
        follow_roads: bool,
    },
    DeleteRoute {
        route_id: String,
    },
    AddPoint {
        route_id: String,
        lat: f64,
        lng: f64,
        #[serde(default)]
        stop_id: Option<String>,
    },
    InsertPoint {
        route_id: String,
        leg_index: usize,
        lat: f64,
        lng: f64,
    },
    MovePoint {
        route_id: String,
        index: usize,
        lat: f64,
        lng: f64,
    },
    NudgePoint {
        route_id: String,
        index: usize,
        east_m: f64,
        north_m: f64,
    },
    NudgeStop {
        id: String,
        east_m: f64,
        north_m: f64,
    },
    DeletePoint {
        route_id: String,
        index: usize,
    },
    ReorderPoint {
        route_id: String,
        index: usize,
        delta: i32,
    },
    /// Turns a shaping point into a stop (creating the stop) or a stop point back into a
    /// shaping point (the stop itself stays in the event).
    SetPointStop {
        route_id: String,
        index: usize,
        stop: bool,
    },
    Reverse {
        route_id: String,
    },
    CloseLoop {
        route_id: String,
    },
    OutAndBack {
        route_id: String,
    },
    SplitAt {
        route_id: String,
        index: usize,
    },
    AddDrawnLeg {
        route_id: String,
        points: Vec<LatLng>,
    },
    KeepLegAsSketch {
        route_id: String,
        leg_index: usize,
    },
    RetryLeg {
        route_id: String,
        leg_index: usize,
    },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(
    tag = "type",
    rename_all = "camelCase",
    rename_all_fields = "camelCase"
)]
pub enum Selection {
    Event,
    Stop { id: String },
    Session { id: String },
    Route { route_id: String },
    Point { route_id: String, index: usize },
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum EditError {
    #[error("not found: {0}")]
    NotFound(&'static str),
    #[error("not possible: {0}")]
    NotPossible(&'static str),
    #[error("invalid value: {0}")]
    Invalid(&'static str),
    #[error("this event was imported and is read-only")]
    ReadOnly,
}

type R<T> = Result<T, EditError>;

fn id() -> String {
    new_item_id().unwrap_or_else(|_| format!("p{}", next_generation()))
}

fn check_pos(lat: f64, lng: f64) -> R<LatLng> {
    let p = LatLng::new(lat, lng);
    if p.is_valid() {
        Ok(p.rounded6())
    } else {
        Err(EditError::Invalid("coordinate out of range"))
    }
}

/// Moves a position by metres east/north (local flat approximation; fine for 10–100 m nudges).
pub fn offset(p: LatLng, east_m: f64, north_m: f64) -> LatLng {
    const M_PER_DEG: f64 = 111_320.0;
    let lat = p.lat + north_m / M_PER_DEG;
    let lng = p.lng + east_m / (M_PER_DEG * p.lat.to_radians().cos().max(0.01));
    LatLng::new(
        round6(lat.clamp(-90.0, 90.0)),
        round6(lng.clamp(-180.0, 180.0)),
    )
}

fn straight(a: LatLng, b: LatLng, generation: u64) -> Leg {
    let mut l = Leg::pending(generation);
    l.state = LegState::Sketch;
    l.geometry = vec![a, b];
    l.distance_m = haversine_m(a, b).round() as u32;
    l
}

impl RouteDraft {
    pub fn new(mode: TravelMode) -> Self {
        RouteDraft {
            id: id(),
            title: String::new(),
            mode,
            day: String::new(),
            follow_roads: true,
            points: Vec::new(),
            legs: Vec::new(),
        }
    }

    /// Invalidates leg `i` (keeps a drawn line so it is matched again).
    pub fn invalidate(&mut self, i: usize) {
        if let Some(leg) = self.legs.get_mut(i) {
            let drawn = leg.drawn.take();
            *leg = Leg::pending(next_generation());
            leg.drawn = drawn;
        }
    }

    /// Invalidates the legs touching point `i`, dropping drawn lines whose ends moved.
    fn invalidate_around(&mut self, i: usize) {
        for li in [i.wrapping_sub(1), i] {
            if let Some(leg) = self.legs.get_mut(li) {
                leg.drawn = None;
            }
            self.invalidate(li);
        }
    }

    /// Without "Ikut jalan", pending legs become explicit straight or drawn sketches.
    pub fn settle_offline_legs(&mut self) {
        if self.follow_roads {
            return;
        }
        for i in 0..self.legs.len() {
            if self.legs[i].state != LegState::Pending {
                continue;
            }
            let (a, b) = (self.points[i].pos(), self.points[i + 1].pos());
            let generation = self.legs[i].generation;
            self.legs[i] = match self.legs[i].drawn.take() {
                Some(d) if d.len() >= 2 => {
                    let mut l = straight(a, b, generation);
                    l.distance_m = path_length_m(&d).round() as u32;
                    l.geometry = d.clone();
                    l.drawn = Some(d);
                    l
                }
                _ => straight(a, b, generation),
            };
        }
    }

    fn check_index(&self, i: usize) -> R<()> {
        if i < self.points.len() {
            Ok(())
        } else {
            Err(EditError::NotFound("point"))
        }
    }

    fn push_point(&mut self, p: RoutePoint) {
        if !self.points.is_empty() {
            self.legs.push(Leg::pending(next_generation()));
        }
        self.points.push(p);
    }

    pub fn is_consistent(&self) -> bool {
        self.legs.len() == self.points.len().saturating_sub(1)
    }
}

fn reverse_leg(leg: &mut Leg) {
    match leg.state {
        // Directional restrictions: a reversed road route must be recalculated.
        LegState::Routed | LegState::Matched | LegState::Pending | LegState::Unresolved => {
            *leg = Leg::pending(next_generation());
        }
        LegState::Sketch | LegState::Gpx => {
            leg.geometry.reverse();
            if let Some(e) = &mut leg.elevation {
                e.reverse();
            }
            if let Some(t) = &mut leg.times {
                t.reverse();
            }
            if let Some(d) = &mut leg.drawn {
                d.reverse();
            }
            leg.unmatched.clear();
        }
    }
}

fn next_day(day: &str) -> String {
    NaiveDate::parse_from_str(day, "%Y-%m-%d")
        .ok()
        .and_then(|d| d.succ_opt())
        .map(|d| d.format("%Y-%m-%d").to_string())
        .unwrap_or_default()
}

fn parse_time(s: &str) -> R<DateTime<FixedOffset>> {
    DateTime::parse_from_rfc3339(s).map_err(|_| EditError::Invalid("date/time is not RFC 3339"))
}

/// Keeps every route point linked to `stop_id` on the stop's pin.
fn sync_stop_points(doc: &mut Doc, stop_id: &str) {
    let Some(pos) = doc.stop(stop_id).map(StopDraft::pos) else {
        return;
    };
    for r in &mut doc.routes {
        for i in 0..r.points.len() {
            if r.points[i].stop_id.as_deref() == Some(stop_id) && r.points[i].pos() != pos {
                r.points[i].lat = pos.lat;
                r.points[i].lng = pos.lng;
                r.invalidate_around(i);
            }
        }
        r.settle_offline_legs();
    }
}

fn route_mut<'a>(doc: &'a mut Doc, route_id: &str) -> R<&'a mut RouteDraft> {
    doc.route_mut(route_id).ok_or(EditError::NotFound("route"))
}

fn stop_label(doc: &Doc) -> String {
    format!("Hentian {}", doc.stops.len() + 1)
}

/// Applies one edit. Returns what the UI should select afterwards.
pub fn apply(doc: &mut Doc, op: EditOp) -> R<Option<Selection>> {
    if doc.imported.is_some() {
        return Err(EditError::ReadOnly);
    }
    let sel = match op {
        EditOp::SetMeta { meta } => {
            doc.meta = meta;
            Some(Selection::Event)
        }
        EditOp::AddStop {
            lat,
            lng,
            kind,
            label,
        } => {
            let p = check_pos(lat, lng)?;
            let stop = StopDraft {
                id: id(),
                kind: kind.unwrap_or(StopKind::Other),
                label: label.unwrap_or_else(|| stop_label(doc)),
                lat: p.lat,
                lng: p.lng,
                entrance: None,
                arrive_from: String::new(),
                arrive_to: String::new(),
                dwell_min: None,
                notes: String::new(),
                audience: String::new(),
                organizer_note: String::new(),
            };
            let sid = stop.id.clone();
            doc.stops.push(stop);
            Some(Selection::Stop { id: sid })
        }
        EditOp::UpdateStop { mut stop } => {
            let p = check_pos(stop.lat, stop.lng)?;
            (stop.lat, stop.lng) = (p.lat, p.lng);
            if let Some(e) = stop.entrance {
                stop.entrance = Some(check_pos(e.lat, e.lng)?);
            }
            let slot = doc
                .stops
                .iter_mut()
                .find(|s| s.id == stop.id)
                .ok_or(EditError::NotFound("stop"))?;
            let sid = stop.id.clone();
            *slot = stop;
            sync_stop_points(doc, &sid);
            Some(Selection::Stop { id: sid })
        }
        EditOp::NudgeStop {
            id,
            east_m,
            north_m,
        } => {
            let s = doc
                .stops
                .iter_mut()
                .find(|s| s.id == id)
                .ok_or(EditError::NotFound("stop"))?;
            let p = offset(s.pos(), east_m, north_m);
            (s.lat, s.lng) = (p.lat, p.lng);
            sync_stop_points(doc, &id);
            Some(Selection::Stop { id })
        }
        EditOp::DeleteStop { id } => {
            let before = doc.stops.len();
            doc.stops.retain(|s| s.id != id);
            if doc.stops.len() == before {
                return Err(EditError::NotFound("stop"));
            }
            for r in &mut doc.routes {
                for p in &mut r.points {
                    if p.stop_id.as_deref() == Some(id.as_str()) {
                        p.stop_id = None;
                    }
                }
            }
            for s in &mut doc.sessions {
                if s.stop_id.as_deref() == Some(id.as_str()) {
                    s.stop_id = None;
                }
            }
            Some(Selection::Event)
        }
        EditOp::ReorderStop { id, delta } => {
            let i = doc
                .stops
                .iter()
                .position(|s| s.id == id)
                .ok_or(EditError::NotFound("stop"))?;
            let j = i as i64 + i64::from(delta);
            if j < 0 || j >= doc.stops.len() as i64 {
                return Err(EditError::NotPossible("already at the end"));
            }
            let s = doc.stops.remove(i);
            doc.stops.insert(j as usize, s);
            Some(Selection::Stop { id })
        }
        EditOp::AddSession {
            title,
            start,
            end,
            stop_id,
        } => {
            let session = Session {
                id: id(),
                title,
                start: parse_time(&start)?,
                end: parse_time(&end)?,
                stop_id,
            };
            let sid = session.id.clone();
            doc.sessions.push(session);
            doc.sessions.sort_by_key(|s| s.start);
            Some(Selection::Session { id: sid })
        }
        EditOp::UpdateSession { session } => {
            let slot = doc
                .sessions
                .iter_mut()
                .find(|s| s.id == session.id)
                .ok_or(EditError::NotFound("session"))?;
            let sid = session.id.clone();
            *slot = session;
            doc.sessions.sort_by_key(|s| s.start);
            Some(Selection::Session { id: sid })
        }
        EditOp::DeleteSession { id } => {
            doc.sessions.retain(|s| s.id != id);
            Some(Selection::Event)
        }
        EditOp::AddRoute { mode } => {
            let mut r = RouteDraft::new(mode.unwrap_or(TravelMode::Car));
            r.title = format!("Laluan {}", doc.routes.len() + 1);
            let rid = r.id.clone();
            doc.routes.push(r);
            Some(Selection::Route { route_id: rid })
        }
        EditOp::UpdateRoute {
            route_id,
            title,
            day,
            mode,
            follow_roads,
        } => {
            let r = route_mut(doc, &route_id)?;
            r.title = title;
            r.day = day;
            let mode_changed = r.mode != mode;
            let follow_changed = r.follow_roads != follow_roads;
            r.mode = mode;
            r.follow_roads = follow_roads;
            for i in 0..r.legs.len() {
                let st = r.legs[i].state;
                let recompute = match st {
                    // A bicycle route is not a motorcycle route. Road legs already found stay
                    // when "Ikut jalan" is switched off; only new legs become sketches.
                    LegState::Routed | LegState::Matched => mode_changed,
                    LegState::Unresolved => mode_changed || follow_changed,
                    // Turning "Ikut jalan" on routes straight sketches again; drawn sketches and
                    // GPX geometry stay until the user asks.
                    LegState::Sketch => follow_changed && follow_roads && r.legs[i].drawn.is_none(),
                    LegState::Gpx => false,
                    LegState::Pending => true,
                };
                if recompute {
                    r.invalidate(i);
                }
            }
            r.settle_offline_legs();
            Some(Selection::Route { route_id })
        }
        EditOp::DeleteRoute { route_id } => {
            doc.routes.retain(|r| r.id != route_id);
            Some(Selection::Event)
        }
        EditOp::AddPoint {
            route_id,
            lat,
            lng,
            stop_id,
        } => {
            let mut p = check_pos(lat, lng)?;
            if let Some(sid) = &stop_id {
                p = doc.stop(sid).ok_or(EditError::NotFound("stop"))?.pos();
            }
            let r = route_mut(doc, &route_id)?;
            r.push_point(RoutePoint {
                id: id(),
                lat: p.lat,
                lng: p.lng,
                stop_id,
            });
            r.settle_offline_legs();
            Some(Selection::Point {
                index: r.points.len() - 1,
                route_id,
            })
        }
        EditOp::InsertPoint {
            route_id,
            leg_index,
            lat,
            lng,
        } => {
            let p = check_pos(lat, lng)?;
            let r = route_mut(doc, &route_id)?;
            if leg_index >= r.legs.len() {
                return Err(EditError::NotFound("leg"));
            }
            r.points.insert(
                leg_index + 1,
                RoutePoint {
                    id: id(),
                    lat: p.lat,
                    lng: p.lng,
                    stop_id: None,
                },
            );
            r.legs[leg_index] = Leg::pending(next_generation());
            r.legs
                .insert(leg_index + 1, Leg::pending(next_generation()));
            r.settle_offline_legs();
            Some(Selection::Point {
                route_id,
                index: leg_index + 1,
            })
        }
        EditOp::MovePoint {
            route_id,
            index,
            lat,
            lng,
        } => {
            let p = check_pos(lat, lng)?;
            move_point(doc, &route_id, index, p)?;
            Some(Selection::Point { route_id, index })
        }
        EditOp::NudgePoint {
            route_id,
            index,
            east_m,
            north_m,
        } => {
            let r = route_mut(doc, &route_id)?;
            r.check_index(index)?;
            let p = offset(r.points[index].pos(), east_m, north_m);
            move_point(doc, &route_id, index, p)?;
            Some(Selection::Point { route_id, index })
        }
        EditOp::DeletePoint { route_id, index } => {
            let r = route_mut(doc, &route_id)?;
            r.check_index(index)?;
            r.points.remove(index);
            if !r.legs.is_empty() {
                if index == 0 {
                    r.legs.remove(0);
                } else if index > r.legs.len() - 1 {
                    r.legs.pop();
                } else {
                    // The two legs around the point merge into one.
                    r.legs.remove(index);
                    r.legs[index - 1].drawn = None;
                    r.invalidate(index - 1);
                }
            }
            r.settle_offline_legs();
            let n = r.points.len();
            if n == 0 {
                Some(Selection::Route { route_id })
            } else {
                Some(Selection::Point {
                    route_id,
                    index: index.min(n - 1),
                })
            }
        }
        EditOp::ReorderPoint {
            route_id,
            index,
            delta,
        } => {
            let r = route_mut(doc, &route_id)?;
            r.check_index(index)?;
            let j = index as i64 + i64::from(delta);
            if delta.abs() != 1 || j < 0 || j >= r.points.len() as i64 {
                return Err(EditError::NotPossible("already at the end"));
            }
            let j = j as usize;
            r.points.swap(index, j);
            let (lo, hi) = (index.min(j), index.max(j));
            r.invalidate_around(lo);
            r.invalidate_around(hi);
            r.settle_offline_legs();
            Some(Selection::Point { route_id, index: j })
        }
        EditOp::SetPointStop {
            route_id,
            index,
            stop,
        } => {
            let label = stop_label(doc);
            let r = route_mut(doc, &route_id)?;
            r.check_index(index)?;
            if !stop {
                r.points[index].stop_id = None;
                return Ok(Some(Selection::Point { route_id, index }));
            }
            if r.points[index].stop_id.is_some() {
                return Ok(Some(Selection::Point { route_id, index }));
            }
            let p = r.points[index].pos();
            let kind = if index == 0 {
                StopKind::Start
            } else if index == r.points.len() - 1 {
                StopKind::Finish
            } else {
                StopKind::Checkpoint
            };
            let sid = id();
            r.points[index].stop_id = Some(sid.clone());
            doc.stops.push(StopDraft {
                id: sid,
                kind,
                label,
                lat: p.lat,
                lng: p.lng,
                entrance: None,
                arrive_from: String::new(),
                arrive_to: String::new(),
                dwell_min: None,
                notes: String::new(),
                audience: String::new(),
                organizer_note: String::new(),
            });
            Some(Selection::Point { route_id, index })
        }
        EditOp::Reverse { route_id } => {
            let r = route_mut(doc, &route_id)?;
            if r.points.len() < 2 {
                return Err(EditError::NotPossible("needs two points"));
            }
            r.points.reverse();
            r.legs.reverse();
            r.legs.iter_mut().for_each(reverse_leg);
            r.settle_offline_legs();
            Some(Selection::Route { route_id })
        }
        EditOp::CloseLoop { route_id } => {
            let r = route_mut(doc, &route_id)?;
            if r.points.len() < 2 {
                return Err(EditError::NotPossible("needs two points"));
            }
            let first = r.points[0].clone();
            if r.points.last().is_some_and(|l| l.pos() == first.pos()) {
                return Err(EditError::NotPossible("already a loop"));
            }
            r.push_point(RoutePoint { id: id(), ..first });
            r.settle_offline_legs();
            Some(Selection::Route { route_id })
        }
        EditOp::OutAndBack { route_id } => {
            let r = route_mut(doc, &route_id)?;
            let n = r.points.len();
            if n < 2 {
                return Err(EditError::NotPossible("needs two points"));
            }
            // A candidate return path: same points back, every new leg routed again.
            for i in (0..n - 1).rev() {
                let p = r.points[i].clone();
                r.push_point(RoutePoint { id: id(), ..p });
            }
            r.settle_offline_legs();
            Some(Selection::Route { route_id })
        }
        EditOp::SplitAt { route_id, index } => {
            let r = route_mut(doc, &route_id)?;
            if index == 0 || index + 1 >= r.points.len() {
                return Err(EditError::NotPossible("split at an inner point"));
            }
            let mut b = RouteDraft::new(r.mode);
            b.follow_roads = r.follow_roads;
            b.title = if r.title.is_empty() {
                String::new()
            } else {
                format!("{} (2)", r.title)
            };
            b.day = next_day(&r.day);
            b.points = r.points.split_off(index + 1);
            b.legs = r.legs.split_off(index);
            let shared = r.points[index].clone();
            b.points.insert(0, RoutePoint { id: id(), ..shared });
            let pos = doc
                .routes
                .iter()
                .position(|x| x.id == route_id)
                .unwrap_or(0);
            let bid = b.id.clone();
            doc.routes.insert(pos + 1, b);
            Some(Selection::Route { route_id: bid })
        }
        EditOp::AddDrawnLeg { route_id, points } => {
            if points.len() < 2 {
                return Err(EditError::Invalid("a drawn line needs two points"));
            }
            let mut pts = Vec::with_capacity(points.len() + 1);
            for p in &points {
                pts.push(check_pos(p.lat, p.lng)?);
            }
            let r = route_mut(doc, &route_id)?;
            if let Some(last) = r.points.last() {
                pts.insert(0, last.pos());
            } else {
                r.points.push(RoutePoint {
                    id: id(),
                    lat: pts[0].lat,
                    lng: pts[0].lng,
                    stop_id: None,
                });
            }
            let end = *pts.last().expect("two points");
            r.push_point(RoutePoint {
                id: id(),
                lat: end.lat,
                lng: end.lng,
                stop_id: None,
            });
            let li = r.legs.len() - 1;
            r.legs[li].drawn = Some(pts);
            r.settle_offline_legs();
            Some(Selection::Point {
                index: r.points.len() - 1,
                route_id,
            })
        }
        EditOp::KeepLegAsSketch {
            route_id,
            leg_index,
        } => {
            let r = route_mut(doc, &route_id)?;
            if leg_index >= r.legs.len() {
                return Err(EditError::NotFound("leg"));
            }
            let (a, b) = (r.points[leg_index].pos(), r.points[leg_index + 1].pos());
            let leg = &mut r.legs[leg_index];
            let geometry = match &leg.drawn {
                Some(d) if d.len() >= 2 => d.clone(),
                _ => vec![a, b],
            };
            leg.state = LegState::Sketch;
            leg.distance_m = path_length_m(&geometry).round() as u32;
            leg.geometry = geometry;
            leg.duration_s = None;
            leg.unmatched.clear();
            leg.problem = None;
            Some(Selection::Route { route_id })
        }
        EditOp::RetryLeg {
            route_id,
            leg_index,
        } => {
            let r = route_mut(doc, &route_id)?;
            if leg_index >= r.legs.len() {
                return Err(EditError::NotFound("leg"));
            }
            r.invalidate(leg_index);
            r.settle_offline_legs();
            Some(Selection::Route { route_id })
        }
    };
    Ok(sel)
}

fn move_point(doc: &mut Doc, route_id: &str, index: usize, p: LatLng) -> R<()> {
    let r = route_mut(doc, route_id)?;
    r.check_index(index)?;
    r.points[index].lat = p.lat;
    r.points[index].lng = p.lng;
    r.invalidate_around(index);
    r.settle_offline_legs();
    // A stop point carries its stop along (and every other route through that stop).
    if let Some(sid) = r.points[index].stop_id.clone() {
        if let Some(s) = doc.stops.iter_mut().find(|s| s.id == sid) {
            (s.lat, s.lng) = (p.lat, p.lng);
        }
        sync_stop_points(doc, &sid);
    }
    Ok(())
}

// ---------------------------------------------------------------------------------------------
// Routing jobs: what must be computed, and applying results without clobbering newer edits.

#[derive(Debug, Clone, PartialEq)]
pub enum JobKind {
    Route { from: LatLng, to: LatLng },
    Match { points: Vec<LatLng> },
}

#[derive(Debug, Clone, PartialEq)]
pub struct LegJob {
    pub route_id: String,
    pub leg_index: usize,
    pub generation: u64,
    pub mode: TravelMode,
    pub kind: JobKind,
}

#[derive(Debug, Clone, PartialEq)]
pub enum JobOutput {
    Routed {
        points: Vec<LatLng>,
        distance_m: u32,
        duration_s: u32,
    },
    Matched {
        points: Vec<LatLng>,
        distance_m: u32,
        duration_s: u32,
        unmatched: Vec<[usize; 2]>,
    },
}

/// Every pending leg of every route that follows roads.
pub fn pending_jobs(doc: &Doc) -> Vec<LegJob> {
    let mut jobs = Vec::new();
    for r in doc.routes.iter().filter(|r| r.follow_roads) {
        for (i, leg) in r.legs.iter().enumerate() {
            if leg.state != LegState::Pending {
                continue;
            }
            let kind = match &leg.drawn {
                Some(d) if d.len() >= 2 => JobKind::Match {
                    points: thin(d, route_core::routing::MATCH_MAX_POINTS),
                },
                _ => JobKind::Route {
                    from: r.points[i].pos(),
                    to: r.points[i + 1].pos(),
                },
            };
            jobs.push(LegJob {
                route_id: r.id.clone(),
                leg_index: i,
                generation: leg.generation,
                mode: r.mode,
                kind,
            });
        }
    }
    jobs
}

/// Keeps at most `max` points, always including both ends.
pub fn thin(points: &[LatLng], max: usize) -> Vec<LatLng> {
    if points.len() <= max || max < 2 {
        return points.to_vec();
    }
    let step = (points.len() - 1) as f64 / (max - 1) as f64;
    (0..max)
        .map(|k| points[((k as f64 * step).round() as usize).min(points.len() - 1)])
        .collect()
}

/// Applies a routing result if the leg has not been edited since the job was created.
/// Returns whether anything changed.
pub fn apply_job(doc: &mut Doc, job: &LegJob, result: Result<JobOutput, String>) -> bool {
    let Some(r) = doc.route_mut(&job.route_id) else {
        return false;
    };
    let Some(leg) = r.legs.get_mut(job.leg_index) else {
        return false;
    };
    if leg.generation != job.generation || leg.state != LegState::Pending {
        return false;
    }
    match result {
        Ok(JobOutput::Routed {
            points,
            distance_m,
            duration_s,
        }) if points.len() >= 2 => {
            leg.state = LegState::Routed;
            leg.geometry = points;
            leg.distance_m = distance_m;
            leg.duration_s = Some(duration_s);
            leg.problem = None;
        }
        Ok(JobOutput::Matched {
            points,
            distance_m,
            duration_s,
            unmatched,
        }) if points.len() >= 2 => {
            leg.state = LegState::Matched;
            leg.geometry = points;
            leg.distance_m = distance_m;
            leg.duration_s = Some(duration_s);
            leg.unmatched = unmatched;
            leg.problem = None;
        }
        Ok(_) => {
            leg.state = LegState::Unresolved;
            leg.problem = Some("no_route".into());
        }
        Err(code) => {
            leg.state = LegState::Unresolved;
            leg.problem = Some(code);
        }
    }
    true
}

// ---------------------------------------------------------------------------------------------
// Undo / redo: whole-document snapshots (documents are small).

#[derive(Debug, Default)]
pub struct History {
    undo: Vec<Doc>,
    redo: Vec<Doc>,
}

pub const HISTORY_LIMIT: usize = 100;

impl History {
    /// Call with the document as it was *before* an edit.
    pub fn record(&mut self, before: Doc) {
        self.undo.push(before);
        if self.undo.len() > HISTORY_LIMIT {
            self.undo.remove(0);
        }
        self.redo.clear();
    }

    pub fn undo(&mut self, current: &mut Doc) -> bool {
        match self.undo.pop() {
            Some(prev) => {
                self.redo.push(std::mem::replace(current, prev));
                true
            }
            None => false,
        }
    }

    pub fn redo(&mut self, current: &mut Doc) -> bool {
        match self.redo.pop() {
            Some(next) => {
                self.undo.push(std::mem::replace(current, next));
                true
            }
            None => false,
        }
    }

    pub fn can_undo(&self) -> bool {
        !self.undo.is_empty()
    }

    pub fn can_redo(&self) -> bool {
        !self.redo.is_empty()
    }
}
