using Gimano.Core.Contracts;

namespace Gimano.Core.Domain;

// Back office: crash reports, sealed contact details, agent access and the reporter Inbox (docs/back-office.md).
// Nothing here carries a user id except InboxItem (the reporter's own Inbox, deleted with the account).

/// <summary>Crash events with the same <see cref="Signature"/> (exception + top app frames).</summary>
public sealed class CrashGroup
{
    /// <summary>sha256 hex of platform + exception + top 5 app frames, line numbers removed.</summary>
    public required string Signature { get; set; }
    public required string Platform { get; set; }
    public CrashKind Kind { get; set; }
    public required string Exception { get; set; }
    /// <summary>The latest event's message (scrubbed on the phone).</summary>
    public string? Message { get; set; }
    /// <summary>The first app frame, for list titles.</summary>
    public string? TopFrame { get; set; }
    public CrashStatus Status { get; set; } = CrashStatus.New;
    public Severity? Severity { get; set; }
    public int EventCount { get; set; }
    public DateTimeOffset FirstSeenAt { get; set; }
    public DateTimeOffset LastSeenAt { get; set; }
    public string? FirstVersion { get; set; }
    public string? LatestVersion { get; set; }
    public int? LatestBuild { get; set; }
    /// <summary>App build number the fix shipped in; an event from this build or later reopens the group.</summary>
    public int? FixedInBuild { get; set; }
    public string? FixedInVersion { get; set; }
    /// <summary>Issue or PR on GitHub.</summary>
    public string? ExternalUrl { get; set; }
    public string? TriageNote { get; set; }
    /// <summary>Root-cause analysis written by an agent (markdown).</summary>
    public string? Analysis { get; set; }
    public string? AnalysisBy { get; set; }
    public DateTimeOffset UpdatedAt { get; set; }
}

/// <summary>One crash report. No user id, device token or location: retention 90 days, then only the group counts remain.</summary>
public sealed class CrashEvent
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public required string Signature { get; set; }
    public DateTimeOffset OccurredAt { get; set; }
    public DateTimeOffset ReceivedAt { get; set; }
    public CrashKind Kind { get; set; }
    public required string Exception { get; set; }
    public string? Message { get; set; }
    public required string Stack { get; set; }
    public string? Threads { get; set; }
    public string? Screen { get; set; }
    public long? UptimeS { get; set; }
    public required DeviceInfo Device { get; set; }
    public bool HasContact { get; set; }
}

/// <summary>
/// Contact details volunteered on ONE feedback item or crash report, AES-GCM sealed with a key from host config.
/// Only maintainers can unseal it (audited); agents and every API response see the flags only.
/// </summary>
public sealed class ReportContact
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public Guid? FeedbackId { get; set; }
    public Guid? CrashEventId { get; set; }
    /// <summary>nonce (12) ‖ tag (16) ‖ ciphertext of the JSON {email, whatsapp}.</summary>
    public required byte[] Sealed { get; set; }
    public bool HasEmail { get; set; }
    public bool HasWhatsapp { get; set; }
    public DateTimeOffset CreatedAt { get; set; }
    /// <summary>min(created + 90 d, closed + 30 d). The worker deletes the row after this.</summary>
    public DateTimeOffset ExpiresAt { get; set; }
}

/// <summary>A bearer token for an AI agent (MCP / admin API). Only the sha256 is stored.</summary>
public sealed class AgentToken
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public required string Name { get; set; }
    /// <summary>First characters of the token, to recognise it in lists ("agt_3F9k…").</summary>
    public required string Prefix { get; set; }
    public required string TokenHash { get; set; }
    public string[] Scopes { get; set; } = [];
    public required string CreatedBy { get; set; }
    public DateTimeOffset CreatedAt { get; set; }
    public DateTimeOffset ExpiresAt { get; set; }
    public DateTimeOffset? RevokedAt { get; set; }
    public DateTimeOffset? LastUsedAt { get; set; }
}

/// <summary>Who did what in the back office. Never stores returned content, contact details or request bodies.</summary>
public sealed class AuditEntry
{
    public long Id { get; set; }
    public DateTimeOffset At { get; set; }
    public required string Actor { get; set; }
    /// <summary>human | agent</summary>
    public required string ActorKind { get; set; }
    public required string Action { get; set; }
    public required string TargetType { get; set; }
    public string? TargetId { get; set; }
    /// <summary>A short summary ("status=triaged severity=s2") or the reason given for revealing a contact.</summary>
    public string? Detail { get; set; }
}

public sealed record AgentRunStep(DateTimeOffset At, string Title, string? Detail);

/// <summary>An agent working on a feedback item or crash group: its steps, branch and PR.</summary>
public sealed class AgentRun
{
    public int Id { get; set; }
    public required string Title { get; set; }
    /// <summary>feedback | crash</summary>
    public required string TargetType { get; set; }
    public required string TargetId { get; set; }
    public AgentRunStatus Status { get; set; } = AgentRunStatus.Running;
    public required string Agent { get; set; }
    public DateTimeOffset StartedAt { get; set; }
    public DateTimeOffset UpdatedAt { get; set; }
    public string? Branch { get; set; }
    public string? PrUrl { get; set; }
    public string? Summary { get; set; }
    public List<AgentRunStep> Steps { get; set; } = [];
}

/// <summary>A reply to a feedback reporter, delivered as an Inbox item once a maintainer approves it.</summary>
public sealed class ReplyDraft
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public Guid FeedbackId { get; set; }
    public required string Message { get; set; }
    public FeedbackStatus? NewStatus { get; set; }
    public ReplyStatus Status { get; set; } = ReplyStatus.Draft;
    public required string DraftedBy { get; set; }
    public DateTimeOffset CreatedAt { get; set; }
    public string? DecidedBy { get; set; }
    public DateTimeOffset? DecidedAt { get; set; }
}

/// <summary>News for a user about their own public contributions and feedback (docs/api.md "Inbox"). Kept 90 days.</summary>
public sealed class InboxItem
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public Guid UserId { get; set; }
    public required string Kind { get; set; }
    public required string Title { get; set; }
    public required string Body { get; set; }
    public string? RefType { get; set; }
    public string? RefId { get; set; }
    public int Count { get; set; } = 1;
    public DateTimeOffset CreatedAt { get; set; }
}

/// <summary>A shipped app build: maps version/build to the git commit (and its symbol files) for agents and regression checks.</summary>
public sealed class AppBuild
{
    public int Id { get; set; }
    /// <summary>android | ios</summary>
    public required string Platform { get; set; }
    public required string Version { get; set; }
    public int Build { get; set; }
    public required string Commit { get; set; }
    /// <summary>Where the R8 mapping.txt / dSYM lives (CI artifact URL or path).</summary>
    public string? SymbolsUrl { get; set; }
    public DateTimeOffset ReleasedAt { get; set; }
}
