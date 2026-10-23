namespace Gimano.Core.Contracts;

// ---- Auth & profile -------------------------------------------------------

public sealed record DeviceAuthRequest(string DeviceId, Platform Platform, string? Locale);
public sealed record DeviceAuthResponse(string Token, Guid UserId, string DisplayName);

public sealed record UserDto(Guid Id, string DisplayName, string Mood, Vehicle Vehicle, int Points, int Level, DateTimeOffset JoinedAt);
public sealed record PatchMeRequest(string? DisplayName, string? Mood, Vehicle? Vehicle, string? Locale);

// ---- Saved places ---------------------------------------------------------

public sealed record SavedPlaceDto(Guid Id, SavedPlaceKind Kind, string Name, double Lat, double Lng, string Address, string? PlaceId);
public sealed record SavePlaceRequest(string Name, double Lat, double Lng, string? Address, string? PlaceId);
public sealed record RecentDto(Guid Id, string Name, string Address, double Lat, double Lng, string? PlaceId, DateTimeOffset LastUsedAt);

// ---- Search & places ------------------------------------------------------

public sealed record PlaceSummary(string Id, string Name, PlaceCategory Category, string Address, double Lat, double Lng, double? DistanceM, string? Brand);

public sealed record PlaceDto(
    string Id, string Name, PlaceCategory Category, string Address, double Lat, double Lng, double? DistanceM, string? Brand,
    string? Phone, string? Website, string? OpeningHours, bool? OpenNow,
    IReadOnlyList<string> Amenities, IReadOnlyDictionary<string, string> Tags, string OsmType, long OsmId);

public sealed record SearchResponse(IReadOnlyList<PlaceSummary> Results);
public sealed record ReverseGeocodeResponse(string Name, string Address, double Lat, double Lng);

// ---- Routing --------------------------------------------------------------

public sealed record LatLng(double Lat, double Lng, string? PlaceId = null);
public sealed record AvoidOptions(bool Tolls = false, bool Highways = false, bool Ferries = false);

public sealed record RouteRequest(
    LatLng Origin, LatLng Destination, IReadOnlyList<LatLng>? Waypoints, Vehicle? Vehicle,
    AvoidOptions? Avoid, DateTimeOffset? DepartAt, int? Alternatives);

public sealed record RerouteRequest(double Lat, double Lng, double? Heading);

public sealed record RoutesResponse(IReadOnlyList<RouteDto> Routes);

public sealed record RouteDto(
    string Id, RouteLabel Label, double DurationS, double DistanceM, double TrafficDurationS,
    string Summary, string TrafficDescription, bool HasTolls, decimal? TollEstimateMyr,
    string Polyline, double[] Bbox, IReadOnlyList<RouteLeg> Legs,
    IReadOnlyList<TrafficSegment> TrafficSegments, IReadOnlyList<AlertOnRoute> Alerts);

public sealed record RouteLeg(IReadOnlyList<ManeuverDto> Maneuvers);

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
