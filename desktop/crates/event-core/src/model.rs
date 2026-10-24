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
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub routes: Vec<Route>,
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

/// Default access expiry: `end` + 24 h, in UTC.
pub fn default_expiry(end: &DateTime<FixedOffset>) -> DateTime<Utc> {
    end.with_timezone(&Utc) + Duration::hours(24)
}

impl Event {
    /// Validates and serializes; these are the bytes that get signed.
    pub fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        self.validate()?;
        serde_json::to_vec(self).map_err(|e| Error::MalformedEvent(e.to_string()))
    }

    /// Parses and validates signed event bytes.
    pub fn from_bytes(bytes: &[u8]) -> Result<Event, Error> {
        let event: Event =
            serde_json::from_slice(bytes).map_err(|e| Error::MalformedEvent(e.to_string()))?;
        event.validate()?;
        Ok(event)
    }

    pub fn validate(&self) -> Result<(), ValidationError> {
        crate::validate::validate(self)
    }

    pub fn stop(&self, id: &str) -> Option<&Stop> {
        self.stops.iter().find(|s| s.id == id)
    }

    /// The "stops only" variant offered when a link would be too long (§5.1).
    pub fn without_route_geometry(&self) -> Event {
        Event {
            routes: Vec::new(),
            ..self.clone()
        }
    }
}
