//! Stateless routing (docs/events.md §6). Request bodies carry coordinates and the travel mode
//! only: no waypoint names, place ids, event ids or notes, and `POST /routes` always sends
//! `"store": false` so the server caches nothing.

#[cfg(feature = "http")]
pub mod http;

use std::future::Future;

use serde::Deserialize;
use serde_json::{Value, json};

use crate::geo::LatLng;
use crate::model::TravelMode;
use crate::polyline::{PolylineError, decode_polyline};

/// Intermediate waypoints allowed by `POST /routes` with `"store": false`.
pub const MAX_STATELESS_WAYPOINTS: usize = 25;
pub const MATCH_MIN_POINTS: usize = 2;
pub const MATCH_MAX_POINTS: usize = 5000;
/// Upper bound for decoding a server polyline.
pub const MAX_RESPONSE_POINTS: usize = 500_000;

#[derive(Debug, Clone, PartialEq)]
pub struct RouteRequest {
    pub origin: LatLng,
    pub destination: LatLng,
    /// Intermediate stops in order, 0–25.
    pub waypoints: Vec<LatLng>,
    pub vehicle: TravelMode,
}

#[derive(Debug, Clone, PartialEq)]
pub struct MatchRequest {
    /// Drawn line in drawing order, 2–5000 points.
    pub points: Vec<LatLng>,
    pub vehicle: TravelMode,
}

#[derive(Debug, Clone, PartialEq)]
pub struct RoutedLeg {
    pub distance_m: u32,
    pub duration_s: u32,
}

#[derive(Debug, Clone, PartialEq)]
pub struct RoutedPath {
    /// Server id (`x-…` for stateless routes); not stored in events.
    pub id: String,
    /// Precision-6 encoded polyline exactly as returned.
    pub polyline: String,
    pub points: Vec<LatLng>,
    pub distance_m: u32,
    pub duration_s: u32,
    pub legs: Vec<RoutedLeg>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UnmatchedRange {
    pub from_index: usize,
    pub to_index: usize,
}

#[derive(Debug, Clone, PartialEq)]
pub struct MatchedPath {
    pub polyline: String,
    pub points: Vec<LatLng>,
    pub distance_m: u32,
    pub duration_s: u32,
    pub unmatched: Vec<UnmatchedRange>,
}

#[derive(Debug, Clone, PartialEq, thiserror::Error)]
pub enum RoutingError {
    #[error("invalid routing request: {0}")]
    InvalidRequest(&'static str),
    /// 422: no route between the points, or nothing matched.
    #[error("no route found")]
    NoRoute,
    /// 503: routing engine down.
    #[error("routing engine unavailable")]
    Unavailable,
    #[error("routing server returned HTTP {0}")]
    Status(u16),
    #[error("routing request failed: {0}")]
    Transport(String),
    #[error("invalid routing response: {0}")]
    InvalidResponse(String),
}

impl From<PolylineError> for RoutingError {
    fn from(e: PolylineError) -> Self {
        RoutingError::InvalidResponse(e.to_string())
    }
}

pub trait RoutingClient {
    /// `POST /routes` with `"store": false`.
    fn route(
        &self,
        req: &RouteRequest,
    ) -> impl Future<Output = Result<Vec<RoutedPath>, RoutingError>> + Send;

