using Gimano.Core.Contracts;
using NetTopologySuite.Geometries;

namespace Gimano.Core.Domain;

/// <summary>A place added by a Gimano user (always public; private places never reach the server). Id is "c" + 32 hex.</summary>
public sealed class CommunityPlace
{
    public string Id { get; set; } = NewId();
    public required string Name { get; set; }
    public PlaceCategory Category { get; set; } = PlaceCategory.Other;
    public required Point Location { get; set; }
    public string Address { get; set; } = "";
    public string? Description { get; set; }
    public string? Phone { get; set; }
    public string? Website { get; set; }
    public string? OpeningHours { get; set; }
    public List<string> Amenities { get; set; } = [];
    public string Country { get; set; } = "MY";
    /// <summary>Kept for "my contributions", owner edits and owner-visible pending places; null after the owner deleted their account.</summary>
    public Guid? OwnerId { get; set; }
    public User? Owner { get; set; }
    public PlaceStatus Status { get; set; } = PlaceStatus.Pending;
    public int VerifiedCount { get; set; }
    public int RejectedCount { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;

    public static string NewId() => "c" + Guid.CreateVersion7().ToString("N");
    public static bool IsCommunityId(string id) => id.Length == 33 && id[0] == 'c';
}

public sealed class PlaceVerification
{
    public required string PlaceId { get; set; }
    public Guid UserId { get; set; }
    public bool Exists { get; set; }
    public bool? CorrectLocation { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;

    public bool Positive => Exists && CorrectLocation != false;
}

public enum SuggestionStatus { Pending, Applied, Rejected }

/// <summary>A suggested edit to any place (OSM or community). OSM suggestions are map-data feedback for curators.</summary>
public sealed class PlaceSuggestion
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public required string PlaceId { get; set; }
    public Guid? UserId { get; set; }
    public required SuggestEditRequest Changes { get; set; }
    public SuggestionStatus Status { get; set; } = SuggestionStatus.Pending;
    public int Confirmations { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
}

public sealed class Review
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public required string PlaceId { get; set; }
    public Guid? UserId { get; set; }
    public User? User { get; set; }
    public int Rating { get; set; }
    public string? Text { get; set; }
    public ReviewStatus Status { get; set; } = ReviewStatus.Published;
    public int HelpfulCount { get; set; }
    public int FlagCount { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
    public DateTimeOffset? EditedAt { get; set; }
}

public sealed class ReviewHelpful
{
    public Guid ReviewId { get; set; }
    public Guid UserId { get; set; }
}

public sealed class ReviewFlag
{
    public Guid ReviewId { get; set; }
    public Guid UserId { get; set; }
    public FlagReason Reason { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
}

public sealed class Photo
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public required string PlaceId { get; set; }
    public Guid? ReviewId { get; set; }
    public Guid? UserId { get; set; }
    public User? User { get; set; }
    public string? Caption { get; set; }
    public PhotoStatus Status { get; set; } = PhotoStatus.Processing;
    /// <summary>Media keys (content hashes) of the processed WebP and its thumbnail.</summary>
    public string? Key { get; set; }
    public string? ThumbKey { get; set; }
    /// <summary>Temporary key of the original upload; deleted after processing.</summary>
    public string? UploadKey { get; set; }
    public int Width { get; set; }
    public int Height { get; set; }
    public string? RejectReason { get; set; }
    public int FlagCount { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
}

public sealed class PhotoFlag
{
    public Guid PhotoId { get; set; }
    public Guid UserId { get; set; }
    public FlagReason Reason { get; set; }
}

/// <summary>Inland water areas (OSM natural=water, waterway=riverbank, landuse=reservoir closed ways) for the "not in water" check.</summary>
public sealed class WaterArea
{
    public long Id { get; set; }
    public long OsmWayId { get; set; }
    public required Polygon Geom { get; set; }
}
