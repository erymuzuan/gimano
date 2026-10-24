//! GPX import preview / apply and export, on top of `route_core::gpx` (bounded parser, no DTDs).

use event_core::{StopKind, TravelMode};
use route_core::{
    GpxDocument, GpxExport, GpxExportOptions, GpxPath, GpxPathKind, GpxPoint, GpxWaypoint, LatLng,
    path_length_m,
};
use serde::{Deserialize, Serialize};

use crate::doc::{Doc, Leg, LegState, RouteDraft, RoutePoint, StopDraft};
use crate::editing::{next_generation, thin};

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GpxPathPreview {
    pub index: usize,
    /// track | route
    pub kind: &'static str,
    pub name: Option<String>,
    pub point_count: usize,
    pub distance_m: u32,
    pub has_elevation: bool,
    pub has_times: bool,
    /// Up to 400 `[lng, lat]` points for the preview map.
    pub preview: Vec<[f64; 2]>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GpxPreview {
    pub paths: Vec<GpxPathPreview>,
    pub waypoints: Vec<(Option<String>, f64, f64)>,
    pub point_count: usize,
    pub has_timestamps: bool,
    pub has_elevation: bool,
}

pub fn preview(doc: &GpxDocument) -> GpxPreview {
    GpxPreview {
        paths: doc
            .paths
            .iter()
            .enumerate()
            .map(|(index, p)| {
                let pos = p.positions();
                GpxPathPreview {
                    index,
                    kind: match p.kind {
                        GpxPathKind::Track => "track",
                        GpxPathKind::Route => "route",
                    },
                    name: p.name.clone(),
                    point_count: pos.len(),
                    distance_m: path_length_m(&pos).round() as u32,
                    has_elevation: p.points().any(|x| x.ele.is_some()),
                    has_times: p.points().any(|x| x.time.is_some()),
                    preview: thin(&pos, 400).iter().map(|q| [q.lng, q.lat]).collect(),
                }
            })
            .collect(),
        waypoints: doc
            .waypoints
            .iter()
            .map(|w| (w.name.clone(), w.point.pos.lat, w.point.pos.lng))
            .collect(),
        point_count: doc.point_count,
        has_timestamps: doc.has_timestamps(),
        has_elevation: doc.has_elevation(),
    }
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GpxApply {
    pub path_index: usize,
    pub mode: TravelMode,
    /// "Ikut jalan sebenar (snap)": match the line to roads via `/routes/match`.
    pub snap: bool,
    /// Default on: recorded times are dropped on import.
    pub strip_timestamps: bool,
    #[serde(default)]
    pub waypoints_as_stops: bool,
}

/// A route draft from one GPX path: start and end points joined by one leg that keeps the GPX
/// geometry (or waits to be snapped to roads).
pub fn route_from_path(path: &GpxPath, opts: &GpxApply) -> Option<RouteDraft> {
    let pts: Vec<&GpxPoint> = path.points().collect();
    if pts.len() < 2 {
        return None;
    }
    let pos: Vec<LatLng> = pts.iter().map(|p| p.pos.rounded6()).collect();
    let mut r = RouteDraft::new(opts.mode);
    r.title = path
        .name
        .clone()
        .unwrap_or_default()
        .chars()
        .take(80)
        .collect();
    for p in [pos[0], pos[pos.len() - 1]] {
        r.points.push(RoutePoint {
            id: event_core::new_item_id().unwrap_or_else(|_| format!("g{}", next_generation())),
            lat: p.lat,
            lng: p.lng,
            stop_id: None,
        });
    }
    let mut leg = Leg::pending(next_generation());
    if opts.snap {
        leg.drawn = Some(thin(&pos, route_core::routing::MATCH_MAX_POINTS));
    } else {
        leg.state = LegState::Gpx;
        leg.distance_m = path_length_m(&pos).round() as u32;
        if pts.iter().any(|p| p.ele.is_some()) {
            leg.elevation = Some(pts.iter().map(|p| p.ele).collect());
        }
        if !opts.strip_timestamps && pts.iter().any(|p| p.time.is_some()) {
            leg.times = Some(pts.iter().map(|p| p.time.clone()).collect());
        }
        leg.geometry = pos;
    }
    r.legs.push(leg);
    Some(r)
}

pub fn waypoint_stops(doc: &GpxDocument) -> Vec<StopDraft> {
    doc.waypoints
        .iter()
        .take(event_core::limits::MAX_STOPS)
        .enumerate()
        .map(|(i, w)| {
            let p = w.point.pos.rounded6();
            StopDraft {
                id: event_core::new_item_id().unwrap_or_else(|_| format!("w{}", next_generation())),
                kind: StopKind::Other,
                label: w
                    .name
                    .clone()
                    .filter(|n| !n.trim().is_empty())
                    .unwrap_or_else(|| format!("Titik {}", i + 1))
                    .chars()
                    .take(80)
                    .collect(),
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
        })
        .collect()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GpxExportRequest {
    /// The user confirmed: GPX is plaintext and has no expiry.
    pub acknowledged_plaintext: bool,
    pub include_stops: bool,
    pub include_elevation: bool,
    /// Off by default; only meaningful for geometry imported with times kept.
    pub include_timestamps: bool,
}

/// What will be written, for the confirmation dialog (names and counts, nothing hidden).
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GpxExportManifest {
    pub track_name: Option<String>,
    pub point_count: usize,
    pub waypoint_names: Vec<String>,
    pub has_elevation: bool,
    pub has_timestamps: bool,
}

fn route_points(r: &RouteDraft, req: &GpxExportRequest) -> Vec<GpxPoint> {
    let mut out: Vec<GpxPoint> = Vec::new();
    for l in r.legs.iter().filter(|l| l.is_resolved()) {
        for (i, p) in l.geometry.iter().enumerate() {
            if i == 0 && out.last().is_some_and(|q| q.pos == *p) {
                continue;
            }
            out.push(GpxPoint {
                pos: *p,
                ele: l
                    .elevation
                    .as_ref()
                    .and_then(|e| e.get(i).copied().flatten()),
                time: if req.include_timestamps {
                    l.times.as_ref().and_then(|t| t.get(i).cloned().flatten())
                } else {
                    None
                },
            });
        }
    }
    out
}

fn stops_on(doc: &Doc, r: &RouteDraft) -> Vec<GpxWaypoint> {
    r.points
        .iter()
        .filter_map(|p| p.stop_id.as_deref().and_then(|id| doc.stop(id)))
        .map(|s| GpxWaypoint {
            point: GpxPoint::new(s.pos()),
            name: Some(s.label.clone()),
        })
        .collect()
}

pub fn manifest(doc: &Doc, r: &RouteDraft, req: &GpxExportRequest) -> GpxExportManifest {
    let pts = route_points(r, req);
    GpxExportManifest {
        track_name: (!r.title.trim().is_empty()).then(|| r.title.clone()),
        point_count: pts.len(),
        waypoint_names: if req.include_stops {
            stops_on(doc, r)
                .into_iter()
                .filter_map(|w| w.name)
                .collect()
        } else {
            Vec::new()
        },
        has_elevation: req.include_elevation && pts.iter().any(|p| p.ele.is_some()),
        has_timestamps: pts.iter().any(|p| p.time.is_some()),
    }
}

/// Builds the GPX text. Organizer-only notes are never included; guest notes neither (only
/// stop labels as waypoint names, shown to the user before writing).
pub fn export(doc: &Doc, r: &RouteDraft, req: &GpxExportRequest) -> Option<String> {
    let pts = route_points(r, req);
    if pts.len() < 2 {
        return None;
    }
    let export = GpxExport {
        name: None,
        time: None,
        paths: vec![GpxPath {
            kind: GpxPathKind::Track,
            name: (!r.title.trim().is_empty()).then(|| r.title.clone()),
            segments: vec![pts],
        }],
        waypoints: if req.include_stops {
            stops_on(doc, r)
        } else {
            Vec::new()
        },
    };
    Some(route_core::export_gpx(
        &export,
        &GpxExportOptions {
            include_metadata: false,
            include_timestamps: req.include_timestamps,
            include_elevation: req.include_elevation,
        },
    ))
}
