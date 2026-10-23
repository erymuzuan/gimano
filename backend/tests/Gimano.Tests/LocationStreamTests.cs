using Gimano.Api.Endpoints;
using Gimano.Core.Contracts;
using Gimano.Core.Geo;
using Gimano.Core.Messaging;
using Gimano.Core.Traffic;
using Gimano.Worker.Traffic;

namespace Gimano.Tests;

public class RoadIndexTests
{
    // Fixtures shaped like real OSM chunks around SS2 / Federal Highway.
    private static readonly SnapCandidate[] Roads =
    [
        new(1, true, [new(3.10005, 101.599), new(3.10005, 101.601)]),                         // one-way east
        new(2, true, [new(3.09991, 101.601), new(3.09991, 101.599)]),                         // one-way west (other carriageway)
        new(3, false, [new(3.1010, 101.599), new(3.1010, 101.601)]),                          // two-way, ~110 m north
        new(4, false, [new(3.0990, 101.6000), new(3.0995, 101.6004), new(3.1001, 101.6012), new(3.1007, 101.6015)]), // curved local road
        new(5, false, [new(3.1100, 101.6100), new(3.1110, 101.6110)]),                        // far away
    ];

    private static RoadIndex Build()
    {
        var b = new RoadIndex.Builder();
        foreach (var r in Roads) b.Add(r.SegmentId, r.Oneway, r.Geometry);
        return b.Build();
    }

    [Theory]
    [InlineData(3.1000, 101.6000, 90.0, 40.0)]
    [InlineData(3.1000, 101.6000, 270.0, 40.0)]
    [InlineData(3.1000, 101.6000, null, null)]
    [InlineData(3.1000, 101.6000, 0.0, 40.0)]
    [InlineData(3.1009, 101.6000, 90.0, 40.0)]
    [InlineData(3.1003, 101.6010, 45.0, 30.0)]
    [InlineData(3.1003, 101.6010, 225.0, 30.0)]
    [InlineData(3.1050, 101.6050, 90.0, 40.0)]
    [InlineData(3.1000, 101.6000, 270.0, 2.0)]
    public void In_memory_snap_matches_the_PostGIS_path_rules(double lat, double lng, double? heading, double? speed)
    {
        var expected = SegmentSnapper.Snap(Roads, new GeoPoint(lat, lng), heading, speed);
        Assert.Equal(expected, Build().Snap(lat, lng, heading, speed));
    }

    [Fact]
    public void Random_points_agree_with_reference_snapper()
    {
        var index = Build();
        var rnd = new Random(42);
        var agree = 0;
        for (var i = 0; i < 2000; i++)
        {
            var lat = 3.0985 + rnd.NextDouble() * 0.003;
            var lng = 101.5985 + rnd.NextDouble() * 0.003;
            double? heading = rnd.Next(5) == 0 ? null : rnd.NextDouble() * 360;
            var speed = rnd.NextDouble() * 80;
            if (SegmentSnapper.Snap(Roads, new GeoPoint(lat, lng), heading, speed) == index.Snap(lat, lng, heading, speed)) agree++;
        }
        // float32 coordinates (~1 m) can flip points right at the 25 m / 50° thresholds.
        Assert.True(agree >= 1990, $"agreement {agree}/2000");
    }

    [Fact]
    public void Segments_spanning_many_cells_are_found_everywhere_along_them()
    {
        var b = new RoadIndex.Builder();
        b.Add(9, false, [new GeoPoint(3.0, 101.0), new GeoPoint(3.0, 101.02)]); // 2.2 km straight line, 10+ cells
        var idx = b.Build();
        for (var lng = 101.0005; lng < 101.02; lng += 0.0013) Assert.Equal(9, idx.Snap(3.0001, lng, null));
        Assert.True(idx.Cells >= 10);
    }
}

