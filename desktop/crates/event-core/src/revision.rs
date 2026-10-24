use chrono::{DateTime, Utc};
use sha2::{Digest, Sha256};

use crate::encoding::b64u;
use crate::model::Event;

/// SHA-256 of the exact signed event bytes.
pub fn revision_hash(event_bytes: &[u8]) -> [u8; 32] {
    Sha256::digest(event_bytes).into()
}

pub fn revision_hash_b64u(event_bytes: &[u8]) -> String {
    b64u(&revision_hash(event_bytes))
}

impl Event {
    /// Draft of the next revision: `rev + 1`, `parent` = hash of the previous signed bytes.
    pub fn next_revision(&self, previous_bytes: &[u8], published_at: DateTime<Utc>) -> Event {
        Event {
            rev: self.rev.saturating_add(1),
            parent: Some(revision_hash_b64u(previous_bytes)),
            published_at,
            ..self.clone()
        }
    }
}
