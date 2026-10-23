using Gimano.Core.Contracts;

namespace Gimano.Core.Messaging;

/// <summary>RabbitMQ topology from docs/architecture.md ("Messaging").</summary>
public static class RoutingKeys
{
    public const string Exchange = "gimano.events";

    public const string LocationPing = "location.ping";
    public const string ReportCreated = "report.created";
    public const string ReportUpdated = "report.updated";
    public const string ReportVoted = "report.voted";
    public const string ReportExpired = "report.expired";
    public const string JamUpdated = "jam.updated";
    public const string JamCleared = "jam.cleared";
    public const string PlaceChanged = "place.changed";
    public const string FeedbackCreated = "feedback.created";
}

public sealed record ReportVotedEvent(Guid ReportId, bool StillThere, Guid UserId);
public sealed record ReportExpiredEvent(Guid ReportId);
public sealed record JamClearedEvent(Guid JamId);
public sealed record PlaceChangedEvent(string PlaceId);
/// <summary>Deliberately carries no user/device identifiers or screenshot; consumers load the row by id.</summary>
public sealed record FeedbackCreatedEvent(Guid FeedbackId, FeedbackCategory Category, DateTimeOffset CreatedAt);
