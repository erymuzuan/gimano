using Gimano.Core.Contracts;
using Gimano.Core.Geo;

namespace Gimano.Core.Routing;

/// <summary>
/// Long multi-stop routes (<c>"store": false</c> allows 25 waypoints = 27 locations) exceed Valhalla's default
/// <c>service_limits.auto.max_locations</c> (20), so they are routed in consecutive chunks that share their boundary
/// stop and joined back into one route.
/// </summary>
public static partial class ValhallaMapper
{
    /// <summary>Valhalla's default max_locations for auto/motorcycle (the lowest of our costings).</summary>
    public const int MaxLocationsPerRequest = 20;

    /// <summary>
    /// Splits a request into consecutive requests of at most <paramref name="maxLocations"/> locations each; chunk n+1
    /// starts at chunk n's last stop. A request that fits is returned unchanged.
    /// </summary>
    public static IReadOnlyList<RouteRequest> SplitStops(RouteRequest req, int maxLocations = MaxLocationsPerRequest)
    {
        var all = new List<LatLng> { req.Origin };
        all.AddRange(req.Waypoints ?? []);
        all.Add(req.Destination);
        if (all.Count <= maxLocations) return [req];

        var chunks = new List<RouteRequest>();
        for (var start = 0; start < all.Count - 1;)
        {
            var end = Math.Min(start + maxLocations - 1, all.Count - 1);
            chunks.Add(req with
            {
                Origin = all[start],
                Waypoints = all.Skip(start + 1).Take(end - start - 1).ToList(),
                Destination = all[end],
                Alternatives = 0,
            });
            start = end;
        }
        return chunks;
    }

    /// <summary>Joins consecutive chunk routes: one polyline (shared vertex dropped), legs in order with shape indexes shifted.</summary>
    public static RouteCandidate Join(IReadOnlyList<RouteCandidate> parts)
    {
        if (parts.Count == 1) return parts[0];
        var points = new List<GeoPoint>();
        var legs = new List<RouteLeg>();
        foreach (var part in parts)
        {
            var offset = points.Count == 0 ? 0 : points.Count - 1;
            points.AddRange(points.Count == 0 ? part.Points : part.Points.Skip(1));
            legs.AddRange(part.Legs.Select(l => l with
            {
                Maneuvers = l.Maneuvers.Select(m => m with
                {
                    BeginShapeIndex = m.BeginShapeIndex + offset,
                    EndShapeIndex = m.EndShapeIndex + offset,
                }).ToList(),
            }));
        }
        return new RouteCandidate(
            Math.Round(parts.Sum(p => p.DurationS), 1), Math.Round(parts.Sum(p => p.DistanceM), 1), parts.Any(p => p.HasTolls),
            Polyline.Encode(points), points, legs, Summarize(legs));
    }
}
