namespace Gimano.Core.Contracts;

// ---- Auth & profile -------------------------------------------------------

public sealed record DeviceAuthRequest(string DeviceId, Platform Platform, string? Locale);
public sealed record DeviceAuthResponse(string Token, Guid UserId, string DisplayName);

public sealed record UserDto(Guid Id, string DisplayName, string Mood, Vehicle Vehicle, int Points, int Level, DateTimeOffset JoinedAt);
public sealed record PatchMeRequest(string? DisplayName, string? Mood, Vehicle? Vehicle, string? Locale);

// ---- Saved places ---------------------------------------------------------

public sealed record SavedPlaceDto(Guid Id, SavedPlaceKind Kind, string Name, FavoriteIcon Icon, double Lat, double Lng, string Address, string? PlaceId, int SortOrder);
public sealed record SavePlaceRequest(string Name, double Lat, double Lng, string? Address, string? PlaceId, FavoriteIcon? Icon = null);
public sealed record PatchPlaceRequest(string? Name, FavoriteIcon? Icon, double? Lat, double? Lng, string? Address, int? SortOrder);
public sealed record RecentDto(Guid Id, string Name, string Address, double Lat, double Lng, string? PlaceId, DateTimeOffset LastUsedAt);

// ---- Search & places ------------------------------------------------------

public sealed record PlaceSummary(
    string Id, string Name, PlaceCategory Category, string Address, double Lat, double Lng, double? DistanceM, string? Brand,
    PlaceSource Source = PlaceSource.Osm, PlaceStatus? Status = null, double? Rating = null, int ReviewCount = 0, string? ThumbnailUrl = null);

public sealed record PlaceDto(
    string Id, string Name, PlaceCategory Category, string Address, double Lat, double Lng, double? DistanceM, string? Brand,
    string? Phone, string? Website, string? OpeningHours, bool? OpenNow,
    IReadOnlyList<string> Amenities, IReadOnlyDictionary<string, string> Tags, string? OsmType, long? OsmId,
    PlaceSource Source = PlaceSource.Osm, PlaceStatus? Status = null, double? Rating = null, int ReviewCount = 0, string? ThumbnailUrl = null,
    string? Description = null, AddedBy? AddedBy = null, int VerifiedCount = 0, IReadOnlyList<PhotoDto>? Photos = null);

public sealed record AddedBy(string DisplayName);

// ---- Community places --------------------------------------------------------

public sealed record CreatePlaceRequest(
    string Name, PlaceCategory? Category, double Lat, double Lng, string? Address, string? Description,
    string? Phone, string? Website, string? OpeningHours, IReadOnlyList<string>? Amenities);

public sealed record PatchCommunityPlaceRequest(
    string? Name, PlaceCategory? Category, double? Lat, double? Lng, string? Address, string? Description,
    string? Phone, string? Website, string? OpeningHours, IReadOnlyList<string>? Amenities);

public sealed record SuggestEditRequest(
    string? Name, PlaceCategory? Category, double? Lat, double? Lng, string? Address, string? Phone, string? Website,
    string? OpeningHours, string? Description, bool? Closed, string? Comment);

public sealed record SuggestionAccepted(Guid SuggestionId);

public sealed record VerifyPlaceRequest(bool Exists, bool? CorrectLocation);

// ---- Reviews & photos ----------------------------------------------------------

public sealed record ReviewAuthor(string DisplayName, int Level, int ReviewCount);

public sealed record ReviewDto(
    Guid Id, string PlaceId, int Rating, string? Text, IReadOnlyList<PhotoDto> Photos, ReviewAuthor Author,
    int HelpfulCount, DateTimeOffset CreatedAt, DateTimeOffset? EditedAt, ReviewStatus Status);

public sealed record ReviewsPage(IReadOnlyList<ReviewDto> Reviews, string? Next);

public sealed record CreateReviewRequest(int Rating, string? Text, IReadOnlyList<Guid>? PhotoIds);

public sealed record PatchReviewRequest(int? Rating, string? Text, IReadOnlyList<Guid>? PhotoIds);

public sealed record FlagRequest(FlagReason Reason);

public sealed record PhotoDto(
    Guid Id, string? Url, string? ThumbUrl, int Width, int Height, string? Caption, AddedBy UploadedBy, DateTimeOffset CreatedAt,
    PhotoStatus Status = PhotoStatus.Published);

public sealed record PhotosPage(IReadOnlyList<PhotoDto> Photos, string? Next);

public sealed record SearchResponse(IReadOnlyList<PlaceSummary> Results);
public sealed record ReverseGeocodeResponse(string Name, string Address, double Lat, double Lng);

// ---- Routing --------------------------------------------------------------

/// <summary>A route point. Waypoints may carry a display <c>Name</c> (used for the leg's <c>to.name</c>).</summary>
public sealed record LatLng(double Lat, double Lng, string? PlaceId = null, string? Name = null);
public sealed record AvoidOptions(bool Tolls = false, bool Highways = false, bool Ferries = false);

public sealed record RouteRequest(
    LatLng Origin, LatLng Destination, IReadOnlyList<LatLng>? Waypoints, Vehicle? Vehicle,
    AvoidOptions? Avoid, DateTimeOffset? DepartAt, int? Alternatives);

