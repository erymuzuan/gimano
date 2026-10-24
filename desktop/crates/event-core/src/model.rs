//! Event JSON v1 (docs/events.md §4). Field names are exact; absent optional fields are omitted
//! on write (except `parent`, written as `null` for rev 1) and accepted as missing or `null` on read.

use chrono::{DateTime, Duration, FixedOffset, Utc};
use route_core::{LatLng, Route};
use serde::{Deserialize, Serialize};

use crate::error::{Error, ValidationError};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Event {
    /// b64u of 16 random bytes; the same for every revision.
    pub id: String,
    pub rev: u32,
    /// b64u(SHA-256(event bytes of rev − 1)); `None` for rev 1.
    pub parent: Option<String>,
    pub published_at: DateTime<Utc>,
    pub template: Template,
    pub title: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    pub lang: Lang,
    /// IANA time zone, e.g. `Asia/Kuala_Lumpur`.
    pub tz: String,
    pub start: DateTime<FixedOffset>,
    pub end: DateTime<FixedOffset>,
    pub expires_at: DateTime<Utc>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub organizer: Option<Organizer>,
    #[serde(default)]
    pub stops: Vec<Stop>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub sessions: Vec<Session>,
    /// Guest info blocks (§4.1), plain text, shown in order after the agenda.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub info: Vec<InfoBlock>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub routes: Vec<Route>,
    /// Present only on a stops-only variant (§5.5); always written last.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub variant: Option<Variant>,
}

/// `variant.kind` of a stops-only link (§5.5).
pub const VARIANT_STOPS_ONLY: &str = "stops-only";

/// `"variant": {"kind": "stops-only", "fullHash": "<b64u SHA-256 of the full event bytes>"}`.
/// `kind` stays a string so an unknown kind is reported as such (`unsupported_variant`) rather
/// than as malformed JSON.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Variant {
    pub kind: String,
    pub full_hash: String,
}

impl Variant {
    pub fn stops_only(full_hash: [u8; 32]) -> Self {
        Variant {
            kind: VARIANT_STOPS_ONLY.into(),
            full_hash: crate::encoding::b64u(&full_hash),
        }
    }

    pub fn is_stops_only(&self) -> bool {
        self.kind == VARIANT_STOPS_ONLY
    }

    /// The decoded `fullHash`, if it is a valid SHA-256 value.
    pub fn full_hash_bytes(&self) -> Option<[u8; 32]> {
        crate::encoding::b64u_array(&self.full_hash)
    }
}

/// Unknown values from newer writers read as `Other` (display only).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Template {
    Kenduri,
    Dinner,
    Company,
    Ride,
    Convoy,
    Trip,
    Meeting,
    Seminar,
    Picnic,
    Hike,
    #[serde(other)]
    Other,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Lang {
    Ms,
    En,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Organizer {
    /// Event-only alias, never a real account name.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub alias: Option<String>,
}

/// Unknown values from newer writers read as `Other` (display only).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum StopKind {
    Parking,
    Dropoff,
    Entrance,
    Venue,
    Meal,
    Checkpoint,
    Fuel,
    Water,
    Rest,
    Prayer,
    Accommodation,
    Start,
    Finish,
    #[serde(other)]
    Other,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TimeWindow {
    pub from: DateTime<FixedOffset>,
    pub to: DateTime<FixedOffset>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Stop {
    /// b64u of 8 random bytes.
    pub id: String,
    pub kind: StopKind,
    pub label: String,
    pub lat: f64,
    pub lng: f64,
    /// Vehicle entrance; navigate here when present.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub entrance: Option<LatLng>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub arrive: Option<TimeWindow>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub dwell_min: Option<u32>,
    /// Guest-visible.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub notes: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub audience: Option<String>,
}

impl Stop {
    pub fn pin(&self) -> LatLng {
        LatLng::new(self.lat, self.lng)
    }

    /// Where navigation goes: the entrance if present, else the pin (§7.7).
    pub fn navigation_target(&self) -> LatLng {
        self.entrance.unwrap_or_else(|| self.pin())
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Session {
    pub id: String,
    pub title: String,
    pub start: DateTime<FixedOffset>,
    pub end: DateTime<FixedOffset>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub stop_id: Option<String>,
}

/// `info[].kind` (§4.1). Unknown values from newer writers read as `Other`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum InfoKind {
    Dress,
    Safety,
    Documents,
    Bring,
    Contact,
    #[serde(other)]
    Other,
}

/// One guest info block (§4.1): plain guest-visible text, 1–1000 code points, newlines allowed.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InfoBlock {
    pub kind: InfoKind,
    pub text: String,
}

/// Default access expiry: `end` + 24 h, in UTC.
pub fn default_expiry(end: &DateTime<FixedOffset>) -> DateTime<Utc> {
    end.with_timezone(&Utc) + Duration::hours(24)
}

impl Event {
    /// Validates and serializes; these are the bytes that get signed. Writers sign
    /// [`Event::simplified`] (§5.4); [`crate::seal`] does that for you.
    pub fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        crate::validate::check(self)?;
        serde_json::to_vec(self).map_err(|e| Error::MalformedEvent(e.to_string()))
    }

    /// Parses and validates signed event bytes (a full event or a stops-only variant).
    pub fn from_bytes(bytes: &[u8]) -> Result<Event, Error> {
        let event: Event =
            serde_json::from_slice(bytes).map_err(|e| Error::MalformedEvent(e.to_string()))?;
        crate::validate::check(&event)?;
        Ok(event)
    }

    /// Every rule as a [`ValidationError`]. [`Event::from_bytes`] and [`Event::to_bytes`] report
    /// the variant rules (§5.5) with their own [`Error`] codes instead.
    pub fn validate(&self) -> Result<(), ValidationError> {
        crate::validate::validate(self)
    }

    /// A stops-only variant (§5.5), as opposed to a full event.
    pub fn is_stops_only(&self) -> bool {
        self.variant.as_ref().is_some_and(Variant::is_stops_only)
    }

    /// Copy with every route polyline simplified with Douglas–Peucker at 5 m (§5.4). Distances and
    /// durations keep the routing values. Polylines that do not decode are left to validation.
    pub fn simplified(&self) -> Event {
        let mut e = self.clone();
        for r in &mut e.routes {
            if let Some(p) = &r.polyline
                && let Ok(s) = route_core::simplify_polyline(
                    p,
                    route_core::EVENT_SIMPLIFY_TOLERANCE_M,
                    crate::validate::limits::MAX_POLYLINE_POINTS,
                )
            {
                r.polyline = Some(s);
            }
        }
        e
    }

    pub fn stop(&self, id: &str) -> Option<&Stop> {
        self.stops.iter().find(|s| s.id == id)
    }
}

/// The stops-only variant of signed full event bytes (§5.5): the same event with every route's
/// `polyline` removed and `variant: {kind: "stops-only", fullHash: SHA-256(full bytes)}` appended
/// as the last field. Deterministic for given full bytes. Sign it with the same organizer key.
pub fn stops_only_variant(full_event_bytes: &[u8]) -> Result<Vec<u8>, Error> {
    let mut v = Event::from_bytes(full_event_bytes)?;
    if v.variant.is_some() {
        return Err(Error::NotFullEvent);
    }
    for r in &mut v.routes {
        r.polyline = None;
    }
    v.variant = Some(Variant::stops_only(crate::revision::bytes_hash(
        full_event_bytes,
    )));
    v.to_bytes()
}
