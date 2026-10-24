//! Draft → guest-visible `event_core::Event`, validation issues, route summaries and the change
//! summary against the previous revision. Organizer-only notes are never read here.

use std::collections::BTreeMap;

use chrono::{DateTime, FixedOffset, Utc};
use event_core::{
    Event, GeometrySource, Organizer, Route, RouteLeg, Stop, TimeWindow, default_expiry,
};
use route_core::{LatLng, ascent_descent, encode_polyline};
use serde::Serialize;
use serde_json::Value;

use crate::doc::{Doc, DocKind, Leg, LegState, RouteDraft};
use crate::editing::thin;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Severity {
    Error,
    Warning,
}

/// A problem shown in "Semakan" and next to the field at `path` (event-core paths such as
/// `stops[3].label`, plus desktop checks such as `routes[0].legs`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Issue {
    pub path: String,
    pub code: String,
    pub severity: Severity,
    pub message: String,
}

impl Issue {
    pub(crate) fn error(path: impl Into<String>, code: &str, message: impl Into<String>) -> Self {
        Issue {
            path: path.into(),
            code: code.into(),
            severity: Severity::Error,
            message: message.into(),
        }
    }

    fn warning(path: impl Into<String>, code: &str, message: impl Into<String>) -> Self {
        Issue {
            severity: Severity::Warning,
            ..Issue::error(path, code, message)
        }
    }
}

fn time(path: &str, s: &str, issues: &mut Vec<Issue>) -> Option<DateTime<FixedOffset>> {
    match DateTime::parse_from_rfc3339(s.trim()) {
        Ok(t) => Some(t),
        Err(_) => {
            let msg = if s.trim().is_empty() {
                "is required"
            } else {
                "is not a valid date and time"
            };
            issues.push(Issue::error(path, "date", msg));
            None
        }
    }
}

fn opt(s: &str) -> Option<String> {
    let t = s.trim();
    (!t.is_empty()).then(|| t.to_owned())
}

/// Totals for one route as shown in the inspector.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RouteSummary {
    pub distance_m: u32,
    /// Movement time; `None` unless every leg has an estimate.
    pub duration_s: Option<u32>,
    pub unresolved_legs: usize,
    pub ascent_m: Option<u32>,
    pub descent_m: Option<u32>,
    pub source: Option<GeometrySource>,
    pub navigable: bool,
    /// `[distance along route m, elevation m]` when every point has elevation.
    pub profile: Vec<[f64; 2]>,
}

fn leg_source(l: &Leg) -> Option<GeometrySource> {
    match l.state {
        LegState::Routed => Some(GeometrySource::Routed),
        LegState::Matched if l.unmatched.is_empty() => Some(GeometrySource::Routed),
        LegState::Matched | LegState::Sketch => Some(GeometrySource::Sketch),
        LegState::Gpx => Some(GeometrySource::Gpx),
        LegState::Pending | LegState::Unresolved => None,
    }
}

fn combine(sources: impl Iterator<Item = Option<GeometrySource>>) -> Option<GeometrySource> {
    let mut out = GeometrySource::Routed;
    for s in sources {
        match s? {
            GeometrySource::Gpx => out = GeometrySource::Gpx,
            GeometrySource::Sketch if out != GeometrySource::Gpx => out = GeometrySource::Sketch,
            _ => {}
        }
    }
    Some(out)
}

fn joined_geometry(legs: &[Leg]) -> (Vec<LatLng>, Option<Vec<Option<f64>>>) {
    let mut pts: Vec<LatLng> = Vec::new();
    let mut ele: Option<Vec<Option<f64>>> = Some(Vec::new());
    for l in legs.iter().filter(|l| l.is_resolved()) {
        let skip = usize::from(pts.last().is_some_and(|p| l.geometry.first() == Some(p)));
        pts.extend(l.geometry.iter().skip(skip).map(LatLng::rounded6));
        match (&mut ele, &l.elevation) {
            (Some(e), Some(le)) if le.len() == l.geometry.len() => e.extend(le.iter().skip(skip)),
            _ => ele = None,
        }
    }
    (pts, ele.filter(|e| !e.is_empty()))
}

