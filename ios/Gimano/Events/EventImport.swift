import Foundation

// Import decision (docs/events.md §7 steps 3–5) as a pure function of the opened pack, what is already stored
// for the same event id, and the current time. Mirrors `event_core::import::decide_import`.
//
// Hashes are **revision hashes**: SHA-256 of the signed bytes, or `variant.fullHash` for a stops-only variant (§5.5).
// A variant and the full event it was made from share one revision hash, so they are never a fork (§7.4).

struct StoredEventRevision: Equatable, Sendable {
    var rev: UInt32
    /// Revision hash (`fullHash` when the stored bytes are a stops-only variant).
    var hash: [UInt8]
    /// The stored bytes are a stops-only variant (no route geometry).
    var stopsOnly = false
}

/// The local record for one event id: the pinned organizer key and every revision kept
/// (two entries share a `rev` after the user kept both sides of a fork).
struct StoredEventRecord: Equatable, Sendable {
    var organizerPub: [UInt8]
    var revisions: [StoredEventRevision]
}

struct EventImportCandidate: Equatable, Sendable {
    var rev: UInt32
    var parent: [UInt8]?
    var expiresAt: Date
    var organizerPub: [UInt8]
    /// Revision hash (`fullHash` for a stops-only variant).
    var revisionHash: [UInt8]
    var stopsOnly: Bool

    init(rev: UInt32, parent: [UInt8]?, expiresAt: Date, organizerPub: [UInt8], revisionHash: [UInt8], stopsOnly: Bool = false) {
        self.rev = rev
        self.parent = parent
        self.expiresAt = expiresAt
        self.organizerPub = organizerPub
        self.revisionHash = revisionHash
        self.stopsOnly = stopsOnly
    }

    init(_ p: OpenedEventPack) {
        self.init(rev: p.event.rev, parent: p.event.parent.flatMap { B64u.decode($0) }.flatMap { $0.count == 32 ? $0 : nil },
                  expiresAt: p.event.expiresAt.date, organizerPub: p.organizerPub, revisionHash: p.revisionHash,
                  stopsOnly: p.isStopsOnly)
    }
}

enum EventParentCheck: Equatable, Sendable {
    /// `parent` is the hash of the stored rev − 1 (or rev 1 with no parent).
    case matches
    /// No stored rev − 1: revisions were missed. Allowed.
    case missedRevisions
    /// A stored rev − 1 exists with a different hash. Warn, still allowed.
    case mismatch
}

enum EventImportDecision: Equatable, Sendable {
    /// "Acara telah tamat": do not import.
    case expired
    /// First time this id is seen: show alias + safety code, pin the organizer key on confirm.
    case new(safetyCode: String)
    /// "penganjur berbeza": reject.
    case differentOrganizer
    /// "sudah diimport": the same revision is already stored (identical bytes, the full event when a stops-only
    /// variant arrives, or the same variant again). Nothing changes.
    case alreadyImported
    /// "Laluan penuh ditambah": the full event of a stored stops-only variant arrived. Replace the stored variant of
    /// `rev` with it silently (no prompt, not a fork).
    case upgradeToFull(rev: UInt32)
    /// "versi lebih lama": keep the stored revision.
    case older(storedRev: UInt32)
    /// Same rev, different bytes: warn, keep both, the user chooses.
    case fork(rev: UInt32)
    /// Replace the stored latest revision.
    case newer(storedRev: UInt32, parent: EventParentCheck)

    /// Short code, as in the vectors' `expected.decision`.
    var code: String {
        switch self {
        case .expired: "expired"
        case .new: "new"
        case .differentOrganizer: "different_organizer"
        case .alreadyImported: "already_imported"
        case .upgradeToFull: "upgrade_to_full"
        case .older: "older"
        case .fork: "fork"
        case .newer: "newer"
        }
    }

    /// The pack may be stored (after the user confirms; an upgrade to the full route without asking).
    var canImport: Bool {
        switch self {
        case .new, .fork, .newer, .upgradeToFull: true
        default: false
        }
    }
}

enum EventImport {
    /// Expired at or after `expiresAt`.
    static func isExpired(_ expiresAt: Date, now: Date) -> Bool { now >= expiresAt }

    /// `stored` must be the record for the candidate's event id, if any.
    static func decide(_ c: EventImportCandidate, stored: StoredEventRecord?, now: Date) -> EventImportDecision {
        if isExpired(c.expiresAt, now: now) { return .expired }
        guard let stored else { return .new(safetyCode: EventCodes.safetyCode(c.organizerPub)) }
        if stored.organizerPub != c.organizerPub { return .differentOrganizer }
        let same = stored.revisions.filter { $0.rev == c.rev && $0.hash == c.revisionHash }
        if !same.isEmpty {
            // Full over variant upgrades; anything else (identical, variant over full, the same variant) is already there.
            return !c.stopsOnly && same.allSatisfy(\.stopsOnly) ? .upgradeToFull(rev: c.rev) : .alreadyImported
        }
        let storedRev = stored.revisions.map(\.rev).max() ?? 0
        if c.rev < storedRev { return .older(storedRev: storedRev) }
        if c.rev == storedRev { return .fork(rev: storedRev) }
        let parent: EventParentCheck
        if c.rev == 1 && c.parent == nil {
            parent = .matches
        } else {
            let previous = stored.revisions.filter { $0.rev == c.rev - 1 }
            if previous.isEmpty {
                parent = .missedRevisions
            } else if previous.contains(where: { $0.hash == c.parent }) {
                parent = .matches
            } else {
                parent = .mismatch
            }
        }
        return .newer(storedRev: storedRev, parent: parent)
    }
}
