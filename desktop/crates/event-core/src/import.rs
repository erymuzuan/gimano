//! Import decision (docs/events.md §7 steps 3–5) as a pure function of the opened pack, what is
//! already stored for the same event id, and the current time.
//!
//! Hashes here are **revision hashes** ([`crate::revision_hash`]): SHA-256 of the signed bytes,
//! or `variant.fullHash` for a stops-only variant (§5.5). A variant and the full event it was made
//! from therefore share one revision hash, which is what makes them "not a fork" (§7.4).

use chrono::{DateTime, Utc};

use crate::codes::safety_code;
use crate::encoding::b64u_array;
use crate::pack::OpenedPack;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StoredRevision {
    pub rev: u32,
    /// Revision hash: SHA-256 of the stored bytes, or `fullHash` when they are a stops-only variant.
    pub hash: [u8; 32],
    /// The stored bytes are a stops-only variant (no route geometry).
    pub stops_only: bool,
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
    /// Revision hash (`fullHash` for a stops-only variant).
    pub revision_hash: [u8; 32],
    pub stops_only: bool,
}

impl From<&OpenedPack> for ImportCandidate {
    fn from(p: &OpenedPack) -> Self {
        Self {
            rev: p.event.rev,
            parent: p.event.parent.as_deref().and_then(b64u_array),
            expires_at: p.event.expires_at,
            organizer_pub: p.organizer_pub,
            revision_hash: p.revision_hash,
            stops_only: p.event.is_stops_only(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ParentCheck {
    /// `parent` is the revision hash of the stored rev − 1 (or rev 1 with no parent).
    Matches,
    /// No stored rev − 1: revisions were missed. Allowed.
    MissedRevisions,
    /// A stored rev − 1 exists with a different revision hash. Warn, still allowed.
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
    /// "sudah diimport": the same revision is already stored (identical bytes, the full event
    /// when a stops-only variant arrives, or the same variant again). Nothing changes.
    AlreadyImported,
    /// "laluan penuh ditambah": the full event of a stored stops-only variant arrived. Replace the
    /// stored variant of `rev` with it silently (no prompt, not a fork).
    UpgradeToFull { rev: u32 },
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
    let same: Vec<_> = stored
        .revisions
        .iter()
        .filter(|r| r.rev == candidate.rev && r.hash == candidate.revision_hash)
        .collect();
    if !same.is_empty() {
        // Full over variant upgrades; anything else (identical, variant over full, variant over
        // the same variant) is already there.
        return if !candidate.stops_only && same.iter().all(|r| r.stops_only) {
            ImportDecision::UpgradeToFull { rev: candidate.rev }
        } else {
            ImportDecision::AlreadyImported
        };
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
            stops_only: false,
        }
    }

    fn variant(rev: u32, full_hash: u8, parent: Option<u8>) -> ImportCandidate {
        ImportCandidate {
            stops_only: true,
            ..cand(rev, full_hash, parent)
        }
    }

    fn stored(revs: &[(u32, u8)]) -> StoredEvent {
        StoredEvent {
            organizer_pub: PUB,
            revisions: revs
                .iter()
                .map(|&(rev, h)| StoredRevision {
                    rev,
                    hash: [h; 32],
                    stops_only: false,
                })
                .collect(),
        }
    }

    fn with_variant(mut s: StoredEvent, rev: u32) -> StoredEvent {
        for r in &mut s.revisions {
            if r.rev == rev {
                r.stops_only = true;
            }
        }
        s
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
        // Also for a variant claiming the stored full event's hash.
        assert_eq!(
            decide_import(&variant(1, 10, None), Some(&s), now()),
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

    // §5.5 / §7.4 stops-only variants. Hash 11 is the full rev 2; a variant of it carries
    // fullHash 11 as its revision hash.

    #[test]
    fn full_over_stored_variant_upgrades() {
        let s = with_variant(stored(&[(1, 10), (2, 11)]), 2);
        assert_eq!(
            decide_import(&cand(2, 11, Some(10)), Some(&s), now()),
            ImportDecision::UpgradeToFull { rev: 2 }
        );
    }

    #[test]
    fn variant_over_stored_full_is_already_imported() {
        assert_eq!(
            decide_import(
                &variant(2, 11, Some(10)),
                Some(&stored(&[(1, 10), (2, 11)])),
                now()
            ),
            ImportDecision::AlreadyImported
        );
    }

    #[test]
    fn same_variant_again_is_already_imported() {
        let s = with_variant(stored(&[(2, 11)]), 2);
        assert_eq!(
            decide_import(&variant(2, 11, Some(10)), Some(&s), now()),
            ImportDecision::AlreadyImported
        );
    }

    #[test]
    fn variant_of_other_bytes_is_a_fork() {
        // Same rev, but its fullHash is not the stored full event: a real fork.
        assert_eq!(
            decide_import(&variant(2, 12, Some(10)), Some(&stored(&[(2, 11)])), now()),
            ImportDecision::Fork { rev: 2 }
        );
        let s = with_variant(stored(&[(2, 11)]), 2);
        assert_eq!(
            decide_import(&cand(2, 12, Some(10)), Some(&s), now()),
            ImportDecision::Fork { rev: 2 }
        );
    }

    #[test]
    fn matching_hash_with_other_rev_is_not_an_upgrade() {
        let s = with_variant(stored(&[(2, 11)]), 2);
        assert_eq!(
            decide_import(&cand(3, 11, Some(11)), Some(&s), now()),
            ImportDecision::Newer {
                stored_rev: 2,
                parent: ParentCheck::Matches
            }
        );
    }

    #[test]
    fn parent_check_uses_the_variants_full_hash() {
        let s = with_variant(stored(&[(1, 10), (2, 11)]), 2);
        assert_eq!(
            decide_import(&cand(3, 12, Some(11)), Some(&s), now()),
            ImportDecision::Newer {
                stored_rev: 2,
                parent: ParentCheck::Matches
            }
        );
        // A rev-3 variant over a stored rev-2 variant, too.
        assert_eq!(
            decide_import(&variant(3, 12, Some(11)), Some(&s), now()),
            ImportDecision::Newer {
                stored_rev: 2,
                parent: ParentCheck::Matches
            }
        );
    }

    #[test]
    fn older_variant_keeps_stored() {
        assert_eq!(
            decide_import(&variant(1, 10, None), Some(&stored(&[(2, 11)])), now()),
            ImportDecision::Older { stored_rev: 2 }
        );
    }
}
