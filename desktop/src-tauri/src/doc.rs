//! Local draft documents. A draft is richer than the published event: it keeps editable text
//! dates, route points with their legs and editing state, and **organizer-only notes**, which
//! have no counterpart in `event_core::Event` and therefore can never be written into a pack.

use chrono::{DateTime, Utc};
use event_core::{InfoBlock, Lang, Session, StopKind, Template, TravelMode};
use route_core::LatLng;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DocKind {
    /// An event (kenduri, ride, …) with stops, sessions and routes.
    Event,
    /// A standalone route, kept privately on its own.
    Route,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Doc {
    /// For events, the event id (b64u of 16 bytes) shared by every revision.
    pub id: String,
    pub kind: DocKind,
    pub updated_at: DateTime<Utc>,
    pub meta: EventMeta,
    #[serde(default)]
    pub stops: Vec<StopDraft>,
    #[serde(default)]
    pub sessions: Vec<Session>,
    /// Guest info blocks (§4.1). Kept from imported packs and republished as-is; the desktop
    /// editor shows them read-only.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub info: Vec<InfoBlock>,
    #[serde(default)]
    pub routes: Vec<RouteDraft>,
    /// Published revisions, oldest first (the exact signed bytes).
    #[serde(default)]
    pub published: Vec<PublishedRev>,
    /// Set for packs imported from someone else: read-only, no organizer key.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub imported: Option<ImportedInfo>,
}

/// Event metadata as the user types it; dates are RFC 3339 text and are checked on publish.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EventMeta {
    pub template: Template,
    pub title: String,
    #[serde(default)]
    pub description: String,
    pub lang: Lang,
    pub tz: String,
    pub start: String,
    pub end: String,
    /// Empty = default (end + 24 h).
    #[serde(default)]
    pub expires_at: String,
    #[serde(default)]
    pub alias: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StopDraft {
    pub id: String,
    pub kind: StopKind,
    pub label: String,
    pub lat: f64,
    pub lng: f64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub entrance: Option<LatLng>,
    #[serde(default)]
    pub arrive_from: String,
    #[serde(default)]
    pub arrive_to: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub dwell_min: Option<u32>,
    /// Guest-visible.
    #[serde(default)]
    pub notes: String,
    #[serde(default)]
    pub audience: String,
    /// "Nota penganjur sahaja": stays in the local encrypted store, never exported.
    #[serde(default)]
    pub organizer_note: String,
}

impl StopDraft {
    pub fn pos(&self) -> LatLng {
        LatLng::new(self.lat, self.lng)
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RouteDraft {
    pub id: String,
    #[serde(default)]
    pub title: String,
    pub mode: TravelMode,
    #[serde(default)]
    pub day: String,
    /// "Ikut jalan": off = straight-line sketch legs.
    pub follow_roads: bool,
    pub points: Vec<RoutePoint>,
    /// `legs[i]` joins `points[i]` and `points[i + 1]`; always `points.len() - 1` long (or 0).
    pub legs: Vec<Leg>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RoutePoint {
    pub id: String,
    pub lat: f64,
    pub lng: f64,
    /// `Some` = a stop (linked to `Doc::stops`); `None` = a shaping point.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub stop_id: Option<String>,
}

impl RoutePoint {
    pub fn pos(&self) -> LatLng {
        LatLng::new(self.lat, self.lng)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LegState {
    /// Needs calculation ("BELUM").
    Pending,
    /// Road geometry from `POST /routes` (store:false).
    Routed,
    /// Drawn line snapped by `POST /routes/match`; `unmatched` lists drawn ranges with no road.
    Matched,
    /// Straight line or drawn line kept as an explicit sketch (not navigable).
    Sketch,
    /// Geometry from an imported GPX file, kept as is (not navigable).
    Gpx,
    /// The routing engine could not connect these points ("BELUM"); nothing is drawn.
    Unresolved,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Leg {
    pub state: LegState,
    /// Bumped whenever the leg is invalidated, so late routing results are discarded.
    pub generation: u64,
    #[serde(default)]
    pub geometry: Vec<LatLng>,
    #[serde(default)]
    pub distance_m: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub duration_s: Option<u32>,
    /// Elevation per geometry point, only when an imported GPX had it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub elevation: Option<Vec<Option<f64>>>,
    /// Recorded GPX times per geometry point, kept only if the user chose not to strip them;
    /// used for GPX re-export only, never published.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub times: Option<Vec<Option<String>>>,
    /// Freehand line drawn in "Lukis" mode (input of `/routes/match`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub drawn: Option<Vec<LatLng>>,
    /// Inclusive ranges of `drawn` that matched no road.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub unmatched: Vec<[usize; 2]>,
    /// Why the leg is unresolved (routing error code), for the leg list.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub problem: Option<String>,
}

impl Leg {
    pub fn pending(generation: u64) -> Self {
        Leg {
            state: LegState::Pending,
            generation,
            geometry: Vec::new(),
            distance_m: 0,
            duration_s: None,
            elevation: None,
            times: None,
            drawn: None,
            unmatched: Vec::new(),
            problem: None,
        }
    }

    /// Has usable geometry (drawn on the map, counted in totals).
    pub fn is_resolved(&self) -> bool {
        matches!(
            self.state,
            LegState::Routed | LegState::Matched | LegState::Sketch | LegState::Gpx
        )
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PublishedRev {
    pub rev: u32,
    pub published_at: DateTime<Utc>,
    /// b64u of the exact signed event bytes.
    pub event_bytes: String,
    pub hash: String,
    pub expires_at: DateTime<Utc>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportedInfo {
    pub safety_code: String,
    /// b64u of the pinned organizer public key.
    pub organizer_pub: String,
    pub rev: u32,
    pub expires_at: DateTime<Utc>,
}

impl Doc {
    pub fn stop(&self, id: &str) -> Option<&StopDraft> {
        self.stops.iter().find(|s| s.id == id)
    }

    pub fn route(&self, id: &str) -> Option<&RouteDraft> {
        self.routes.iter().find(|r| r.id == id)
    }

    pub fn route_mut(&mut self, id: &str) -> Option<&mut RouteDraft> {
        self.routes.iter_mut().find(|r| r.id == id)
    }

    /// When the local copy must be deleted: only once published or imported. An unpublished
    /// draft has no expiry, so planning a past-dated event never loses work.
    pub fn store_expiry(&self) -> Option<DateTime<Utc>> {
        if let Some(i) = &self.imported {
            return Some(i.expires_at);
        }
        self.published.last().map(|p| p.expires_at)
    }
}
