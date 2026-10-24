use chrono::{DateTime, Utc};
use sha2::{Digest, Sha256};

use crate::encoding::b64u;
use crate::model::{Event, Variant};

/// SHA-256 of the exact bytes.
pub fn bytes_hash(bytes: &[u8]) -> [u8; 32] {
    Sha256::digest(bytes).into()
}

/// The revision hash (§7, what the next revision's `parent` refers to): SHA-256 of the exact
/// signed event bytes, except for a stops-only variant (§5.5), whose revision hash is its
/// `variant.fullHash`. Meant for bytes that already passed [`Event::from_bytes`].
pub fn revision_hash(event_bytes: &[u8]) -> [u8; 32] {
    #[derive(serde::Deserialize)]
    struct Probe {
        #[serde(default)]
        variant: Option<Variant>,
    }
    if let Ok(Probe { variant: Some(v) }) = serde_json::from_slice::<Probe>(event_bytes)
        && v.is_stops_only()
        && let Some(h) = v.full_hash_bytes()
    {
        return h;
    }
    bytes_hash(event_bytes)
}

pub fn revision_hash_b64u(event_bytes: &[u8]) -> String {
    b64u(&revision_hash(event_bytes))
}

impl Event {
    /// Draft of the next revision: `rev + 1`, `parent` = revision hash of the previous signed
    /// bytes (their `fullHash` if they were a stops-only variant). `self` should be the full event.
    pub fn next_revision(&self, previous_bytes: &[u8], published_at: DateTime<Utc>) -> Event {
        Event {
            rev: self.rev.saturating_add(1),
            parent: Some(revision_hash_b64u(previous_bytes)),
            published_at,
            variant: None,
            ..self.clone()
        }
    }
}
