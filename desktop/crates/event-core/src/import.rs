//! Import decision (docs/events.md §7 steps 3–5) as a pure function of the opened pack, what is
//! already stored for the same event id, and the current time.

use chrono::{DateTime, Utc};

use crate::codes::safety_code;
use crate::encoding::b64u_array;
use crate::pack::OpenedPack;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StoredRevision {
    pub rev: u32,
    pub hash: [u8; 32],
}

/// The local record for one event id: the pinned organizer key and every revision kept
/// (two entries share a `rev` after the user kept both sides of a fork).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoredEvent {
    pub organizer_pub: [u8; 32],
    pub revisions: Vec<StoredRevision>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImportCandidate {
    pub rev: u32,
    pub parent: Option<[u8; 32]>,
    pub expires_at: DateTime<Utc>,
    pub organizer_pub: [u8; 32],
    pub revision_hash: [u8; 32],
}

impl From<&OpenedPack> for ImportCandidate {
    fn from(p: &OpenedPack) -> Self {
        Self {
            rev: p.event.rev,
            parent: p.event.parent.as_deref().and_then(b64u_array),
            expires_at: p.event.expires_at,
            organizer_pub: p.organizer_pub,
            revision_hash: p.revision_hash,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ParentCheck {
    /// `parent` is the hash of the stored rev − 1 (or rev 1 with no parent).
    Matches,
    /// No stored rev − 1: revisions were missed. Allowed.
    MissedRevisions,
    /// A stored rev − 1 exists with a different hash. Warn, still allowed.
    Mismatch,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ImportDecision {
    /// "Acara telah tamat": do not import.
    Expired,
    /// First time this id is seen: show alias + safety code, pin `organizer_pub` on confirm.
    New { safety_code: String },
    /// "penganjur berbeza": reject.
    DifferentOrganizer,
    /// Identical bytes already stored: nothing to do.
    AlreadyImported,
    /// "versi lebih lama": keep the stored revision.
    Older { stored_rev: u32 },
    /// Same rev, different bytes: warn, keep both, the user chooses.
    Fork { rev: u32 },
    /// Replace the stored latest revision.
    Newer {
        stored_rev: u32,
        parent: ParentCheck,
    },
}

/// Expired at or after `expires_at`.
pub fn is_expired(expires_at: &DateTime<Utc>, now: &DateTime<Utc>) -> bool {
    now >= expires_at
}

/// `stored` must be the record for the candidate's event id, if any.
pub fn decide_import(
    candidate: &ImportCandidate,
    stored: Option<&StoredEvent>,
    now: DateTime<Utc>,
) -> ImportDecision {
    if is_expired(&candidate.expires_at, &now) {
        return ImportDecision::Expired;
    }
    let Some(stored) = stored else {
        return ImportDecision::New {
            safety_code: safety_code(&candidate.organizer_pub),
        };
    };
    if stored.organizer_pub != candidate.organizer_pub {
        return ImportDecision::DifferentOrganizer;
    }
    if stored
        .revisions
        .iter()
        .any(|r| r.hash == candidate.revision_hash)
    {
        return ImportDecision::AlreadyImported;
    }
    let stored_rev = stored.revisions.iter().map(|r| r.rev).max().unwrap_or(0);
    if candidate.rev < stored_rev {
        return ImportDecision::Older { stored_rev };
    }
    if candidate.rev == stored_rev {
        return ImportDecision::Fork { rev: stored_rev };
    }
    let parent = match (candidate.rev, candidate.parent) {
        (1, None) => ParentCheck::Matches,
        (_, parent) => {
            let previous: Vec<_> = stored
                .revisions
                .iter()
                .filter(|r| r.rev == candidate.rev - 1)
                .collect();
            if previous.is_empty() {
                ParentCheck::MissedRevisions
            } else if previous.iter().any(|r| Some(r.hash) == parent) {
                ParentCheck::Matches
            } else {
                ParentCheck::Mismatch
            }
        }
    };
    ImportDecision::Newer { stored_rev, parent }
}

#[cfg(test)]
mod tests {
    use super::*;

    const PUB: [u8; 32] = [1; 32];
    const OTHER_PUB: [u8; 32] = [2; 32];

    fn now() -> DateTime<Utc> {
        "2026-11-01T00:00:00Z".parse().unwrap()
    }

    fn cand(rev: u32, hash: u8, parent: Option<u8>) -> ImportCandidate {
        ImportCandidate {
            rev,
            parent: parent.map(|p| [p; 32]),
            expires_at: "2026-11-16T08:00:00Z".parse().unwrap(),
            organizer_pub: PUB,
            revision_hash: [hash; 32],
        }
    }

    fn stored(revs: &[(u32, u8)]) -> StoredEvent {
        StoredEvent {
            organizer_pub: PUB,
            revisions: revs
                .iter()
                .map(|&(rev, h)| StoredRevision { rev, hash: [h; 32] })
                .collect(),
        }
    }

    #[test]
    fn new_event_shows_safety_code() {
        match decide_import(&cand(1, 10, None), None, now()) {
            ImportDecision::New { safety_code: c } => assert_eq!(c, safety_code(&PUB)),
            d => panic!("{d:?}"),
        }
    }

    #[test]
    fn expired_wins_over_everything() {
        let mut c = cand(3, 10, Some(9));
        c.expires_at = now();
        assert_eq!(decide_import(&c, None, now()), ImportDecision::Expired);
        assert_eq!(
            decide_import(&c, Some(&stored(&[(2, 9)])), now()),
            ImportDecision::Expired
        );
        c.expires_at = now() + chrono::Duration::seconds(1);
        assert_ne!(decide_import(&c, None, now()), ImportDecision::Expired);
    }

    #[test]
    fn different_organizer_rejected() {
        let mut s = stored(&[(1, 10)]);
        s.organizer_pub = OTHER_PUB;
        assert_eq!(
            decide_import(&cand(2, 11, Some(10)), Some(&s), now()),
            ImportDecision::DifferentOrganizer
        );
    }

    #[test]
    fn already_imported() {
        assert_eq!(
            decide_import(
                &cand(2, 11, Some(10)),
                Some(&stored(&[(1, 10), (2, 11)])),
                now()
            ),
            ImportDecision::AlreadyImported
        );
    }

    #[test]
    fn older_keeps_stored() {
        assert_eq!(
            decide_import(&cand(1, 9, None), Some(&stored(&[(1, 10), (2, 11)])), now()),
            ImportDecision::Older { stored_rev: 2 }
        );
    }

    #[test]
    fn same_rev_different_bytes_is_fork() {
        assert_eq!(
            decide_import(
                &cand(2, 12, Some(10)),
                Some(&stored(&[(1, 10), (2, 11)])),
                now()
            ),
            ImportDecision::Fork { rev: 2 }
        );
    }

    #[test]
    fn newer_with_matching_parent() {
        assert_eq!(
            decide_import(
                &cand(3, 12, Some(11)),
                Some(&stored(&[(1, 10), (2, 11)])),
                now()
            ),
            ImportDecision::Newer {
                stored_rev: 2,
                parent: ParentCheck::Matches
            }
        );
    }

    #[test]
    fn newer_parent_matches_either_fork_side() {
        assert_eq!(
            decide_import(
                &cand(3, 13, Some(12)),
                Some(&stored(&[(2, 11), (2, 12)])),
                now()
            ),
            ImportDecision::Newer {
                stored_rev: 2,
                parent: ParentCheck::Matches
            }
        );
    }

    #[test]
    fn newer_with_parent_mismatch_warns() {
        assert_eq!(
            decide_import(&cand(3, 12, Some(99)), Some(&stored(&[(2, 11)])), now()),
            ImportDecision::Newer {
                stored_rev: 2,
                parent: ParentCheck::Mismatch
            }
        );
    }

    #[test]
    fn newer_after_missed_revisions() {
        assert_eq!(
            decide_import(&cand(5, 12, Some(40)), Some(&stored(&[(2, 11)])), now()),
            ImportDecision::Newer {
                stored_rev: 2,
                parent: ParentCheck::MissedRevisions
            }
        );
    }

    #[test]
    fn pinned_without_revisions() {
        assert_eq!(
            decide_import(&cand(1, 12, None), Some(&stored(&[])), now()),
            ImportDecision::Newer {
                stored_rev: 0,
                parent: ParentCheck::Matches
            }
        );
    }
}
