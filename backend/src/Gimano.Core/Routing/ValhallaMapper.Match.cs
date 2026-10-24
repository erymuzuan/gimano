using System.Text.Json.Nodes;
using Gimano.Core.Contracts;
using Gimano.Core.Geo;

namespace Gimano.Core.Routing;

/// <summary>
/// POST /routes/match (docs/events.md §6): snap a drawn line to roads with Valhalla <c>/trace_attributes</c>
/// (<c>shape_match: map_snap</c>). Pure mapping; the API is stateless (no cache, no DB, no coordinates in logs).
/// </summary>
public static partial class ValhallaMapper
{
    public const int MinMatchPoints = 2;
    public const int MaxMatchPoints = 5000;

    /// <summary>
    /// Search radius (m) around each drawn point. A line drawn with a mouse/finger is less precise than GPS, so this
    /// is wider than Valhalla's default; it stays under Valhalla's default <c>max_search_radius</c> (100 m).
    /// </summary>
    internal const int MatchSearchRadiusM = 60;

    /// <summary>Drawn lines are sparse (one point per click); Valhalla's default 2 km breakage would split them.</summary>
    internal const int MatchBreakageDistanceM = 5000;

    private static readonly string[] TraceAttributes =
    [
        "shape", "edge.length", "edge.speed", "node.elapsed_time",
        "matched.point", "matched.type", "matched.edge_index", "matched.distance_along_edge",
    ];

    public static JsonObject BuildTraceRequest(IReadOnlyList<MatchPoint> points, Vehicle vehicle)
    {
        var shape = new JsonArray();
        foreach (var p in points) shape.Add(new JsonObject { ["lat"] = p.Lat, ["lon"] = p.Lng });
        var costing = Costing(vehicle);
        return new JsonObject
        {
            ["shape"] = shape,
            ["costing"] = costing,
            ["costing_options"] = new JsonObject { [costing] = CostingOptions(vehicle, null) },
            ["shape_match"] = "map_snap",
            ["units"] = "kilometers",
            ["trace_options"] = new JsonObject
            {
                ["search_radius"] = MatchSearchRadiusM,
                ["gps_accuracy"] = 20,
                ["breakage_distance"] = MatchBreakageDistanceM,
            },
            ["filters"] = new JsonObject
            {
                ["attributes"] = new JsonArray(TraceAttributes.Select(a => (JsonNode)JsonValue.Create(a)).ToArray()),
                ["action"] = "include",
            },
        };
    }

    /// <summary>
    /// Maps a Valhalla <c>/trace_attributes</c> response. Returns null when nothing matched a road (no shape, no edges,
    /// or every input point is <c>unmatched</c>).
    /// </summary>
    /// <param name="inputCount">Number of points sent; <c>matched_points</c> is index-aligned with them.</param>
    public static RouteMatchResponse? MapTrace(JsonNode response, int inputCount)
    {
        var shape = Polyline.Decode(response["shape"]?.GetValue<string>());
        var edges = response["edges"] as JsonArray ?? [];
        if (shape.Count < 2 || edges.Count == 0) return null;

        var types = (response["matched_points"] as JsonArray ?? [])
            .Select(m => m?["type"]?.GetValue<string>())
            .ToList();
        // Valhalla returns one matched point per input point; be defensive if it does not.
        while (types.Count < inputCount) types.Add(null);
        if (types.Count > inputCount) types = types.Take(inputCount).ToList();
        if (types.Count > 0 && types.All(t => t is null or "unmatched")) return null;

        var lengthFactor = (response["units"]?.GetValue<string>() ?? "kilometers") == "miles" ? 1609.344 : 1000.0;
        var edgeLengthsM = edges.Select(e => (e?["length"]?.GetValue<double>() ?? 0) * lengthFactor).ToList();
        var distanceM = edgeLengthsM.Sum() is > 0 and var sum ? sum : GeoMath.LengthM(shape);

        // Cumulative elapsed time at the end of the last edge (includes turn costs); else length / speed per edge.
        var durationS = edges[^1]?["end_node"]?["elapsed_time"]?.GetValue<double>()
            ?? edges.Select((e, i) => e?["speed"]?.GetValue<double>() is > 0 and var kmh ? edgeLengthsM[i] / (kmh / 3.6) : 0).Sum();

        return new RouteMatchResponse(Polyline.Encode(shape), Math.Round(distanceM, 1), Math.Round(durationS, 1), UnmatchedRanges(types));
    }

    /// <summary>Contiguous runs of <c>unmatched</c> input points as inclusive index ranges. Null types count as unmatched.</summary>
    public static IReadOnlyList<UnmatchedRange> UnmatchedRanges(IReadOnlyList<string?> matchedTypes)
    {
        var ranges = new List<UnmatchedRange>();
        int? start = null;
        for (var i = 0; i < matchedTypes.Count; i++)
        {
            var unmatched = matchedTypes[i] is null or "unmatched";
            if (unmatched && start is null) start = i;
            else if (!unmatched && start is { } s) { ranges.Add(new UnmatchedRange(s, i - 1)); start = null; }
        }
        if (start is { } open) ranges.Add(new UnmatchedRange(open, matchedTypes.Count - 1));
        return ranges;
    }
}
