using Gimano.Core.Contracts;
using Gimano.Core.Geo;

namespace Gimano.Core.Traffic;

public sealed record JamLine(int Level, double SpeedKmh, IReadOnlyList<GeoPoint> Geometry);

/// <summary>Projects live jams onto a route shape and re-scores the route.</summary>
public static class TrafficOverlay
{
    public const double MatchToleranceM = 20;

    /// <summary>Per route vertex: the jam level of the closest jam within 20 m (0 if none).</summary>
    public static int[] LevelsPerVertex(IReadOnlyList<GeoPoint> route, IEnumerable<JamLine> jams)
    {
        var levels = new int[route.Count];
        foreach (var jam in jams)
        {
            if (jam.Geometry.Count < 2) continue;
            var jb = BBox.Of(jam.Geometry).Expand(MatchToleranceM);
            for (var i = 0; i < route.Count; i++)
            {
                var p = route[i];
                if (!jb.Contains(p.Lat, p.Lng) || levels[i] >= jam.Level) continue;
                if (GeoMath.ProjectOnLine(jam.Geometry, p).DistanceM <= MatchToleranceM) levels[i] = jam.Level;
            }
        }
        return levels;
    }

    /// <summary>Compresses per-vertex levels into <c>{fromIndex,toIndex,level}</c> runs (level &gt; 0 only).</summary>
    public static IReadOnlyList<TrafficSegment> ToSegments(int[] levels)
    {
        var result = new List<TrafficSegment>();
        var i = 0;
        while (i < levels.Length)
        {
            if (levels[i] == 0) { i++; continue; }
            var start = i;
            var level = levels[i];
            while (i + 1 < levels.Length && levels[i + 1] == level) i++;
            // A run must span at least one edge.
            var end = Math.Min(Math.Max(i, start + 1), levels.Length - 1);
            if (end > start) result.Add(new TrafficSegment(start, end, level));
            i++;
        }
        return result;
    }

    /// <summary>Time multiplier on jammed edges by level.</summary>
    public static double SlowdownFactor(int level) => level switch
    {
        <= 0 => 1.0,
        1 => 1.6,
        2 => 2.4,
        3 => 4.0,
        _ => 7.0,
    };

    /// <summary>
    /// Scales the route duration by the live speed factor on jammed edges.
    /// Assumes uniform speed along the route (we only know Valhalla's total time).
    /// </summary>
    public static double TrafficDuration(IReadOnlyList<GeoPoint> route, IReadOnlyList<TrafficSegment> segments, double durationS, double distanceM)
    {
        if (segments.Count == 0 || distanceM <= 0) return durationS;
        var secondsPerMetre = durationS / distanceM;
        double extra = 0;
        foreach (var s in segments)
        {
            double len = 0;
            for (var k = s.FromIndex + 1; k <= s.ToIndex && k < route.Count; k++) len += GeoMath.HaversineM(route[k - 1], route[k]);
            extra += len * secondsPerMetre * (SlowdownFactor(s.Level) - 1);
        }
        return durationS + extra;
    }

    public static string Describe(double durationS, double trafficDurationS, bool anyLiveData)
    {
        if (durationS <= 0) return "Typical traffic";
        var ratio = trafficDurationS / durationS;
        if (ratio >= 1.15) return "Heavier than usual";
        if (!anyLiveData || ratio < 1.02) return anyLiveData ? "Light traffic" : "Typical traffic";
        return "Typical traffic";
    }
}
