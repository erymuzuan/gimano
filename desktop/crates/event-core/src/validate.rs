use std::collections::HashSet;

use chrono::{DateTime, Duration, FixedOffset, NaiveDate};
use route_core::{LatLng, decode_polyline};

use crate::encoding::b64u_decode;
use crate::error::{Error, ValidationError};
use crate::model::Event;

/// Limits from docs/events.md §4. Text lengths count Unicode scalar values.
pub mod limits {
    pub const MAX_EVENT_DAYS: i64 = 14;
    pub const MAX_STOPS: usize = 200;
    pub const MAX_SESSIONS: usize = 50;
    pub const MAX_ROUTES: usize = 20;
    pub const MAX_POLYLINE_POINTS: usize = 50_000;
    pub const MAX_FILE_BYTES: usize = 20 * 1024 * 1024;
    pub const MAX_FRAGMENT_CHARS: usize = 16_000;

    pub const TITLE_CHARS: usize = 120;
    pub const DESCRIPTION_CHARS: usize = 2000;
    pub const STOP_LABEL_CHARS: usize = 80;
    pub const STOP_NOTES_CHARS: usize = 500;
    pub const AUDIENCE_CHARS: usize = 40;
    pub const ROUTE_TITLE_CHARS: usize = 80;
    /// Not fixed by the contract; same as the event title.
    pub const SESSION_TITLE_CHARS: usize = 120;
    /// Not fixed by the contract; same as a stop label.
    pub const ALIAS_CHARS: usize = 80;
    pub const TZ_CHARS: usize = 64;
    /// Not fixed by the contract: the 14-day maximum, in minutes.
    pub const MAX_DWELL_MIN: u32 = 14 * 24 * 60;

    pub const EVENT_ID_BYTES: usize = 16;
    pub const ITEM_ID_BYTES: usize = 8;
}

use limits::*;

type R = Result<(), ValidationError>;

fn err(path: impl Into<String>, reason: &'static str) -> R {
    Err(ValidationError::new(path, reason))
}

fn text(path: &str, s: &str, min: usize, max: usize) -> R {
    let n = s.chars().count();
    if n < min || (min > 0 && s.trim().is_empty()) {
        return err(path, "is required");
    }
    if n > max {
        return err(path, "is too long");
    }
    Ok(())
}

fn opt_text(path: &str, s: &Option<String>, max: usize) -> R {
    s.as_deref().map_or(Ok(()), |s| text(path, s, 0, max))
}

fn id(path: &str, s: &str, bytes: usize) -> R {
    match b64u_decode(s) {
        Some(b) if b.len() == bytes => Ok(()),
        _ => err(path, "is not a valid id"),
    }
}

fn six_decimals(v: f64) -> bool {
    ((v * 1e6).round() / 1e6 - v).abs() <= 1e-9
}

fn coord(path: &str, p: LatLng) -> R {
    if !p.is_valid() {
        return err(path, "coordinate out of range");
    }
    if !six_decimals(p.lat) || !six_decimals(p.lng) {
        return err(path, "coordinate has more than 6 decimals");
    }
    Ok(())
}

fn window(path: &str, from: &DateTime<FixedOffset>, to: &DateTime<FixedOffset>) -> R {
    if to < from {
        return err(path, "ends before it starts");
    }
    Ok(())
}

fn tz(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= TZ_CHARS
        && !s.starts_with('/')
        && !s.ends_with('/')
        && !s.contains("//")
        && s.bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'/' | b'_' | b'-' | b'+'))
}

/// The variant rules (§5.5) with their own error codes, then every §4 rule.
pub(crate) fn check(e: &Event) -> Result<(), Error> {
    let stops_only = match &e.variant {
        None => false,
        Some(v) if v.is_stops_only() => true,
        Some(v) => return Err(Error::UnsupportedVariant(v.kind.clone())),
    };
    for (i, r) in e.routes.iter().enumerate() {
        match (stops_only, r.polyline.is_some()) {
            (true, true) => return Err(Error::VariantHasPolyline { route: i }),
            (false, false) => return Err(Error::MissingPolyline { route: i }),
            _ => {}
        }
    }
    validate(e)?;
    Ok(())
}