    /// `POST /routes/match`: snap a drawn line to roads.
    fn match_line(
        &self,
        req: &MatchRequest,
    ) -> impl Future<Output = Result<MatchedPath, RoutingError>> + Send;
}

fn point(p: &LatLng) -> Value {
    json!({ "lat": p.lat, "lng": p.lng })
}

fn check_points<'a>(pts: impl IntoIterator<Item = &'a LatLng>) -> Result<(), RoutingError> {
    if pts.into_iter().all(LatLng::is_valid) {
        Ok(())
    } else {
        Err(RoutingError::InvalidRequest("coordinate out of range"))
    }
}

/// The exact JSON body sent for `POST /routes`.
pub fn route_request_body(req: &RouteRequest) -> Result<Value, RoutingError> {
    if req.waypoints.len() > MAX_STATELESS_WAYPOINTS {
        return Err(RoutingError::InvalidRequest("more than 25 waypoints"));
    }
    check_points(
        [&req.origin, &req.destination]
            .into_iter()
            .chain(&req.waypoints),
    )?;
    Ok(json!({
        "origin": point(&req.origin),
        "destination": point(&req.destination),
        "waypoints": req.waypoints.iter().map(point).collect::<Vec<_>>(),
        "vehicle": req.vehicle,
        "store": false,
    }))
}

/// The exact JSON body sent for `POST /routes/match`.
pub fn match_request_body(req: &MatchRequest) -> Result<Value, RoutingError> {
    if !(MATCH_MIN_POINTS..=MATCH_MAX_POINTS).contains(&req.points.len()) {
        return Err(RoutingError::InvalidRequest("match needs 2–5000 points"));
    }
    check_points(&req.points)?;
    Ok(json!({
        "points": req.points.iter().map(point).collect::<Vec<_>>(),
        "vehicle": req.vehicle,
    }))
}

fn metres(v: f64) -> u32 {
    if v.is_finite() && v > 0.0 {
        v.round().min(f64::from(u32::MAX)) as u32
    } else {
        0
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct WireRoutes {
    routes: Vec<WireRoute>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct WireRoute {
    #[serde(default)]
    id: String,
    polyline: String,
    distance_m: f64,
    duration_s: f64,
    #[serde(default)]
    legs: Vec<WireLeg>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct WireLeg {
    distance_m: f64,
    duration_s: f64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct WireMatch {
    polyline: String,
    distance_m: f64,
    duration_s: f64,
    #[serde(default)]
    unmatched: Vec<WireUnmatched>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct WireUnmatched {
    from_index: usize,
    to_index: usize,
}

pub fn parse_routes_response(body: &[u8]) -> Result<Vec<RoutedPath>, RoutingError> {
    let wire: WireRoutes =
        serde_json::from_slice(body).map_err(|e| RoutingError::InvalidResponse(e.to_string()))?;
    wire.routes
        .into_iter()
        .map(|r| {
            Ok(RoutedPath {
                points: decode_polyline(&r.polyline, MAX_RESPONSE_POINTS)?,
                id: r.id,
                polyline: r.polyline,
                distance_m: metres(r.distance_m),
                duration_s: metres(r.duration_s),
                legs: r
                    .legs
                    .into_iter()
                    .map(|l| RoutedLeg {
                        distance_m: metres(l.distance_m),
                        duration_s: metres(l.duration_s),
                    })
                    .collect(),
            })
        })
        .collect()
}

pub fn parse_match_response(body: &[u8]) -> Result<MatchedPath, RoutingError> {
    let m: WireMatch =
        serde_json::from_slice(body).map_err(|e| RoutingError::InvalidResponse(e.to_string()))?;
    Ok(MatchedPath {
        points: decode_polyline(&m.polyline, MAX_RESPONSE_POINTS)?,
        polyline: m.polyline,
        distance_m: metres(m.distance_m),
        duration_s: metres(m.duration_s),
        unmatched: m
            .unmatched
            .into_iter()
            .map(|u| UnmatchedRange {
                from_index: u.from_index,
                to_index: u.to_index,
            })
            .collect(),
    })
}

/// Maps an HTTP status from the routing API to an error (`None` for success).
pub fn status_error(status: u16) -> Option<RoutingError> {
    match status {
        200..=299 => None,
        422 => Some(RoutingError::NoRoute),
        503 => Some(RoutingError::Unavailable),
        s => Some(RoutingError::Status(s)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn req(n: usize) -> RouteRequest {
        RouteRequest {
            origin: LatLng::new(3.13, 101.62),
            destination: LatLng::new(3.12, 101.61),
            waypoints: vec![LatLng::new(3.15, 101.69); n],
            vehicle: TravelMode::Bicycle,
        }
    }

    #[test]
    fn route_body_is_coordinates_and_vehicle_only() {
        let body = route_request_body(&req(2)).unwrap();
        let obj = body.as_object().unwrap();
        let mut keys: Vec<_> = obj.keys().map(String::as_str).collect();
        keys.sort_unstable();
        assert_eq!(
            keys,
            ["destination", "origin", "store", "vehicle", "waypoints"]
        );
        assert_eq!(body["store"], json!(false));
        assert_eq!(body["vehicle"], json!("bicycle"));
        assert_eq!(body["waypoints"][1], json!({"lat": 3.15, "lng": 101.69}));
        assert!(!body.to_string().contains("name"));
    }

    #[test]
    fn route_waypoint_limit() {
        assert!(route_request_body(&req(25)).is_ok());
        assert_eq!(
            route_request_body(&req(26)),
            Err(RoutingError::InvalidRequest("more than 25 waypoints"))
        );
        let mut bad = req(0);
        bad.origin.lat = 95.0;
        assert!(route_request_body(&bad).is_err());
    }

    #[test]
    fn match_point_limits() {
        let m = |n| MatchRequest {
            points: vec![LatLng::new(3.1, 101.7); n],
            vehicle: TravelMode::Walk,
        };
        assert!(match_request_body(&m(1)).is_err());
        assert!(match_request_body(&m(5001)).is_err());
        let body = match_request_body(&m(2)).unwrap();
        assert_eq!(
            body,
            json!({"points": [{"lat":3.1,"lng":101.7},{"lat":3.1,"lng":101.7}], "vehicle": "walk"})
        );
    }

    #[test]
    fn parses_route_response() {
        let body = br#"{"routes":[{"id":"x-1","label":"best","polyline":"_izlhA~rlgdF_{geC~ywl@","distanceM":1234.6,"durationS":300,"summary":"Via X","legs":[{"distanceM":1234.6,"durationS":300,"to":{"lat":1,"lng":2},"maneuvers":[]}]}]}"#;
        let r = parse_routes_response(body).unwrap();
        assert_eq!(r.len(), 1);
        assert_eq!(r[0].id, "x-1");
        assert_eq!(r[0].distance_m, 1235);
        assert_eq!(
            r[0].points,
            vec![LatLng::new(38.5, -120.2), LatLng::new(40.7, -120.95)]
        );
        assert_eq!(
            r[0].legs,
            vec![RoutedLeg {
                distance_m: 1235,
                duration_s: 300
            }]
        );
        assert!(
            parse_routes_response(br#"{"routes":[{"polyline":"!!","distanceM":1,"durationS":1}]}"#)
                .is_err()
        );
    }

    #[test]
    fn parses_match_response() {
        let body = br#"{"polyline":"_izlhA~rlgdF","distanceM":10,"durationS":5,"unmatched":[{"fromIndex":40,"toIndex":55}]}"#;
        let m = parse_match_response(body).unwrap();
        assert_eq!(m.points.len(), 1);
        assert_eq!(
            m.unmatched,
            vec![UnmatchedRange {
                from_index: 40,
                to_index: 55
            }]
        );
    }

    #[test]
    fn status_mapping() {
        assert_eq!(status_error(200), None);
        assert_eq!(status_error(422), Some(RoutingError::NoRoute));
        assert_eq!(status_error(503), Some(RoutingError::Unavailable));
        assert_eq!(status_error(404), Some(RoutingError::Status(404)));
    }
}
