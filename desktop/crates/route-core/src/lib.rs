//! Route planning core for Manotu Desktop (docs/events.md §4 routes, §6 routing).
//!
//! Everything here is local computation except [`routing`], whose HTTP client sends coordinates
//! and the travel mode only (never a stop label, event id or note) with `"store": false`.

pub mod elevation;
pub mod geo;
pub mod gpx;
pub mod model;
pub mod polyline;
pub mod routing;
pub mod simplify;

pub use elevation::{AscentDescent, ELEVATION_HYSTERESIS_M, ascent_descent};
pub use geo::{EARTH_RADIUS_M, LatLng, haversine_m, path_length_m};
pub use gpx::{
    GpxDocument, GpxError, GpxExport, GpxExportOptions, GpxLimits, GpxPath, GpxPathKind, GpxPoint,
    GpxWaypoint, export_gpx, parse_gpx,
};
pub use model::{GeometrySource, Route, RouteLeg, TravelMode};
pub use polyline::{PolylineError, decode_polyline, encode_polyline};
pub use routing::{
    MatchRequest, MatchedPath, RouteRequest, RoutedLeg, RoutedPath, RoutingClient, RoutingError,
    UnmatchedRange,
};
pub use simplify::{EVENT_SIMPLIFY_TOLERANCE_M, simplify, simplify_polyline};

#[cfg(feature = "http")]
pub use routing::http::HttpRoutingClient;