pub fn route_summary(r: &RouteDraft) -> RouteSummary {
    let unresolved_legs = r.legs.iter().filter(|l| !l.is_resolved()).count();
    let distance_m = r
        .legs
        .iter()
        .filter(|l| l.is_resolved())
        .map(|l| l.distance_m)
        .sum();
    let duration_s = if unresolved_legs == 0 && !r.legs.is_empty() {
        r.legs.iter().map(|l| l.duration_s).sum()
    } else {
        None
    };
    let source = if r.legs.is_empty() {
        None
    } else {
        combine(r.legs.iter().map(leg_source))
    };
    let (pts, ele) = joined_geometry(&r.legs);
    let (mut ascent_m, mut descent_m, mut profile) = (None, None, Vec::new());
    if unresolved_legs == 0
        && let Some(e) = &ele
        && let Some(ad) = ascent_descent(e)
    {
        ascent_m = Some(ad.ascent_m);
        descent_m = Some(ad.descent_m);
        let mut along = 0.0;
        for (i, p) in pts.iter().enumerate() {
            if i > 0 {
                along += route_core::haversine_m(pts[i - 1], *p);
            }
            if let Some(Some(h)) = e.get(i) {
                profile.push([along.round(), *h]);
            }
        }
        if profile.len() > 600 {
            let step = profile.len() as f64 / 600.0;
            profile = (0..600)
                .map(|k| profile[(k as f64 * step) as usize])
                .collect();
        }
    }
    RouteSummary {
        distance_m,
        duration_s,
        unresolved_legs,
        ascent_m,
        descent_m,
        navigable: source == Some(GeometrySource::Routed),
        source,
        profile,
    }
}

fn build_route(i: usize, r: &RouteDraft, issues: &mut Vec<Issue>) -> Option<Route> {
    let p = |f: &str| format!("routes[{i}].{f}");
    if r.points.len() < 2 {
        issues.push(Issue::warning(
            p("points"),
            "route_empty",
            "needs at least two points; not published",
        ));
        return None;
    }
    let mut ok = true;
    for (j, l) in r.legs.iter().enumerate() {
        match l.state {
            LegState::Pending | LegState::Unresolved => {
                issues.push(Issue::error(
                    p(&format!("legs[{j}]")),
                    "leg_unresolved",
                    "this leg is not calculated yet (BELUM)",
                ));
                ok = false;
            }
            LegState::Matched if !l.unmatched.is_empty() => {
                issues.push(Issue::error(
                    p(&format!("legs[{j}]")),
                    "leg_unmatched",
                    "part of the drawn line matched no road: redraw, add a point or keep as sketch",
                ));
                ok = false;
            }
            _ => {}
        }
    }
    if !ok {
        return None;
    }
    let summary = route_summary(r);
    let source = summary.source.unwrap_or(GeometrySource::Sketch);
    if source != GeometrySource::Routed {
        issues.push(Issue::warning(
            p("source"),
            "route_not_navigable",
            "sketch or GPX geometry: guests see it, but it is not a navigable road route",
        ));
    }
    let (pts, _) = joined_geometry(&r.legs);
    let polyline = match encode_polyline(&pts) {
        Ok(s) => s,
        Err(_) => {
            issues.push(Issue::error(
                p("polyline"),
                "polyline",
                "geometry cannot be encoded",
            ));
            return None;
        }
    };
    let mut stop_ids: Vec<String> = Vec::new();
    let mut legs = Vec::new();
    let mut span: Option<(String, Vec<&Leg>)> = None;
    for (j, pt) in r.points.iter().enumerate() {
        if let Some(sid) = &pt.stop_id {
            if stop_ids.last() != Some(sid) {
                stop_ids.push(sid.clone());
            }
            if let Some((from, sub)) = span.take()
                && !sub.is_empty()
            {
                legs.push(RouteLeg {
                    from_stop_id: from,
                    to_stop_id: sid.clone(),
                    distance_m: sub.iter().map(|l| l.distance_m).sum(),
                    duration_s: sub.iter().map(|l| l.duration_s).sum(),
                    source: combine(sub.iter().map(|l| leg_source(l)))
                        .unwrap_or(GeometrySource::Sketch),
                });
            }
            span = Some((sid.clone(), Vec::new()));
        }
        if let (Some((_, sub)), Some(l)) = (&mut span, r.legs.get(j)) {
            sub.push(l);
        }
    }
    Some(Route {
        id: r.id.clone(),
        title: opt(&r.title),
        mode: r.mode,
        day: opt(&r.day),
        stop_ids,
        polyline: Some(polyline),
        source,
        distance_m: summary.distance_m,
        duration_s: summary.duration_s,
        ascent_m: summary.ascent_m,
        descent_m: summary.descent_m,
        legs,
    })
}

