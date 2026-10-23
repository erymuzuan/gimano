using Gimano.Core.Geo;

namespace Gimano.Core.Traffic;

public sealed record SnapCandidate(long SegmentId, bool Oneway, IReadOnlyList<GeoPoint> Geometry);

public static class SegmentSnapper
{
    public const double MaxSnapDistanceM = 25;
    public const double HeadingToleranceDeg = 50;

    /// <summary>
    /// Chooses the nearest segment within 25 m whose local direction is compatible with the heading.
    /// Two-way roads accept both directions; one-way roads only the digitised direction.
    /// </summary>
    public static long? Snap(IEnumerable<SnapCandidate> candidates, GeoPoint p, double? heading, double? speedKmh = null)
    {
        // Heading is noisy below walking speed; ignore it then.
        var useHeading = heading is not null && (speedKmh ?? 10) >= 5;
        long? best = null;
        var bestDist = double.MaxValue;
        foreach (var c in candidates)
        {
            var proj = GeoMath.ProjectOnLine(c.Geometry, p);
            if (proj.DistanceM > MaxSnapDistanceM || proj.DistanceM >= bestDist) continue;
            if (useHeading)
            {
                var forward = GeoMath.HeadingDelta(heading!.Value, proj.SegmentBearing) <= HeadingToleranceDeg;
                var backward = GeoMath.HeadingDelta(heading.Value, (proj.SegmentBearing + 180) % 360) <= HeadingToleranceDeg;
                if (!(forward || (!c.Oneway && backward))) continue;
            }
            best = c.SegmentId;
            bestDist = proj.DistanceM;
        }
        return best;
    }
}