/// <summary><c>NextStopIndex</c>: index into the original waypoints of the next stop not yet reached (default 0).</summary>
public sealed record RerouteRequest(double Lat, double Lng, double? Heading, int? NextStopIndex = null);

public sealed record RoutesResponse(IReadOnlyList<RouteDto> Routes);

public sealed record RouteDto(
    string Id, RouteLabel Label, double DurationS, double DistanceM, double TrafficDurationS,
    string Summary, string TrafficDescription, Vehicle Vehicle,
    bool HasTolls, decimal? TollEstimateMyr, bool TollFareComplete, IReadOnlyList<TollOnRoute> Tolls,
    string Polyline, double[] Bbox, IReadOnlyList<RouteLeg> Legs,
    IReadOnlyList<TrafficSegment> TrafficSegments, IReadOnlyList<AlertOnRoute> Alerts);

public sealed record RouteLeg(double DurationS, double DistanceM, LegEnd To, IReadOnlyList<ManeuverDto> Maneuvers);

public sealed record LegEnd(double Lat, double Lng, string? Name);

public sealed record TollOnRoute(string PlazaId, string Name, string? Highway, double Lat, double Lng, decimal? FareMyr, TollSystem System, string? Source);

public sealed record ManeuverDto(
    ManeuverType Type, string Instruction, string? StreetName, string? ExitNumber, string? RoadRef,
    double DistanceM, double DurationS, int BeginShapeIndex, int EndShapeIndex,
    string? VerbalPre, string? VerbalPost);

public sealed record TrafficSegment(int FromIndex, int ToIndex, int Level);

public sealed record DepartureSlot(DateTimeOffset DepartAt, double DurationS);
public sealed record DepartureTimesResponse(IReadOnlyList<DepartureSlot> Slots);

// ---- Reports / alerts -----------------------------------------------------

public sealed record ReportedBy(string DisplayName, string Mood);

public record ReportDto(
    Guid Id, ReportType Type, string? Subtype, double Lat, double Lng, double? Heading, string? Street,
    ReportedBy ReportedBy, DateTimeOffset CreatedAt, DateTimeOffset ExpiresAt, int Confirmations, int Thanks);

/// <summary><c>AlertOnRoute = Report + {distanceAlongRouteM}</c></summary>
public sealed record AlertOnRoute(
    Guid Id, ReportType Type, string? Subtype, double Lat, double Lng, double? Heading, string? Street,
    ReportedBy ReportedBy, DateTimeOffset CreatedAt, DateTimeOffset ExpiresAt, int Confirmations, int Thanks,
    double DistanceAlongRouteM)
{
    public static AlertOnRoute From(ReportDto r, double along) => new(
        r.Id, r.Type, r.Subtype, r.Lat, r.Lng, r.Heading, r.Street, r.ReportedBy, r.CreatedAt, r.ExpiresAt,
        r.Confirmations, r.Thanks, Math.Round(along, 1));
}

public sealed record CreateReportRequest(
    ReportType Type, string? Subtype, double Lat, double Lng, double? Heading, string? Comment,
    double? EndLat = null, double? EndLng = null);

public sealed record VoteRequest(bool StillThere);

// ---- Live -----------------------------------------------------------------

public sealed record JamDto(Guid Id, int Level, double SpeedKmh, double DelayS, double LengthM, string Polyline, string? Street, DateTimeOffset UpdatedAt);
public sealed record DriverDto(double Lat, double Lng, double Heading, string Mood);
public sealed record LiveResponse(IReadOnlyList<ReportDto> Alerts, IReadOnlyList<JamDto> Jams, IReadOnlyList<DriverDto> Drivers, DateTimeOffset ServerTime);

// ---- Location pings -------------------------------------------------------

public sealed record LocationPoint(double Lat, double Lng, double? SpeedKmh, double? Heading, double? AccuracyM, DateTimeOffset? Ts);
public sealed record LocationBatch(string? RouteId, IReadOnlyList<LocationPoint> Points);

// ---- Feedback ---------------------------------------------------------------

public sealed record FeedbackContext(
    string? AppVersion, string? Platform, string? OsVersion, string? DeviceModel, string? Locale,
    double? Lat, double? Lng, string? RouteId, string? PlaceId);

public sealed record CreateFeedbackRequest(
    FeedbackCategory Category, string Message, int? Rating, FeedbackScreen? Screen, FeedbackContext? Context, string? Screenshot);

public sealed record FeedbackCreatedResponse(Guid Id, DateTimeOffset CreatedAt);

public sealed record FeedbackSummary(Guid Id, FeedbackCategory Category, string Message, FeedbackStatus Status, DateTimeOffset CreatedAt);

// ---- Inbox (public contributions only) ----------------------------------------------------

public sealed record InboxItemDto(
    Guid Id, InboxKind Kind, string Title, string Body, string? RefType, string? RefId, DateTimeOffset CreatedAt,
    bool Read, int Count = 1);

public sealed record InboxPage(IReadOnlyList<InboxItemDto> Items, string? Next, int Unread);

public sealed record InboxReadRequest(DateTimeOffset UpTo);
