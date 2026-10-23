using System.Globalization;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Gimano.Core.Contracts;
using Gimano.Core.Geo;

namespace Gimano.Core.Routing;

/// <summary>A route as returned by Valhalla, already mapped to our shapes but not yet decorated.</summary>
public sealed record RouteCandidate(
    double DurationS, double DistanceM, bool HasTolls, string Polyline, IReadOnlyList<GeoPoint> Points,
    IReadOnlyList<RouteLeg> Legs, string Summary);

public static partial class ValhallaMapper
{
    public static readonly TimeZoneInfo MalaysiaTime = TimeZoneInfo.CreateCustomTimeZone("MYT", TimeSpan.FromHours(8), "MYT", "MYT");

    // ---- Request ----------------------------------------------------------

    public static JsonObject BuildRouteRequest(RouteRequest req, double? originHeading = null, string language = "en-US")
    {
        var locations = new JsonArray { Location(req.Origin, originHeading) };
        foreach (var w in req.Waypoints ?? []) locations.Add(Location(w, null, "through"));
        locations.Add(Location(req.Destination, null));

        var vehicle = req.Vehicle ?? Vehicle.Car;
        var costing = vehicle == Vehicle.Motorcycle ? "motorcycle" : "auto";
        var avoid = req.Avoid ?? new AvoidOptions();
        var opts = new JsonObject
        {
            ["use_tolls"] = avoid.Tolls ? 0.0 : 0.5,
            ["use_highways"] = avoid.Highways ? 0.0 : (vehicle == Vehicle.Motorcycle ? 0.8 : 1.0),
            ["use_ferry"] = avoid.Ferries ? 0.0 : 0.5,
        };

        var body = new JsonObject
        {
            ["locations"] = locations,
            ["costing"] = costing,
            ["costing_options"] = new JsonObject { [costing] = opts },
            ["units"] = "kilometers",
            ["language"] = language,
            ["directions_options"] = new JsonObject { ["units"] = "kilometers", ["language"] = language },
        };

        var alternates = Math.Clamp(req.Alternatives ?? 2, 0, 3);
        if (alternates > 0 && (req.Waypoints?.Count ?? 0) == 0) body["alternates"] = alternates;

        if (req.DepartAt is { } departAt)
            body["date_time"] = new JsonObject { ["type"] = 1, ["value"] = ToValhallaLocal(departAt) };
        return body;
    }

    public static string ToValhallaLocal(DateTimeOffset t) =>
        TimeZoneInfo.ConvertTime(t, MalaysiaTime).ToString("yyyy-MM-dd'T'HH:mm", CultureInfo.InvariantCulture);

    private static JsonObject Location(LatLng p, double? heading, string type = "break")
    {
        var o = new JsonObject { ["lat"] = p.Lat, ["lon"] = p.Lng, ["type"] = type };
        if (heading is { } h)
        {
            o["heading"] = (int)Math.Round(((h % 360) + 360) % 360);
            o["heading_tolerance"] = 60;
        }
        return o;
    }

    // ---- Response ---------------------------------------------------------

    /// <summary>Maps the primary trip and all alternates of a Valhalla /route response.</summary>
    public static IReadOnlyList<RouteCandidate> MapResponse(JsonNode response)
    {
        var list = new List<RouteCandidate>();
        if (response["trip"] is { } trip) list.Add(MapTrip(trip));
        if (response["alternates"] is JsonArray alts)
            foreach (var a in alts)
                if (a?["trip"] is { } t) list.Add(MapTrip(t));
        return list;
    }

    public static RouteCandidate MapTrip(JsonNode trip)
    {
        var lengthFactor = (trip["units"]?.GetValue<string>() ?? "kilometers") switch
        {
            "miles" => 1609.344,
            _ => 1000.0,
        };

        var allPoints = new List<GeoPoint>();
        var legs = new List<RouteLeg>();
        var legsNode = trip["legs"]?.AsArray() ?? [];
        var hasTolls = trip["summary"]?["has_toll"]?.GetValue<bool>() ?? false;

        foreach (var leg in legsNode)
        {
            var shape = Polyline.Decode(leg!["shape"]?.GetValue<string>());
            // Consecutive legs share their joining vertex; drop the duplicate.
            var offset = allPoints.Count == 0 ? 0 : allPoints.Count - 1;
            allPoints.AddRange(allPoints.Count == 0 ? shape : shape.Skip(1));

            var maneuvers = new List<ManeuverDto>();
            foreach (var m in leg["maneuvers"]?.AsArray() ?? [])
            {
                if (m is null) continue;
                hasTolls |= m["toll"]?.GetValue<bool>() ?? false;
                maneuvers.Add(MapManeuver(m, offset, lengthFactor));
            }
            legs.Add(new RouteLeg(maneuvers));
        }

        var s = trip["summary"];
        var duration = s?["time"]?.GetValue<double>() ?? legs.SelectMany(l => l.Maneuvers).Sum(m => m.DurationS);
        var distance = (s?["length"]?.GetValue<double>() * lengthFactor) ?? legs.SelectMany(l => l.Maneuvers).Sum(m => m.DistanceM);

        return new RouteCandidate(
            Math.Round(duration, 1), Math.Round(distance, 1), hasTolls,
            Polyline.Encode(allPoints), allPoints, legs, Summarize(legs));
    }

