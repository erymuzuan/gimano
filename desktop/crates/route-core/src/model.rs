//! Route and leg as carried in an event (docs/events.md §4 `routes`). Validation against the
//! event's stops and limits lives in `event-core`.

use serde::{Deserialize, Serialize};

/// Travel mode; also the `vehicle` of the routing API.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TravelMode {
    Car,
    Motorcycle,
    Bicycle,
    Walk,
}

/// Where the geometry came from. Only `Routed` is navigable turn by turn.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum GeometrySource {
    Routed,
    Sketch,
    Gpx,
}

impl GeometrySource {
    pub fn is_navigable(self) -> bool {
        matches!(self, GeometrySource::Routed)
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Route {
    pub id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    pub mode: TravelMode,
    /// Local date `YYYY-MM-DD`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub day: Option<String>,
    #[serde(default)]
    pub stop_ids: Vec<String>,
    /// Google encoded polyline, precision 6. Required on a full event; absent on every route of a
    /// stops-only variant (docs/events.md §5.5). Event validation enforces which.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub polyline: Option<String>,
    pub source: GeometrySource,
    pub distance_m: u32,
    /// Movement time only, excluding dwell.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub duration_s: Option<u32>,
    /// Absent means unavailable; never written as 0 for "unknown".
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ascent_m: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub descent_m: Option<u32>,
    #[serde(default)]
    pub legs: Vec<RouteLeg>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RouteLeg {
    pub from_stop_id: String,
    pub to_stop_id: String,
    pub distance_m: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub duration_s: Option<u32>,
    pub source: GeometrySource,
}
