using Gimano.Core.Contracts;
using NetTopologySuite.Geometries;

namespace Gimano.Core.Domain;

/// <summary>
/// A road or place complaint (docs/complaints.md). Public contribution under the reporter's pseudonym; the point is the
/// snapped road position or the place, stored rounded to ≈ 5 m. No per-user location history.
/// </summary>
public sealed class Issue
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public IssueKind Kind { get; set; }
    public string? Subtype { get; set; }
    public IssueState State { get; set; } = IssueState.Suspected;
    public IssueSource Source { get; set; }
    /// <summary>Road target: OSM way + segment index (road_segments.seq) + direction. Stable across imports.</summary>
    public long? WayId { get; set; }
    public int? Seq { get; set; }
    public IssueDirection Direction { get; set; }
    /// <summary>Turn target: the OSM node (v1: nearest junction; informational).</summary>
    public long? NodeId { get; set; }
    public string? PlaceId { get; set; }
    public string? Highway { get; set; }
    public string? RoadName { get; set; }
    public bool MajorRoad { get; set; }
    public required Point Location { get; set; }
    public Vehicle? Vehicle { get; set; }
    /// <summary>Reporter (public pseudonym, like a community place's owner). Null after DELETE /me.</summary>
    public Guid? CreatedBy { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
    public DateTimeOffset? ExpiresAt { get; set; }
    public DateTimeOffset? ConfirmedAt { get; set; }
    public DateTimeOffset? ClosedAt { get; set; }
    public DateTimeOffset LastEvidenceAt { get; set; } = DateTimeOffset.UtcNow;
    public double WeightedStill { get; set; }
    public double WeightedCleared { get; set; }
    public bool EmasSupport { get; set; }
    /// <summary>0–1, for display ("disahkan N pemandu" uses vote counts; this blends votes and traffic).</summary>
    public double Confidence { get; set; }
    public int StillVotes { get; set; }
    public int ClearedVotes { get; set; }
    /// <summary>Kind-specific public data: until, openingHours, navLat/navLng, edits, duplicateOf, trace, from/to…</summary>
    public Dictionary<string, string> Payload { get; set; } = [];
    public DateTimeOffset? ExportedAt { get; set; }
    /// <summary>Audit trail: "2026-10-23T10:00Z suspected→confirmed (votes 2.0)".</summary>
    public List<string> History { get; set; } = [];
}

/// <summary>One vote per pseudonym per issue. Unlinked when the issue closes (VoterId → HMAC(per-issue salt), time → day).</summary>
public sealed class IssueVote
{
    public long Id { get; set; }
    public Guid IssueId { get; set; }
    public Guid? VoterId { get; set; }
    public byte[]? VoterHash { get; set; }
    public IssueAnswer Answer { get; set; }
    public double Weight { get; set; }
    public bool IsReport { get; set; }
    public bool Emas { get; set; }
    /// <summary>Counted toward the voter's accuracy already (at confirm or close).</summary>
    public bool Scored { get; set; }
    public DateTimeOffset? CreatedAt { get; set; }
    public DateOnly Day { get; set; }
}

public sealed class IssueSalt
{
    public Guid IssueId { get; set; }
    public required byte[] Salt { get; set; }
    public DateTimeOffset? ResolvedAt { get; set; }
}

/// <summary>
/// Anonymous passive evidence: how many distinct (rotating, never stored) sessions crossed a segment in a direction during
/// an hour. Aggregated in the worker's memory from the transient ping stream; kept 15 days. No per-driver data.
/// </summary>
public sealed class SegmentTraversalCount
{
    public long WayId { get; set; }
    public int Seq { get; set; }
    public IssueDirection Direction { get; set; }
    public DateTimeOffset Hour { get; set; }
    public int Count { get; set; }
}
