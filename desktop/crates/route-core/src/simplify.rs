//! Douglas–Peucker line simplification (docs/events.md §5.4).
//!
//! Distances are measured on a local equirectangular projection around each chord, in metres,
//! against the chord **segment** (not the infinite line), so every dropped point lies within the
//! tolerance of the simplified polyline, closed loops included. The first and last points are always
//! kept. Iterative, so 50 000-point routes cannot overflow the stack.

use crate::geo::{EARTH_RADIUS_M, LatLng};
use crate::polyline::{PolylineError, decode_polyline, encode_polyline};

/// Tolerance writers apply before signing an event (docs/events.md §5.4).
pub const EVENT_SIMPLIFY_TOLERANCE_M: f64 = 5.0;

/// Simplified copy of `points`; inputs with fewer than 3 points come back unchanged.
pub fn simplify(points: &[LatLng], tolerance_m: f64) -> Vec<LatLng> {
    let n = points.len();
    if n < 3 || !tolerance_m.is_finite() || tolerance_m <= 0.0 {
        return points.to_vec();
    }
    let mut keep = vec![false; n];
    keep[0] = true;
    keep[n - 1] = true;
    let mut stack = vec![(0usize, n - 1)];
    while let Some((first, last)) = stack.pop() {
        if last <= first + 1 {
            continue;
        }
        let chord = Chord::new(points[first], points[last]);
        let (mut index, mut max) = (first, -1.0f64);
        for (i, p) in points.iter().enumerate().take(last).skip(first + 1) {
            let d = chord.distance_m(*p);
            if d > max {
                max = d;
                index = i;
            }
        }
        if max > tolerance_m {
            keep[index] = true;
            stack.push((first, index));
            stack.push((index, last));
        }
    }
    points
        .iter()
        .zip(keep)
        .filter_map(|(p, k)| k.then_some(*p))
        .collect()
}

/// Decodes a precision-6 polyline, simplifies it and encodes it again.
pub fn simplify_polyline(
    encoded: &str,
    tolerance_m: f64,
    max_points: usize,
) -> Result<String, PolylineError> {
    let points = decode_polyline(encoded, max_points)?;
    encode_polyline(&simplify(&points, tolerance_m))
}

/// A chord projected around its start point: x east, y north, metres.
struct Chord {
    origin: LatLng,
    kx: f64,
    ky: f64,
    bx: f64,
    by: f64,
    len2: f64,
}

impl Chord {
    fn new(a: LatLng, b: LatLng) -> Self {
        let mid_lat = ((a.lat + b.lat) / 2.0).to_radians();
        let ky = EARTH_RADIUS_M.to_radians();
        let kx = ky * mid_lat.cos();
        let mut c = Chord {
            origin: a,
            kx,
            ky,
            bx: 0.0,
            by: 0.0,
            len2: 0.0,
        };
        (c.bx, c.by) = c.project(b);
        c.len2 = c.bx * c.bx + c.by * c.by;
        c
    }

    fn project(&self, p: LatLng) -> (f64, f64) {
        let mut dlng = p.lng - self.origin.lng;
        // Routes never span half the globe; this only keeps antimeridian crossings sane.
        if dlng > 180.0 {
            dlng -= 360.0;
        } else if dlng < -180.0 {
            dlng += 360.0;
        }
        (dlng * self.kx, (p.lat - self.origin.lat) * self.ky)
    }

