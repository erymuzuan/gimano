using Gimano.Core.Contracts;
using Gimano.Core.Geo;

namespace Gimano.Core.Alerts;

/// <summary>Alert lifecycle rules from docs/architecture.md ("Alert lifecycle").</summary>
public static class AlertRules
{
    public const double DuplicateRadiusM = 150;
    public const double DuplicateHeadingToleranceDeg = 45;
    public const double NotThereExpiryWeight = 2.0;
    public const int MaxTtlMultiplier = 3;

    public static readonly IReadOnlyDictionary<ReportType, IReadOnlySet<string>> Subtypes =
        new Dictionary<ReportType, IReadOnlySet<string>>
        {
            [ReportType.Traffic] = new HashSet<string> { "moderate", "heavy", "standstill" },
            [ReportType.Police] = new HashSet<string> { "visible", "hidden", "roadblock" },
            [ReportType.Accident] = new HashSet<string> { "minor", "major", "other_side" },
            [ReportType.Hazard] = new HashSet<string> { "object_on_road", "pothole", "stopped_vehicle", "roadkill", "construction", "flood", "oil_spill" },
            [ReportType.Closure] = new HashSet<string>(),
            [ReportType.BlockedLane] = new HashSet<string> { "left", "center", "right" },
            [ReportType.MapIssue] = new HashSet<string>(),
            [ReportType.Weather] = new HashSet<string> { "heavy_rain", "fog", "flood", "haze" },
            [ReportType.Animal] = new HashSet<string> { "on_road", "on_shoulder" },
        };

    public static TimeSpan BaseTtl(ReportType type) => type switch
    {
        ReportType.Police => TimeSpan.FromMinutes(30),
        ReportType.Accident => TimeSpan.FromMinutes(60),
        ReportType.Hazard => TimeSpan.FromMinutes(60),
        ReportType.Traffic => TimeSpan.FromMinutes(20),
        ReportType.Closure => TimeSpan.FromHours(24),
        ReportType.Weather => TimeSpan.FromMinutes(120),
        // Not specified in the architecture doc: sensible defaults.
        ReportType.BlockedLane => TimeSpan.FromMinutes(60),
        ReportType.Animal => TimeSpan.FromMinutes(30),
        ReportType.MapIssue => TimeSpan.FromDays(7),
        _ => TimeSpan.FromMinutes(60),
    };

    public static DateTimeOffset InitialExpiry(ReportType type, DateTimeOffset createdAt) => createdAt + BaseTtl(type);

    /// <summary>"Still there" extends the TTL by 50% of base, capped at 3× base from creation.</summary>
    public static DateTimeOffset ExtendOnStillThere(ReportType type, DateTimeOffset createdAt, DateTimeOffset expiresAt, DateTimeOffset now)
    {
        var baseTtl = BaseTtl(type);
        var cap = createdAt + baseTtl * MaxTtlMultiplier;
        var from = expiresAt > now ? expiresAt : now;
        var extended = from + baseTtl / 2;
        return extended > cap ? cap : extended;
    }

    /// <summary>Vote weight from reporter reputation (clamped to [0.5, 2]).</summary>
    public static double VoteWeight(double reputation) => Math.Clamp(reputation, 0.5, 2.0);

    /// <summary>"Not there" ×2 from distinct users (reputation-weighted) expires the report.</summary>
    public static bool ShouldExpireOnNotThere(IEnumerable<double> notThereWeights) =>
        notThereWeights.Sum() >= NotThereExpiryWeight - 1e-9;

    /// <summary>Same type within 150 m along the same heading merges into the existing report.</summary>
    public static bool IsDuplicate(ReportType newType, GeoPoint newLoc, double? newHeading,
                                   ReportType existingType, GeoPoint existingLoc, double? existingHeading)
    {
        if (newType != existingType) return false;
        if (GeoMath.HaversineM(newLoc, existingLoc) > DuplicateRadiusM) return false;
        if (newHeading is null || existingHeading is null) return true;
        return GeoMath.HeadingDelta(newHeading.Value, existingHeading.Value) <= DuplicateHeadingToleranceDeg;
    }

    public static string? ValidateSubtype(ReportType type, string? subtype)
    {
        if (string.IsNullOrEmpty(subtype)) return null;
        if (!Subtypes.TryGetValue(type, out var allowed) || allowed.Count == 0)
            return type is ReportType.Closure or ReportType.MapIssue ? null : $"Report type '{GimanoJson.ToWire(type)}' has no subtypes.";
        return allowed.Contains(subtype) ? null
            : $"Invalid subtype '{subtype}' for '{GimanoJson.ToWire(type)}'. Allowed: {string.Join(", ", allowed)}.";
    }

    /// <summary>Points awarded to a reporter.</summary>
    public static class Points
    {
        public const int NewReport = 10;
        public const int Confirmed = 3;
        public const int Thanked = 2;
        public const int Vote = 1;
    }
}