/// Builds the exact event that would be signed, with every issue found. `None` when the draft
/// cannot be turned into an event (missing dates) or any error exists.
pub fn build_event(
    doc: &Doc,
    rev: u32,
    parent: Option<String>,
    published_at: DateTime<Utc>,
) -> (Option<Event>, Vec<Issue>) {
    let mut issues = Vec::new();
    if doc.kind != DocKind::Event {
        issues.push(Issue::error(
            "kind",
            "not_event",
            "a standalone route is not an event",
        ));
        return (None, issues);
    }
    let m = &doc.meta;
    let start = time("start", &m.start, &mut issues);
    let end = time("end", &m.end, &mut issues);
    let expires_at = if m.expires_at.trim().is_empty() {
        end.as_ref().map(default_expiry)
    } else {
        time("expiresAt", &m.expires_at, &mut issues).map(|t| t.with_timezone(&Utc))
    };
    if let Some(e) = expires_at
        && e <= published_at
    {
        issues.push(Issue::error(
            "expiresAt",
            "expired",
            "access would already have ended",
        ));
    }

    let mut stops = Vec::with_capacity(doc.stops.len());
    for (i, s) in doc.stops.iter().enumerate() {
        let arrive = match (opt(&s.arrive_from), opt(&s.arrive_to)) {
            (None, None) => None,
            (Some(f), Some(t)) => {
                let from = time(&format!("stops[{i}].arrive"), &f, &mut issues);
                let to = time(&format!("stops[{i}].arrive"), &t, &mut issues);
                from.zip(to).map(|(from, to)| TimeWindow { from, to })
            }
            _ => {
                issues.push(Issue::error(
                    format!("stops[{i}].arrive"),
                    "arrive_incomplete",
                    "needs both a start and an end time",
                ));
                None
            }
        };
        stops.push(Stop {
            id: s.id.clone(),
            kind: s.kind,
            label: s.label.trim().to_owned(),
            lat: route_core::geo::round6(s.lat),
            lng: route_core::geo::round6(s.lng),
            entrance: s.entrance.map(|e| e.rounded6()),
            arrive,
            dwell_min: s.dwell_min,
            notes: opt(&s.notes),
            audience: opt(&s.audience),
            // s.organizer_note is deliberately not read: it has no field in the pack.
        });
    }

    let routes: Vec<Route> = doc
        .routes
        .iter()
        .enumerate()
        .filter_map(|(i, r)| build_route(i, r, &mut issues))
        .collect();

    let (Some(start), Some(end), Some(expires_at)) = (start, end, expires_at) else {
        return (None, issues);
    };
    let event = Event {
        id: doc.id.clone(),
        rev,
        parent,
        published_at,
        template: m.template,
        title: m.title.trim().to_owned(),
        description: opt(&m.description),
        lang: m.lang,
        tz: m.tz.trim().to_owned(),
        start,
        end,
        expires_at,
        organizer: opt(&m.alias).map(|a| Organizer { alias: Some(a) }),
        stops,
        sessions: doc.sessions.clone(),
        info: doc.info.clone(),
        routes,
        variant: None,
    };
    if let Err(e) = event.validate() {
        // Route indexes in the event match the draft only if no route was skipped.
        issues.push(Issue::error(e.path.clone(), "invalid_event", e.reason));
    }
    let has_error = issues.iter().any(|i| i.severity == Severity::Error);
    (if has_error { None } else { Some(event) }, issues)
}

// ---------------------------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Change {
    /// added | removed | changed
    pub kind: &'static str,
    /// event | stop | session | route
    pub what: &'static str,
    pub label: String,
    pub fields: Vec<String>,
}

const IGNORED: &[&str] = &[
    "rev",
    "parent",
    "publishedAt",
    "stops",
    "sessions",
    "routes",
];

fn obj(v: Value) -> BTreeMap<String, Value> {
    match v {
        Value::Object(m) => m.into_iter().collect(),
        _ => BTreeMap::new(),
    }
}

fn changed_fields(a: &BTreeMap<String, Value>, b: &BTreeMap<String, Value>) -> Vec<String> {
    let mut keys: Vec<&String> = a.keys().chain(b.keys()).collect();
    keys.sort();
    keys.dedup();
    keys.into_iter()
        .filter(|k| !IGNORED.contains(&k.as_str()) && a.get(*k) != b.get(*k))
        .cloned()
        .collect()
}

