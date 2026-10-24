//! Ascent/descent from elevation samples.
//!
//! Method: hysteresis. A reference elevation starts at the first sample; a change is counted only
//! once a sample differs from the reference by at least [`ELEVATION_HYSTERESIS_M`], and the
//! reference then moves to that sample. This suppresses GPS/DEM noise on flat ground; climbs
//! smaller than the threshold are not counted. Results are rounded to whole metres.
//!
//! Missing data is never zero: fewer than two samples, or any missing / non-finite sample, gives
//! `None` ("Unavailable").

/// Smoothing threshold in metres.
pub const ELEVATION_HYSTERESIS_M: f64 = 5.0;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AscentDescent {
    pub ascent_m: u32,
    pub descent_m: u32,
}

pub fn ascent_descent(samples: &[Option<f64>]) -> Option<AscentDescent> {
    if samples.len() < 2 {
        return None;
    }
    let mut values = Vec::with_capacity(samples.len());
    for s in samples {
        match s {
            Some(v) if v.is_finite() => values.push(*v),
            _ => return None,
        }
    }
    let (mut reference, mut up, mut down) = (values[0], 0.0f64, 0.0f64);
    for &e in &values[1..] {
        let delta = e - reference;
        if delta >= ELEVATION_HYSTERESIS_M {
            up += delta;
            reference = e;
        } else if -delta >= ELEVATION_HYSTERESIS_M {
            down -= delta;
            reference = e;
        }
    }
    Some(AscentDescent {
        ascent_m: up.round().min(f64::from(u32::MAX)) as u32,
        descent_m: down.round().min(f64::from(u32::MAX)) as u32,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn s(v: &[f64]) -> Vec<Option<f64>> {
        v.iter().copied().map(Some).collect()
    }

    #[test]
    fn missing_is_none_never_zero() {
        assert_eq!(ascent_descent(&[]), None);
        assert_eq!(ascent_descent(&[Some(10.0)]), None);
        assert_eq!(ascent_descent(&[Some(10.0), None, Some(30.0)]), None);
        assert_eq!(ascent_descent(&[Some(10.0), Some(f64::NAN)]), None);
    }

    #[test]
    fn flat_noise_is_suppressed() {
        let r = ascent_descent(&s(&[50.0, 52.0, 49.0, 53.0, 50.5, 51.0])).unwrap();
        assert_eq!(
            r,
            AscentDescent {
                ascent_m: 0,
                descent_m: 0
            }
        );
    }

    #[test]
    fn climb_and_descent() {
        let r = ascent_descent(&s(&[10.0, 20.0, 40.0, 38.0, 100.0, 60.0, 20.0])).unwrap();
        assert_eq!(
            r,
            AscentDescent {
                ascent_m: 90,
                descent_m: 80
            }
        );
    }

    #[test]
    fn slow_climb_accumulates_once_threshold_crossed() {
        // Counted at 6 m; the final 4 m stays below the threshold.
        let r = ascent_descent(&s(&[0.0, 2.0, 4.0, 6.0, 8.0, 10.0])).unwrap();
        assert_eq!(r.ascent_m, 6);
        let r = ascent_descent(&s(&[0.0, 2.0, 4.0, 6.0, 8.0, 11.0])).unwrap();
        assert_eq!(r.ascent_m, 11);
    }
}
