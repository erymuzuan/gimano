using Gimano.Core.Contracts;
using NetTopologySuite.Geometries;

namespace Gimano.Core.Domain;

public sealed class User
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public required string DeviceId { get; set; }
    public Platform Platform { get; set; }
    public required string DisplayName { get; set; }
    public string Mood { get; set; } = "happy";
    public Vehicle Vehicle { get; set; } = Vehicle.Car;
    public string Locale { get; set; } = "ms-MY";
    public int Points { get; set; }
    /// <summary>Vote weight in [0.5, 2.0]; grows when the user's reports get confirmed.</summary>
    public double Reputation { get; set; } = 1.0;
    public DateTimeOffset JoinedAt { get; set; } = DateTimeOffset.UtcNow;
    public DateTimeOffset LastSeenAt { get; set; } = DateTimeOffset.UtcNow;

    public int Level => Points switch { < 100 => 1, < 500 => 2, < 2_000 => 3, < 10_000 => 4, < 50_000 => 5, _ => 6 };

    public UserDto ToDto() => new(Id, DisplayName, Mood, Vehicle, Points, Level, JoinedAt);
}

public sealed class SavedPlace
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public Guid UserId { get; set; }
    public SavedPlaceKind Kind { get; set; }
    public required string Name { get; set; }
    public required Point Location { get; set; }
    public string Address { get; set; } = "";
    public string? PlaceId { get; set; }
    public FavoriteIcon Icon { get; set; } = FavoriteIcon.Star;
    public int SortOrder { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;

    /// <summary>Home and work always carry their own icon.</summary>
    public FavoriteIcon EffectiveIcon => Kind switch
    {
        SavedPlaceKind.Home => FavoriteIcon.Home,
        SavedPlaceKind.Work => FavoriteIcon.Work,
        _ => Icon,
    };

    public SavedPlaceDto ToDto() => new(Id, Kind, Name, EffectiveIcon, Location.Y, Location.X, Address, PlaceId, SortOrder);
}

public sealed class RecentDestination
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public Guid UserId { get; set; }
    public required string Name { get; set; }
    public string Address { get; set; } = "";
    public required Point Location { get; set; }
    public string? PlaceId { get; set; }
    public DateTimeOffset LastUsedAt { get; set; } = DateTimeOffset.UtcNow;

    public RecentDto ToDto() => new(Id, Name, Address, Location.Y, Location.X, PlaceId, LastUsedAt);
}

/// <summary>A point of interest imported from OSM. Id is "{n|w|r}{osmId}", e.g. "n123456".</summary>
public sealed class Place
{
    public required string Id { get; set; }
    public required string Name { get; set; }
    public PlaceCategory Category { get; set; }
    public string? Brand { get; set; }
    public string Address { get; set; } = "";
    public required Point Location { get; set; }
    public string Country { get; set; } = "MY";
    public string? Phone { get; set; }
    public string? Website { get; set; }
    public string? OpeningHours { get; set; }
    public List<string> Amenities { get; set; } = [];
    public Dictionary<string, string> Tags { get; set; } = [];
    public required string OsmType { get; set; }
    public long OsmId { get; set; }
    public double Popularity { get; set; }
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;
}

public enum ReportStatus { Active, Expired }

public sealed class Report
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public ReportType Type { get; set; }
    public string? Subtype { get; set; }
    public required Point Location { get; set; }
    public Point? EndLocation { get; set; }
    public double? Heading { get; set; }
    public string? Street { get; set; }
    public string? Comment { get; set; }
    /// <summary>Null once the reporter deleted their account (the report stays, anonymised).</summary>
    public Guid? ReporterId { get; set; }
    public User? Reporter { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
    public DateTimeOffset ExpiresAt { get; set; }
    public int Confirmations { get; set; }
    public int Thanks { get; set; }
    public ReportStatus Status { get; set; } = ReportStatus.Active;
    public uint Version { get; set; }

    public ReportDto ToDto() => new(
        Id, Type, Subtype, Location.Y, Location.X, Heading, Street,
        new ReportedBy(Reporter?.DisplayName ?? "Gimano driver", Reporter?.Mood ?? "happy"),
        CreatedAt, ExpiresAt, Confirmations, Thanks);
}

public sealed class ReportVote
{
    public Guid ReportId { get; set; }
    public Guid UserId { get; set; }
    public bool StillThere { get; set; }
    public double Weight { get; set; } = 1;
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
}

/// <summary>A road chunk (≤ ~300 m) of an OSM way, used for snapping pings and the traffic model.</summary>
public sealed class RoadSegment
{
    public long Id { get; set; }
    public long OsmWayId { get; set; }
    public int Seq { get; set; }
    public string? Name { get; set; }
    public string? Ref { get; set; }
    public required string Highway { get; set; }
    public bool Oneway { get; set; }
    public double FreeFlowKmh { get; set; }
    public double LengthM { get; set; }
    public long FromNode { get; set; }
    public long ToNode { get; set; }
    public required LineString Geom { get; set; }
}

/// <summary>Rolling 5-minute speed per road segment, maintained by the Worker.</summary>
public sealed class SegmentSpeed
{
    public long SegmentId { get; set; }
    public double MeanSpeedKmh { get; set; }
    public double FreeFlowKmh { get; set; }
    public int Samples { get; set; }
    public int DistinctUsers { get; set; }
    public int Level { get; set; }
    public DateTimeOffset UpdatedAt { get; set; }
}

public sealed class Jam
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public int Level { get; set; }
    public double SpeedKmh { get; set; }
    public double DelayS { get; set; }
    public double LengthM { get; set; }
    public required LineString Geom { get; set; }
    public string? Street { get; set; }
    /// <summary>Stable identity of the merged jam: the lowest segment id in the run.</summary>
    public long AnchorSegmentId { get; set; }
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;

    public JamDto ToDto() => new(Id, Level, Math.Round(SpeedKmh, 1), Math.Round(DelayS), Math.Round(LengthM),
        Geo.Polyline.Encode(Geo.GeoMath.FromLineString(Geom)), Street, UpdatedAt);
}

public sealed class Feedback
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    /// <summary>Only for rate limiting and "My feedback"; null once the account is deleted.</summary>
    public Guid? UserId { get; set; }
    public FeedbackCategory Category { get; set; }
    public required string Message { get; set; }
    public int? Rating { get; set; }
    public FeedbackScreen? Screen { get; set; }
    /// <summary>Diagnostics as sent by the app (stored as jsonb).</summary>
    public FeedbackContext? Context { get; set; }
    public byte[]? Screenshot { get; set; }
    public string? ScreenshotContentType { get; set; }
    public FeedbackStatus Status { get; set; } = FeedbackStatus.Received;
    public string? ExternalUrl { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;

    public FeedbackSummary ToSummary() => new(Id, Category, Message, Status, CreatedAt);
}

/// <summary>A row of data/tolls/my-toll-rates.csv, matched to OSM toll plaza place ids by the importer.</summary>
public sealed class TollRate
{
    public int Id { get; set; }
    public string? PlazaOsmId { get; set; }
    public required string Name { get; set; }
    public string? Highway { get; set; }
    public double? Lat { get; set; }
    public double? Lng { get; set; }
    public TollSystem System { get; set; }
    public string? EntryPlaza { get; set; }
    public decimal? Class0Myr { get; set; }
    public decimal? Class1Myr { get; set; }
    public DateOnly? EffectiveDate { get; set; }
    public string? SourceUrl { get; set; }
    public string? Notes { get; set; }
    public List<string> MatchedPlaceIds { get; set; } = [];
}