fn diff_list(what: &'static str, label_key: &str, old: &Value, new: &Value, out: &mut Vec<Change>) {
    let list = |v: &Value| -> Vec<BTreeMap<String, Value>> {
        v.as_array()
            .map(|a| a.iter().cloned().map(obj).collect())
            .unwrap_or_default()
    };
    let (old, new) = (list(old), list(new));
    let key =
        |m: &BTreeMap<String, Value>| m.get("id").and_then(Value::as_str).unwrap_or("").to_owned();
    let label = |m: &BTreeMap<String, Value>| {
        m.get(label_key)
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_owned()
    };
    for n in &new {
        match old.iter().find(|o| key(o) == key(n)) {
            None => out.push(Change {
                kind: "added",
                what,
                label: label(n),
                fields: vec![],
            }),
            Some(o) => {
                let f = changed_fields(o, n);
                if !f.is_empty() {
                    out.push(Change {
                        kind: "changed",
                        what,
                        label: label(n),
                        fields: f,
                    });
                }
            }
        }
    }
    for o in &old {
        if !new.iter().any(|n| key(n) == key(o)) {
            out.push(Change {
                kind: "removed",
                what,
                label: label(o),
                fields: vec![],
            });
        }
    }
}

/// What differs between the previous published revision and the new one.
pub fn change_summary(old: &Event, new: &Event) -> Vec<Change> {
    let (o, n) = (
        serde_json::to_value(old).unwrap_or(Value::Null),
        serde_json::to_value(new).unwrap_or(Value::Null),
    );
    let mut out = Vec::new();
    let f = changed_fields(&obj(o.clone()), &obj(n.clone()));
    if !f.is_empty() {
        out.push(Change {
            kind: "changed",
            what: "event",
            label: new.title.clone(),
            fields: f,
        });
    }
    diff_list("stop", "label", &o["stops"], &n["stops"], &mut out);
    diff_list("session", "title", &o["sessions"], &n["sessions"], &mut out);
    diff_list("route", "title", &o["routes"], &n["routes"], &mut out);
    out
}

/// True when two events differ only in revision bookkeeping.
pub fn same_content(a: &Event, b: &Event) -> bool {
    let strip = |e: &Event| Event {
        rev: 0,
        parent: None,
        published_at: DateTime::<Utc>::MIN_UTC,
        ..e.clone()
    };
    strip(a) == strip(b)
}

// ---------------------------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DocSummary {
    pub id: String,
    pub kind: DocKind,
    pub title: String,
    pub template: event_core::Template,
    pub start: String,
    pub updated_at: DateTime<Utc>,
    pub stop_count: usize,
    pub route_count: usize,
    pub distance_m: u32,
    pub published_rev: Option<u32>,
    pub imported: bool,
    pub expires_at: Option<DateTime<Utc>>,
    /// Up to 48 `[lng, lat]` points for the card's mini route line (route geometry, or stops).
    pub preview: Vec<[f64; 2]>,
}

pub fn summarize(doc: &Doc) -> DocSummary {
    let mut pts: Vec<LatLng> = Vec::new();
    if let Some(r) = doc
        .routes
        .iter()
        .find(|r| r.legs.iter().any(Leg::is_resolved))
    {
        pts = joined_geometry(&r.legs).0;
    }
    if pts.len() < 2 {
        pts = doc.stops.iter().map(|s| s.pos()).collect();
    }
    if pts.len() < 2
        && let Some(r) = doc.routes.first()
    {
        pts = r.points.iter().map(|p| p.pos()).collect();
    }
    DocSummary {
        id: doc.id.clone(),
        kind: doc.kind,
        title: doc.meta.title.clone(),
        template: doc.meta.template,
        start: doc.meta.start.clone(),
        updated_at: doc.updated_at,
        stop_count: doc.stops.len(),
        route_count: doc.routes.len(),
        distance_m: doc.routes.iter().map(|r| route_summary(r).distance_m).sum(),
        published_rev: doc
            .published
            .last()
            .map(|p| p.rev)
            .or(doc.imported.as_ref().map(|i| i.rev)),
        imported: doc.imported.is_some(),
        expires_at: doc.store_expiry(),
        preview: thin(&pts, 48).iter().map(|p| [p.lng, p.lat]).collect(),
    }
}
