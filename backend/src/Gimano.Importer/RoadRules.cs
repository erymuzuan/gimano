using System.Globalization;
using System.Text.RegularExpressions;

namespace Gimano.Importer;

/// <summary>Which OSM highways become traffic-model road segments, and their free-flow speeds.</summary>
public static partial class RoadRules
{
    public const double ChunkLengthM = 300;

    private static readonly Dictionary<string, double> DefaultSpeedKmh = new()
    {
        ["motorway"] = 110, ["motorway_link"] = 60,
        ["trunk"] = 90, ["trunk_link"] = 50,
        ["primary"] = 70, ["primary_link"] = 45,
        ["secondary"] = 60, ["secondary_link"] = 40,
        ["tertiary"] = 50, ["tertiary_link"] = 35,
        ["unclassified"] = 40,
        ["residential"] = 30,
        ["living_street"] = 15,
    };

    public static bool IsRoad(string? highway) => highway is not null && DefaultSpeedKmh.ContainsKey(highway);

    public static double FreeFlowKmh(string highway, string? maxspeed)
    {
        var def = DefaultSpeedKmh.GetValueOrDefault(highway, 40);
        var parsed = ParseMaxSpeed(maxspeed);
        // Real free-flow traffic rarely exceeds 90% of the limit on urban roads.
        return parsed is { } v ? Math.Round(Math.Min(v, def * 1.2) * (highway.StartsWith("motorway") ? 1.0 : 0.9), 1) : def;
    }

    public static double? ParseMaxSpeed(string? s)
    {
        if (string.IsNullOrWhiteSpace(s)) return null;
        var m = MaxSpeedRegex().Match(s.Trim());
        if (!m.Success || !double.TryParse(m.Groups[1].Value, NumberStyles.Float, CultureInfo.InvariantCulture, out var v) || v <= 0) return null;
        return m.Groups[2].Value.Equals("mph", StringComparison.OrdinalIgnoreCase) ? Math.Round(v * 1.609344) : v;
    }

    /// <returns>(oneway, reversed): reversed = digitised against the direction of travel (oneway=-1).</returns>
    public static (bool Oneway, bool Reversed) Oneway(string highway, string? oneway, string? junction)
    {
        if (oneway is "-1" or "reverse") return (true, true);
        if (oneway is "yes" or "1" or "true") return (true, false);
        if (oneway is "no" or "false" or "0") return (false, false);
        return (highway is "motorway" or "motorway_link" || junction is "roundabout" or "circular", false);
    }

    [GeneratedRegex(@"^(\d+(?:\.\d+)?)\s*(km/h|kmh|mph)?$", RegexOptions.IgnoreCase)]
    private static partial Regex MaxSpeedRegex();
}
