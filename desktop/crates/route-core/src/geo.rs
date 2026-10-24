use serde::{Deserialize, Serialize};

/// Mean Earth radius (IUGG), metres.
pub const EARTH_RADIUS_M: f64 = 6_371_008.8;

/// WGS84 position in decimal degrees.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct LatLng {
    pub lat: f64,
    pub lng: f64,
}

impl LatLng {
    pub const fn new(lat: f64, lng: f64) -> Self {
        Self { lat, lng }
    }

    pub fn is_valid(&self) -> bool {
        self.lat.is_finite()
            && self.lng.is_finite()
            && (-90.0..=90.0).contains(&self.lat)
            && (-180.0..=180.0).contains(&self.lng)
    }

    /// Rounded to 6 decimals, the precision of event JSON and polylines.
    pub fn rounded6(&self) -> Self {
        Self {
            lat: round6(self.lat),
            lng: round6(self.lng),
        }
    }
}

pub fn round6(v: f64) -> f64 {
    (v * 1e6).round() / 1e6
}

/// Great-circle distance in metres (haversine, spherical Earth).
pub fn haversine_m(a: LatLng, b: LatLng) -> f64 {
    let (p1, p2) = (a.lat.to_radians(), b.lat.to_radians());
    let dp = p2 - p1;
    let dl = (b.lng - a.lng).to_radians();
    let h = (dp / 2.0).sin().powi(2) + p1.cos() * p2.cos() * (dl / 2.0).sin().powi(2);
    2.0 * EARTH_RADIUS_M * h.sqrt().min(1.0).asin()
}

pub fn path_length_m(points: &[LatLng]) -> f64 {
    points.windows(2).map(|w| haversine_m(w[0], w[1])).sum()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn haversine_known_distance() {
        // One degree of latitude on the mean sphere.
        let d = haversine_m(LatLng::new(0.0, 101.0), LatLng::new(1.0, 101.0));
        assert!((d - 111_195.08).abs() < 1.0, "{d}");
        assert_eq!(
            haversine_m(LatLng::new(3.1, 101.6), LatLng::new(3.1, 101.6)),
            0.0
        );
    }

    #[test]
    fn path_length_sums_segments() {
        let pts = [
            LatLng::new(0.0, 0.0),
            LatLng::new(0.0, 1.0),
            LatLng::new(0.0, 2.0),
        ];
        let one = haversine_m(pts[0], pts[1]);
        assert!((path_length_m(&pts) - 2.0 * one).abs() < 1e-6);
        assert_eq!(path_length_m(&pts[..1]), 0.0);
    }

    #[test]
    fn validity() {
        assert!(LatLng::new(90.0, -180.0).is_valid());
        assert!(!LatLng::new(90.1, 0.0).is_valid());
        assert!(!LatLng::new(0.0, f64::NAN).is_valid());
    }
}