    /// Distance from `p` to the chord segment, metres.
    fn distance_m(&self, p: LatLng) -> f64 {
        let (px, py) = self.project(p);
        let t = if self.len2 > 0.0 {
            ((px * self.bx + py * self.by) / self.len2).clamp(0.0, 1.0)
        } else {
            0.0
        };
        let (dx, dy) = (px - t * self.bx, py - t * self.by);
        (dx * dx + dy * dy).sqrt()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::geo::path_length_m;

    /// Independent check: distance from `p` to segment `a`–`b` on one projection for the whole
    /// route (mean latitude), not the per-chord one used by the implementation.
    fn seg_distance(p: LatLng, a: LatLng, b: LatLng, cos_lat: f64) -> f64 {
        let k = EARTH_RADIUS_M.to_radians();
        let xy = |q: LatLng| ((q.lng - a.lng) * k * cos_lat, (q.lat - a.lat) * k);
        let ((px, py), (bx, by)) = (xy(p), xy(b));
        let len2 = bx * bx + by * by;
        let t = if len2 > 0.0 {
            ((px * bx + py * by) / len2).clamp(0.0, 1.0)
        } else {
            0.0
        };
        ((px - t * bx).powi(2) + (py - t * by).powi(2)).sqrt()
    }

    /// Every original point is within `tol` of the simplified line, checked against the segment
    /// between the kept points that bracket it.
    fn assert_within(original: &[LatLng], simplified: &[LatLng], tol: f64) -> f64 {
        let cos_lat = (original.iter().map(|p| p.lat).sum::<f64>() / original.len() as f64)
            .to_radians()
            .cos();
        let mut worst = 0.0f64;
        let mut seg = 0;
        for p in original {
            while seg + 1 < simplified.len() - 1 && *p == simplified[seg + 1] {
                seg += 1;
            }
            let d = seg_distance(*p, simplified[seg], simplified[seg + 1], cos_lat);
            worst = worst.max(d);
            assert!(d <= tol + 0.05, "point {p:?} is {d:.3} m from the line");
        }
        worst
    }

    fn ll(lat: f64, lng: f64) -> LatLng {
        LatLng::new(lat, lng).rounded6()
    }

    /// A long, road-like line: ~10 m steps, slowly turning, with switchbacks and straights and
    /// sub-metre jitter, rounded to 6 decimals like a decoded polyline. ~190 km in Pahang/Terengganu.
    fn winding_road(n: usize) -> Vec<LatLng> {
        let mut state: u64 = 0x5eed_cafe;
        let mut rnd = || {
            state = state
                .wrapping_mul(6_364_136_223_846_793_005)
                .wrapping_add(1_442_695_040_888_963_407);
            ((state >> 11) as f64) / ((1u64 << 53) as f64)
        };
        let (mut lat, mut lng, mut heading) = (3.80_f64, 102.40_f64, 0.6_f64);
        let mut turn = 0.0f64;
        let mut out = Vec::with_capacity(n);
        let m_per_deg = EARTH_RADIUS_M.to_radians();
        for i in 0..n {
            if i % 400 == 0 {
                // A new stretch: straight, gentle curve, or tight switchbacks.
                turn = match (rnd() * 3.0) as u32 {
                    0 => 0.0,
                    1 => (rnd() - 0.5) * 0.01,
                    _ => (rnd() - 0.5) * 0.08,
                };
            }
            heading += turn + (rnd() - 0.5) * 0.004;
            let step = 9.0 + rnd() * 2.0;
            lat += step * heading.cos() / m_per_deg;
            lng += step * heading.sin() / (m_per_deg * lat.to_radians().cos());
            let jitter = 0.4 / m_per_deg;
            out.push(ll(
                lat + (rnd() - 0.5) * jitter,
                lng + (rnd() - 0.5) * jitter,
            ));
        }
        out
    }

    #[test]
    fn long_road_drops_most_points_and_stays_within_5_m() {
        let road = winding_road(20_000);
        let simple = simplify(&road, EVENT_SIMPLIFY_TOLERANCE_M);
        assert_eq!(simple.first(), road.first());
        assert_eq!(simple.last(), road.last());
        assert!(
            simple.len() * 5 < road.len(),
            "{} → {} points",
            road.len(),
            simple.len()
        );
        let worst = assert_within(&road, &simple, EVENT_SIMPLIFY_TOLERANCE_M);
        assert!(worst > 3.0, "tolerance is actually used ({worst:.2} m)");
        // Kept points are a subsequence of the original, in order.
        let mut it = road.iter();
        assert!(simple.iter().all(|p| it.any(|q| q == p)));
        // The simplified length differs by well under 1 %.
        let (a, b) = (path_length_m(&road), path_length_m(&simple));
        assert!((a - b).abs() / a < 0.01, "{a} vs {b}");
        // Idempotent.
        assert_eq!(simplify(&simple, EVENT_SIMPLIFY_TOLERANCE_M), simple);
        // Encoded size shrinks accordingly.
        let before = encode_polyline(&road).unwrap();
        let after = simplify_polyline(&before, 5.0, 50_000).unwrap();
        assert_eq!(after, encode_polyline(&simple).unwrap());
        assert!(after.len() * 4 < before.len());
    }

    #[test]
    fn keeps_corners_drops_collinear() {
        let pts: Vec<_> = (0..=10)
            .map(|i| ll(3.0, 101.0 + f64::from(i) * 0.001))
            .chain((1..=10).map(|i| ll(3.0 + f64::from(i) * 0.001, 101.01)))
            .collect();
        assert_eq!(
            simplify(&pts, 5.0),
            vec![ll(3.0, 101.0), ll(3.0, 101.01), ll(3.01, 101.01)]
        );
    }

    #[test]
    fn respects_the_tolerance_boundary() {
        // Middle point 4 m and 6 m off a 1 km chord.
        let off = |m: f64| m / EARTH_RADIUS_M.to_radians();
        let a = ll(3.0, 101.0);
        let b = ll(3.0, 101.009);
        let near = LatLng::new(3.0 + off(4.0), 101.0045);
        let far = LatLng::new(3.0 + off(6.0), 101.0045);
        assert_eq!(simplify(&[a, near, b], 5.0), vec![a, b]);
        assert_eq!(simplify(&[a, far, b], 5.0), vec![a, far, b]);
    }

    #[test]
    fn closed_loop_and_spikes() {
        // A loop back to the start: segment distance keeps the far side.
        let start = ll(3.0, 101.0);
        let loop_pts = vec![
            start,
            ll(3.001, 101.0),
            ll(3.001, 101.001),
            ll(3.0, 101.001),
            start,
        ];
        let s = simplify(&loop_pts, 5.0);
        assert_eq!(s.first(), Some(&start));
        assert_eq!(s.last(), Some(&start));
        assert!(s.len() >= 4, "{s:?}");
        assert_within(&loop_pts, &s, 5.0);
        // An out-and-back spike beyond the end of the chord is kept.
        let spike = vec![ll(3.0, 101.0), ll(3.0, 101.002), ll(3.0, 101.001)];
        assert_eq!(simplify(&spike, 5.0), spike);
    }

    #[test]
    fn small_inputs_and_bad_tolerance() {
        assert!(simplify(&[], 5.0).is_empty());
        let one = vec![ll(3.0, 101.0)];
        assert_eq!(simplify(&one, 5.0), one);
        let two = vec![ll(3.0, 101.0), ll(3.0, 101.0)];
        assert_eq!(simplify(&two, 5.0), two);
        let same = vec![ll(3.0, 101.0); 5];
        assert_eq!(simplify(&same, 5.0).len(), 2);
        let three = vec![ll(3.0, 101.0), ll(3.0, 101.000_01), ll(3.0, 101.000_02)];
        assert_eq!(simplify(&three, 0.0), three);
        assert_eq!(simplify(&three, f64::NAN), three);
    }
}
