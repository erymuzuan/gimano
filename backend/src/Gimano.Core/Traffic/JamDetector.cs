using Gimano.Core.Geo;

namespace Gimano.Core.Traffic;

public readonly record struct SpeedSample(Guid UserId, double SpeedKmh, DateTimeOffset Ts);

public sealed record SegmentStats(long SegmentId, double MeanSpeedKmh, int Samples, int DistinctUsers);

/// <summary>Jam rules from docs/architecture.md ("Traffic model").</summary>
public static class JamDetector
{
    public static readonly TimeSpan Window = TimeSpan.FromMinutes(5);
    public const int MinDistinctUsers = 3;
    public const double JamRatio = 0.5;

    /// <summary>
    /// Level 0 = free flowing. A jam needs mean &lt; 0.5 × free-flow with ≥ 3 distinct users.
    /// Level by ratio: [0.35,0.5) → 1, [0.2,0.35) → 2, [0.1,0.2) → 3, &lt; 0.1 → 4 (standstill).
    /// </summary>
    public static int Level(double meanSpeedKmh, double freeFlowKmh, int distinctUsers)
    {
        if (freeFlowKmh <= 0 || distinctUsers < MinDistinctUsers) return 0;
        var ratio = meanSpeedKmh / freeFlowKmh;
        return ratio switch
        {
            >= JamRatio => 0,
            >= 0.35 => 1,
            >= 0.20 => 2,
            >= 0.10 => 3,
            _ => 4,
        };
    }

    /// <summary>Rolling mean over the window; samples older than <paramref name="now"/> − window are ignored.</summary>
    public static SegmentStats? Aggregate(long segmentId, IEnumerable<SpeedSample> samples, DateTimeOffset now)
    {
        var cutoff = now - Window;
        var live = samples.Where(s => s.Ts >= cutoff && s.Ts <= now + TimeSpan.FromMinutes(1)).ToList();
        if (live.Count == 0) return null;
        return new SegmentStats(segmentId, live.Average(s => s.SpeedKmh), live.Count, live.Select(s => s.UserId).Distinct().Count());
    }

    /// <summary>Extra seconds a jam costs versus free-flow over <paramref name="lengthM"/>.</summary>
    public static double DelaySeconds(double lengthM, double meanSpeedKmh, double freeFlowKmh)
    {
        if (freeFlowKmh <= 0) return 0;
        var actual = lengthM / (Math.Max(meanSpeedKmh, 3) / 3.6);
        var free = lengthM / (freeFlowKmh / 3.6);
        return Math.Max(0, actual - free);
    }

    /// <summary>
    /// Merges adjacent jammed segments (sharing an OSM node, end→start) into runs.
    /// Each run is ordered along travel direction. Segments never appear in two runs.
    /// </summary>
    public static IReadOnlyList<IReadOnlyList<JamSegment>> MergeAdjacent(IEnumerable<JamSegment> jammed)
    {
        var list = jammed.ToList();
        var byFrom = list.GroupBy(s => s.FromNode).ToDictionary(g => g.Key, g => g.ToList());
        var hasPredecessor = new HashSet<long>(list.Where(s => list.Any(p => p.SegmentId != s.SegmentId && p.ToNode == s.FromNode)).Select(s => s.SegmentId));
        var used = new HashSet<long>();
        var runs = new List<IReadOnlyList<JamSegment>>();

        // Start from segments with no jammed predecessor, then sweep leftovers (cycles).
        foreach (var start in list.OrderBy(s => hasPredecessor.Contains(s.SegmentId)).ThenBy(s => s.SegmentId))
        {
            if (!used.Add(start.SegmentId)) continue;
            var run = new List<JamSegment> { start };
            var cur = start;
            while (byFrom.TryGetValue(cur.ToNode, out var next))
            {
                var n = next.Where(x => !used.Contains(x.SegmentId)).OrderBy(x => x.SegmentId).FirstOrDefault();
                if (n is null) break;
                used.Add(n.SegmentId);
                run.Add(n);
                cur = n;
            }
            runs.Add(run);
        }
        return runs;
    }

    public static MergedJam ToMergedJam(IReadOnlyList<JamSegment> run)
    {
        var pts = new List<GeoPoint>();
        foreach (var s in run)
        {
            var g = s.Geometry;
            pts.AddRange(pts.Count > 0 && g.Count > 0 && pts[^1] == g[0] ? g.Skip(1) : g);
        }
        var length = run.Sum(s => s.LengthM);
        var speed = length > 0 ? run.Sum(s => s.MeanSpeedKmh * s.LengthM) / length : run.Average(s => s.MeanSpeedKmh);
        var delay = run.Sum(s => DelaySeconds(s.LengthM, s.MeanSpeedKmh, s.FreeFlowKmh));
        var level = (int)Math.Round(length > 0 ? run.Sum(s => s.Level * s.LengthM) / length : run.Max(s => s.Level), MidpointRounding.AwayFromZero);
        var street = run.Where(s => !string.IsNullOrEmpty(s.Street)).GroupBy(s => s.Street)
            .OrderByDescending(g => g.Sum(x => x.LengthM)).Select(g => g.Key).FirstOrDefault();
        return new MergedJam(run.Min(s => s.SegmentId), Math.Clamp(level, 1, 4), speed, delay, length, pts, street);
    }
}

public sealed record JamSegment(
    long SegmentId, long FromNode, long ToNode, int Level, double MeanSpeedKmh, double FreeFlowKmh,
    double LengthM, IReadOnlyList<GeoPoint> Geometry, string? Street);

public sealed record MergedJam(long AnchorSegmentId, int Level, double SpeedKmh, double DelayS, double LengthM,
    IReadOnlyList<GeoPoint> Geometry, string? Street);