pub(crate) fn validate(e: &Event) -> R {
    let stops_only = match &e.variant {
        None => false,
        Some(v) if v.is_stops_only() => {
            if v.full_hash_bytes().is_none() {
                return err("variant.fullHash", "is not a SHA-256 hash");
            }
            true
        }
        Some(_) => return err("variant.kind", "is not supported"),
    };
    id("id", &e.id, EVENT_ID_BYTES)?;
    if e.rev == 0 {
        return err("rev", "must be 1 or more");
    }
    match (&e.parent, e.rev) {
        (None, 1) => {}
        (Some(_), 1) => return err("parent", "must be null for rev 1"),
        (None, _) => return err("parent", "is required after rev 1"),
        (Some(p), _) => {
            if b64u_decode(p).is_none_or(|b| b.len() != 32) {
                return err("parent", "is not a SHA-256 hash");
            }
        }
    }
    text("title", &e.title, 1, TITLE_CHARS)?;
    opt_text("description", &e.description, DESCRIPTION_CHARS)?;
    if !tz(&e.tz) {
        return err("tz", "is not an IANA time zone name");
    }
    window("end", &e.start, &e.end)?;
    if e.end.signed_duration_since(e.start) > Duration::days(MAX_EVENT_DAYS) {
        return err("end", "is more than 14 days after start");
    }
    if let Some(o) = &e.organizer {
        opt_text("organizer.alias", &o.alias, ALIAS_CHARS)?;
    }

    if e.stops.len() > MAX_STOPS {
        return err("stops", "has more than 200 stops");
    }
    let mut stop_ids = HashSet::new();
    for (i, s) in e.stops.iter().enumerate() {
        let p = |f: &str| format!("stops[{i}].{f}");
        id(&p("id"), &s.id, ITEM_ID_BYTES)?;
        if !stop_ids.insert(s.id.as_str()) {
            return err(p("id"), "is duplicated");
        }
        text(&p("label"), &s.label, 1, STOP_LABEL_CHARS)?;
        coord(&p("lat"), s.pin())?;
        if let Some(en) = s.entrance {
            coord(&p("entrance"), en)?;
        }
        if let Some(a) = &s.arrive {
            window(&p("arrive"), &a.from, &a.to)?;
        }
        if s.dwell_min.is_some_and(|d| d > MAX_DWELL_MIN) {
            return err(p("dwellMin"), "is too long");
        }
        opt_text(&p("notes"), &s.notes, STOP_NOTES_CHARS)?;
        opt_text(&p("audience"), &s.audience, AUDIENCE_CHARS)?;
    }
    let known_stop = |path: String, sid: &str| -> R {
        if stop_ids.contains(sid) {
            Ok(())
        } else {
            err(path, "refers to an unknown stop")
        }
    };

    if e.sessions.len() > MAX_SESSIONS {
        return err("sessions", "has more than 50 sessions");
    }
    let mut session_ids = HashSet::new();
    for (i, s) in e.sessions.iter().enumerate() {
        let p = |f: &str| format!("sessions[{i}].{f}");
        id(&p("id"), &s.id, ITEM_ID_BYTES)?;
        if !session_ids.insert(s.id.as_str()) {
            return err(p("id"), "is duplicated");
        }
        text(&p("title"), &s.title, 1, SESSION_TITLE_CHARS)?;
        window(&p("end"), &s.start, &s.end)?;
        if let Some(sid) = &s.stop_id {
            known_stop(p("stopId"), sid)?;
        }
    }

    if e.routes.len() > MAX_ROUTES {
        return err("routes", "has more than 20 routes");
    }
    let mut route_ids = HashSet::new();
    let mut points = 0usize;
    for (i, r) in e.routes.iter().enumerate() {
        let p = |f: &str| format!("routes[{i}].{f}");
        id(&p("id"), &r.id, ITEM_ID_BYTES)?;
        if !route_ids.insert(r.id.as_str()) {
            return err(p("id"), "is duplicated");
        }
        opt_text(&p("title"), &r.title, ROUTE_TITLE_CHARS)?;
        if let Some(day) = &r.day
            && (day.len() != 10 || NaiveDate::parse_from_str(day, "%Y-%m-%d").is_err())
        {
            return err(p("day"), "is not a YYYY-MM-DD date");
        }
        for (j, sid) in r.stop_ids.iter().enumerate() {
            known_stop(p(&format!("stopIds[{j}]")), sid)?;
        }
        let polyline = match (&r.polyline, stops_only) {
            (Some(_), true) => {
                return err(p("polyline"), "must be absent on a stops-only variant");
            }
            (None, false) => return err(p("polyline"), "is required"),
            (None, true) => "",
            (Some(pl), false) => pl.as_str(),
        };
        let budget = MAX_POLYLINE_POINTS - points;
        let decoded = decode_polyline(polyline, budget).map_err(|e| match e {
            route_core::PolylineError::TooManyPoints(_) => ValidationError::new(
                p("polyline"),
                "routes have more than 50 000 polyline points in total",
            ),
            _ => ValidationError::new(p("polyline"), "is not a valid precision-6 polyline"),
        })?;
        if !stops_only && decoded.len() < 2 {
            return err(p("polyline"), "needs at least 2 points");
        }
        points += decoded.len();
        for (j, l) in r.legs.iter().enumerate() {
            known_stop(p(&format!("legs[{j}].fromStopId")), &l.from_stop_id)?;
            known_stop(p(&format!("legs[{j}].toStopId")), &l.to_stop_id)?;
        }
    }
    Ok(())
}