public class LocationWireTests
{
    [Fact]
    public void Round_trips_coalesced_batches()
    {
        var u1 = Guid.NewGuid(); var u2 = Guid.NewGuid();
        var batches = new List<LocationBatchMessage>
        {
            new(u1, "chill", [new LocationSample(3.118123, 101.619456, 42.5f, 90f, 1_792_000_000_000), new LocationSample(3.1182, 101.6195, 41f, -1f, 1_792_000_001_000)]),
            new(u2, "happy", [new LocationSample(1.35, 103.82, 0f, 180f, 1_792_000_000_500)]),
        };
        var bytes = LocationWire.Encode(batches);
        var back = LocationWire.Decode(bytes);
        Assert.Equal(2, back.Count);
        Assert.Equal(u1, back[0].SessionId);
        Assert.Equal("chill", back[0].Mood);
        Assert.Equal(2, back[0].Points.Length);
        Assert.Equal(3.118123, back[0].Points[0].Lat);
        Assert.Equal(42.5f, back[0].Points[0].SpeedKmh);
        Assert.Equal(-1f, back[0].Points[1].Heading);
        Assert.Equal(1_792_000_000_500, back[1].Points[0].TsUnixMs);
        Assert.True(bytes.Length < 300, $"{bytes.Length} bytes");
    }

    [Fact]
    public void Queue_is_lossy_by_design()
    {
        var args = LocationStream.QueueArguments();
        Assert.Equal(30_000, args["x-message-ttl"]);
        Assert.Equal("drop-head", args["x-overflow"]);
        Assert.Equal(200_000, args["x-max-length"]);
        Assert.NotEqual(RoutingKeys.Exchange, LocationStream.Exchange);
    }

    [Fact]
    public void Samples_drop_inaccurate_fixes_clamp_time_and_sort()
    {
        var now = new DateTimeOffset(2026, 10, 23, 8, 0, 0, TimeSpan.Zero);
        var pts = new List<LocationPoint>
        {
            new(3.1, 101.6, 30, 90, 5, now.AddSeconds(-2)),
            new(3.1, 101.6, null, null, 80, now),          // inaccurate → dropped
            new(3.1, 101.6, -5, 400, 5, now.AddHours(-1)), // stale ts → now; bad heading → -1; negative speed → 0
            new(3.1, 101.6, 20, 0, null, now.AddSeconds(-4)),
        };
        var s = LiveEndpoints.ToSamples(pts, now);
        Assert.Equal(3, s.Length);
        Assert.True(s[0].TsUnixMs <= s[1].TsUnixMs && s[1].TsUnixMs <= s[2].TsUnixMs);
        Assert.Equal(now.ToUnixTimeMilliseconds(), s[2].TsUnixMs);
        Assert.Equal(-1f, s[2].Heading);
        Assert.Equal(0f, s[2].SpeedKmh);
    }
}

public class DriverStoreTests
{
    private sealed class FixedClock(DateTimeOffset now) : TimeProvider
    {
        public DateTimeOffset Now = now;
        public override DateTimeOffset GetUtcNow() => Now;
    }

    [Fact]
    public void Keeps_latest_position_in_memory_and_evicts_silent_drivers()
    {
        var clock = new FixedClock(new DateTimeOffset(2026, 10, 23, 8, 0, 0, TimeSpan.Zero));
        var store = new DriverStore(clock);
        var session = Guid.NewGuid();
        var t = clock.Now.ToUnixTimeMilliseconds();
        store.Update(session, 3.1, 101.6, 90, 40, "happy", t);
        store.Update(session, 3.2, 101.7, -1, 41, "happy", t + 1000);
        store.Update(session, 3.0, 101.5, 10, 10, "happy", t - 5000); // out of order → ignored

        var row = Assert.Single(store.Current());
        Assert.Equal(3.2, row.Lat);
        Assert.Equal(90, row.Heading); // unknown heading keeps the last one

        clock.Now = clock.Now.AddMinutes(3);
        Assert.Empty(store.Current());
        Assert.Equal(0, store.Count); // evicted after 2 min of silence
    }
}
