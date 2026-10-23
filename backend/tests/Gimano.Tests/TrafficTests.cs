using Gimano.Core.Geo;
using Gimano.Core.Traffic;

namespace Gimano.Tests;

public class TrafficTests
{
    private static readonly DateTimeOffset Now = new(2026, 10, 23, 8, 0, 0, TimeSpan.Zero);

    [Theory]
    [InlineData(60, 100, 5, 0)]   // ratio 0.6 → free
    [InlineData(50, 100, 5, 0)]   // exactly 0.5 is not a jam
    [InlineData(45, 100, 5, 1)]
    [InlineData(30, 100, 5, 2)]
    [InlineData(15, 100, 5, 3)]
    [InlineData(5, 100, 5, 4)]
    [InlineData(5, 100, 2, 0)]    // < 3 distinct users
    [InlineData(5, 0, 5, 0)]      // unknown free-flow
    public void Jam_level_by_speed_ratio(double mean, double freeFlow, int users, int expected) =>
        Assert.Equal(expected, JamDetector.Level(mean, freeFlow, users));

    [Fact]
    public void Aggregate_uses_5_minute_window_and_distinct_users()
    {
        var u1 = Guid.NewGuid(); var u2 = Guid.NewGuid(); var u3 = Guid.NewGuid();
        var samples = new[]
        {
            new SpeedSample(u1, 10, Now.AddMinutes(-1)),
            new SpeedSample(u1, 20, Now.AddMinutes(-2)),
            new SpeedSample(u2, 30, Now.AddMinutes(-4)),
            new SpeedSample(u3, 100, Now.AddMinutes(-6)), // outside window
        };
        var s = JamDetector.Aggregate(42, samples, Now)!;
        Assert.Equal(20, s.MeanSpeedKmh, 6);
        Assert.Equal(3, s.Samples);
        Assert.Equal(2, s.DistinctUsers);
        Assert.Null(JamDetector.Aggregate(42, [new SpeedSample(u1, 10, Now.AddMinutes(-10))], Now));
    }

    [Fact]
    public void Three_slow_drivers_make_a_jam()
    {
        var samples = Enumerable.Range(0, 3).Select(i => new SpeedSample(Guid.NewGuid(), 12, Now.AddSeconds(-i * 20)));
        var s = JamDetector.Aggregate(1, samples, Now)!;
        Assert.Equal(3, JamDetector.Level(s.MeanSpeedKmh, 90, s.DistinctUsers)); // 12/90 = 0.13
    }

    [Fact]
    public void Merges_adjacent_segments_into_one_jam()
    {
        JamSegment Seg(long id, long from, long to, int level, double lng0, double lng1) =>
            new(id, from, to, level, 10, 60, 300, [new GeoPoint(3.1, lng0), new GeoPoint(3.1, lng1)], "Jalan Kuching");

        var runs = JamDetector.MergeAdjacent(
        [
            Seg(3, 102, 103, 2, 101.006, 101.009),
            Seg(1, 100, 101, 2, 101.000, 101.003),
            Seg(2, 101, 102, 4, 101.003, 101.006),
            Seg(9, 900, 901, 1, 102.000, 102.003), // isolated
        ]);
        Assert.Equal(2, runs.Count);
        var main = runs.Single(r => r.Count == 3);
        Assert.Equal([1L, 2L, 3L], main.Select(s => s.SegmentId));

        var jam = JamDetector.ToMergedJam(main);
        Assert.Equal(1, jam.AnchorSegmentId);
        Assert.Equal(900, jam.LengthM);
        Assert.Equal(4, jam.Geometry.Count); // shared vertices de-duplicated
        Assert.Equal(3, jam.Level);          // length-weighted mean of 2,4,2 = 2.67 → 3
        Assert.Equal("Jalan Kuching", jam.Street);
        Assert.True(jam.DelayS > 0);
    }

    [Fact]
    public void Merge_handles_cycles_without_looping_forever()
    {
        var g = new List<GeoPoint> { new(3, 101), new(3, 101.001) };
        var runs = JamDetector.MergeAdjacent(
        [
            new JamSegment(1, 1, 2, 1, 10, 60, 100, g, null),
            new JamSegment(2, 2, 3, 1, 10, 60, 100, g, null),
            new JamSegment(3, 3, 1, 1, 10, 60, 100, g, null),
        ]);
        Assert.Equal(3, runs.Sum(r => r.Count));
    }

    [Fact]
    public void Delay_is_extra_time_versus_free_flow()
    {
        // 1 km at 10 km/h = 360 s; at 60 km/h = 60 s → 300 s delay.
        Assert.Equal(300, JamDetector.DelaySeconds(1000, 10, 60), 6);
        Assert.Equal(0, JamDetector.DelaySeconds(1000, 80, 60));
    }

    [Fact]
    public void Snapper_picks_nearest_heading_compatible_segment()
    {
        var p = new GeoPoint(3.1000, 101.6000);
        // Eastbound one-way 5 m north, westbound one-way 10 m south.
        var east = new SnapCandidate(1, true, [new GeoPoint(3.10005, 101.599), new GeoPoint(3.10005, 101.601)]);
        var west = new SnapCandidate(2, true, [new GeoPoint(3.09991, 101.601), new GeoPoint(3.09991, 101.599)]);
        var farAway = new SnapCandidate(3, false, [new GeoPoint(3.1010, 101.599), new GeoPoint(3.1010, 101.601)]);

        Assert.Equal(1, SegmentSnapper.Snap([east, west, farAway], p, heading: 90, speedKmh: 40));
        Assert.Equal(2, SegmentSnapper.Snap([east, west, farAway], p, heading: 270, speedKmh: 40));
        Assert.Null(SegmentSnapper.Snap([farAway], p, heading: 90, speedKmh: 40));
        // Two-way roads accept both directions.
        var twoWay = east with { Oneway = false };
        Assert.Equal(1, SegmentSnapper.Snap([twoWay], p, heading: 270, speedKmh: 40));
        // Heading ignored when nearly stationary.
        Assert.Equal(1, SegmentSnapper.Snap([east], p, heading: 270, speedKmh: 1));
    }

    [Fact]
    public void Traffic_segments_compress_vertex_levels()
    {
        var segs = TrafficOverlay.ToSegments([0, 2, 2, 2, 0, 0, 4, 4, 1]);
        Assert.Equal(2, segs.Count); // a trailing single vertex spans no edge
        Assert.Equal((1, 3, 2), (segs[0].FromIndex, segs[0].ToIndex, segs[0].Level));
        Assert.Equal((6, 7, 4), (segs[1].FromIndex, segs[1].ToIndex, segs[1].Level));
        // An isolated jammed vertex mid-route still colours the following edge.
        Assert.Equal((1, 2, 3), TrafficOverlay.ToSegments([0, 3, 0, 0]).Select(x => (x.FromIndex, x.ToIndex, x.Level)).Single());
    }

    [Fact]
    public void Describes_traffic()
    {
        Assert.Equal("Typical traffic", TrafficOverlay.Describe(600, 600, anyLiveData: false));
        Assert.Equal("Light traffic", TrafficOverlay.Describe(600, 600, anyLiveData: true));
        Assert.Equal("Heavier than usual", TrafficOverlay.Describe(600, 800, anyLiveData: true));
    }
}
