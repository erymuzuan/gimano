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
}

public sealed record LocationPingEvent(Guid UserId, double Lat, double Lng, double SpeedKmh, double Heading, DateTimeOffset Ts, string? Mood = null);
public sealed record ReportVotedEvent(Guid ReportId, bool StillThere, Guid UserId);
public sealed record ReportExpiredEvent(Guid ReportId);
public sealed record JamClearedEvent(Guid JamId);
public sealed record PlaceChangedEvent(string PlaceId);
