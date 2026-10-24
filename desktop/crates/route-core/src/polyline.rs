//! Google encoded polyline at precision 6 (1e-6 degrees), as used by the routing API.
//! Coordinates are rounded half away from zero before encoding.

use crate::geo::LatLng;

const FACTOR: f64 = 1e6;

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum PolylineError {
    #[error("invalid polyline character at byte {0}")]
    InvalidChar(usize),
    #[error("polyline ends in the middle of a value")]
    Truncated,
    #[error("polyline value overflows")]
    Overflow,
    #[error("polyline has more than {0} points")]
    TooManyPoints(usize),
    #[error("polyline point {0} is outside the valid coordinate range")]
    OutOfRange(usize),
    #[error("coordinate {0} cannot be encoded")]
    InvalidCoordinate(usize),
}

pub fn encode_polyline(points: &[LatLng]) -> Result<String, PolylineError> {
    let mut out = String::with_capacity(points.len() * 8);
    let (mut plat, mut plng) = (0i64, 0i64);
    for (i, p) in points.iter().enumerate() {
        if !p.is_valid() {
            return Err(PolylineError::InvalidCoordinate(i));
        }
        let lat = (p.lat * FACTOR).round() as i64;
        let lng = (p.lng * FACTOR).round() as i64;
        push_value(&mut out, lat - plat);
        push_value(&mut out, lng - plng);
        plat = lat;
        plng = lng;
    }
    Ok(out)
}

fn push_value(out: &mut String, v: i64) {
    let mut z = ((v << 1) ^ (v >> 63)) as u64;
    while z >= 0x20 {
        out.push(char::from((0x20 | (z & 0x1f)) as u8 + 63));
        z >>= 5;
    }
    out.push(char::from(z as u8 + 63));
}

/// Decodes at most `max_points` points; every point must be a valid WGS84 coordinate.
pub fn decode_polyline(s: &str, max_points: usize) -> Result<Vec<LatLng>, PolylineError> {
    let bytes = s.as_bytes();
    let mut points = Vec::new();
    let (mut i, mut lat, mut lng) = (0usize, 0i64, 0i64);
    while i < bytes.len() {
        if points.len() >= max_points {
            return Err(PolylineError::TooManyPoints(max_points));
        }
        lat += read_value(bytes, &mut i)?;
        if i >= bytes.len() {
            return Err(PolylineError::Truncated);
        }
        lng += read_value(bytes, &mut i)?;
        let p = LatLng::new(lat as f64 / FACTOR, lng as f64 / FACTOR);
        if !p.is_valid() {
            return Err(PolylineError::OutOfRange(points.len()));
        }
        points.push(p);
    }
    Ok(points)
}

fn read_value(bytes: &[u8], i: &mut usize) -> Result<i64, PolylineError> {
    let mut result: u64 = 0;
    let mut shift = 0u32;
    loop {
        let Some(&b) = bytes.get(*i) else {
            return Err(PolylineError::Truncated);
        };
        if !(63..=126).contains(&b) {
            return Err(PolylineError::InvalidChar(*i));
        }
        *i += 1;
        if shift > 35 {
            return Err(PolylineError::Overflow);
        }
        let chunk = u64::from(b - 63);
        result |= (chunk & 0x1f) << shift;
        shift += 5;
        if chunk < 0x20 {
            break;
        }
    }
    let v = (result >> 1) as i64;
    Ok(if result & 1 == 1 { !v } else { v })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn known_precision6_vector() {
        // Google's documented example scaled to precision 6.
        let pts = [
            LatLng::new(38.5, -120.2),
            LatLng::new(40.7, -120.95),
            LatLng::new(43.252, -126.453),
        ];
        let enc = encode_polyline(&pts).unwrap();
        assert_eq!(enc, "_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI");
        assert_eq!(decode_polyline(&enc, 10).unwrap(), pts);
    }

    #[test]
    fn round_trip_malaysia() {
        let pts = [
            LatLng::new(2.9871, 101.793),
            LatLng::new(2.986_6, 101.794_2),
            LatLng::new(-0.000_001, -0.000_001),
            LatLng::new(5.420_312, 100.329_871),
        ];
        let enc = encode_polyline(&pts).unwrap();
        let dec = decode_polyline(&enc, 100).unwrap();
        for (a, b) in pts.iter().zip(&dec) {
            assert!((a.lat - b.lat).abs() < 1e-9 && (a.lng - b.lng).abs() < 1e-9);
        }
        assert_eq!(encode_polyline(&dec).unwrap(), enc);
    }

    #[test]
    fn empty() {
        assert_eq!(encode_polyline(&[]).unwrap(), "");
        assert!(decode_polyline("", 1).unwrap().is_empty());
    }

    #[test]
    fn rejects_malformed() {
        assert_eq!(decode_polyline("_izlhA", 10), Err(PolylineError::Truncated));
        assert_eq!(decode_polyline("_izlh", 10), Err(PolylineError::Truncated));
        assert_eq!(
            decode_polyline("ab cd", 10),
            Err(PolylineError::InvalidChar(2))
        );
        assert_eq!(
            decode_polyline("~~~~~~~~~~~~~~~~~~~~?", 10),
            Err(PolylineError::Overflow)
        );
        // 1000 degrees latitude.
        let far = encode_polyline_unchecked(1_000.0, 0.0);
        assert_eq!(decode_polyline(&far, 10), Err(PolylineError::OutOfRange(0)));
        assert!(encode_polyline(&[LatLng::new(91.0, 0.0)]).is_err());
    }

    #[test]
    fn enforces_max_points() {
        let pts: Vec<_> = (0..10)
            .map(|i| LatLng::new(f64::from(i) * 0.001, 101.0))
            .collect();
        let enc = encode_polyline(&pts).unwrap();
        assert_eq!(decode_polyline(&enc, 10).unwrap().len(), 10);
        assert_eq!(
            decode_polyline(&enc, 9),
            Err(PolylineError::TooManyPoints(9))
        );
    }

    fn encode_polyline_unchecked(lat: f64, lng: f64) -> String {
        let mut s = String::new();
        push_value(&mut s, (lat * FACTOR) as i64);
        push_value(&mut s, (lng * FACTOR) as i64);
        s
    }
}