    public static ManeuverDto MapManeuver(JsonNode m, int shapeOffset = 0, double lengthFactor = 1000)
    {
        var type = MapType(m["type"]?.GetValue<int>() ?? 0);
        var names = (m["street_names"] ?? m["begin_street_names"])?.AsArray()
            .Select(n => n?.GetValue<string>()).Where(n => !string.IsNullOrWhiteSpace(n)).Cast<string>().ToList() ?? [];

        var roadRef = names.FirstOrDefault(IsRoadRef);
        var street = names.FirstOrDefault(n => !IsRoadRef(n)) ?? roadRef;

        var sign = m["sign"];
        var exitNumber = FirstSignText(sign?["exit_number_elements"]);
        roadRef ??= FirstSignText(sign?["exit_branch_elements"]) is { } br && IsRoadRef(br) ? br : null;

        return new ManeuverDto(
            type,
            m["instruction"]?.GetValue<string>() ?? "",
            street,
            exitNumber,
            roadRef,
            Math.Round((m["length"]?.GetValue<double>() ?? 0) * lengthFactor, 1),
            Math.Round(m["time"]?.GetValue<double>() ?? 0, 1),
            (m["begin_shape_index"]?.GetValue<int>() ?? 0) + shapeOffset,
            (m["end_shape_index"]?.GetValue<int>() ?? 0) + shapeOffset,
            m["verbal_pre_transition_instruction"]?.GetValue<string>(),
            m["verbal_post_transition_instruction"]?.GetValue<string>());
    }

    private static string? FirstSignText(JsonNode? elements) =>
        elements is JsonArray a ? a.Select(e => e?["text"]?.GetValue<string>()).FirstOrDefault(t => !string.IsNullOrWhiteSpace(t)) : null;

    /// <summary>Valhalla maneuver type (valhalla/proto/directions.proto) → Gimano maneuver type.</summary>
    public static ManeuverType MapType(int valhallaType) => valhallaType switch
    {
        1 or 2 or 3 => ManeuverType.Depart,
        4 or 5 or 6 => ManeuverType.Arrive,
        7 or 8 or 17 or 22 or 29 => ManeuverType.Straight,
        9 => ManeuverType.SlightRight,
        10 => ManeuverType.Right,
        11 => ManeuverType.SharpRight,
        12 => ManeuverType.UturnRight,
        13 => ManeuverType.UturnLeft,
        14 => ManeuverType.SharpLeft,
        15 => ManeuverType.Left,
        16 => ManeuverType.SlightLeft,
        18 => ManeuverType.RampRight,
        19 => ManeuverType.RampLeft,
        20 => ManeuverType.ExitRight,
        21 => ManeuverType.ExitLeft,
        23 => ManeuverType.KeepRight,
        24 => ManeuverType.KeepLeft,
        25 or 37 or 38 => ManeuverType.Merge,
        26 => ManeuverType.RoundaboutEnter,
        27 => ManeuverType.RoundaboutExit,
        28 => ManeuverType.Ferry,
        _ => ManeuverType.Straight,
    };

    /// <summary>Malaysian / Singaporean road refs: E11, FT2, FT 2, J5, B15, AH2, PIE is not a ref.</summary>
    public static bool IsRoadRef(string s) => RoadRefRegex().IsMatch(s.Trim());

    [GeneratedRegex(@"^(AH|FT|E|[A-Z])\s?\d{1,4}[A-Z]?$")]
    private static partial Regex RoadRefRegex();

    /// <summary>"Via E11 LDP, Jalan SS 2/24" — the two roads carrying the most distance.</summary>
    public static string Summarize(IReadOnlyList<RouteLeg> legs)
    {
        var roads = legs.SelectMany(l => l.Maneuvers)
            .Where(m => m.StreetName is not null || m.RoadRef is not null)
            .GroupBy(m => Label(m))
            .Select(g => (Name: g.Key, Length: g.Sum(m => m.DistanceM), First: g.Min(m => m.BeginShapeIndex)))
            .OrderByDescending(r => r.Length)
            .Take(2)
            .OrderBy(r => r.First)
            .Select(r => r.Name)
            .ToList();
        return roads.Count == 0 ? "" : "Via " + string.Join(", ", roads);

        static string Label(ManeuverDto m) =>
            m.RoadRef is not null && m.StreetName is not null && m.StreetName != m.RoadRef ? $"{m.RoadRef} {m.StreetName}" : (m.StreetName ?? m.RoadRef)!;
    }

    /// <summary>Valhalla error JSON: <c>{"error_code":442,"error":"No path could be found for input","status_code":400}</c>.</summary>
    public static (int Code, string Message)? ParseError(string body)
    {
        try
        {
            var n = JsonNode.Parse(body);
            if (n?["error_code"] is null && n?["error"] is null) return null;
            return (n["error_code"]?.GetValue<int>() ?? 0, n["error"]?.GetValue<string>() ?? "Routing error");
        }
        catch (System.Text.Json.JsonException)
        {
            return null;
        }
    }
}
