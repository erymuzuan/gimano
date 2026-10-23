using Gimano.Core.Data;
using Gimano.Core.Domain;
using Gimano.Core.Geo;
using Gimano.Core.Messaging;
using Gimano.Core.Traffic;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Worker.Traffic;

/// <summary>Consumes <c>location.ping</c>: updates driver positions and snaps pings to road segments.</summary>
public sealed class TrafficAggregator(
    RabbitMqConnection connection, IServiceScopeFactory scopes, SpeedWindow window, ILogger<TrafficAggregator> logger)
    : RabbitConsumer<LocationPingEvent>(connection, logger)
{
    protected override string QueueName => "gimano.worker.traffic";
    protected override IReadOnlyList<string> RoutingKeyBindings => [RoutingKeys.LocationPing];
    protected override ushort Prefetch => 200;

    protected override async Task HandleAsync(LocationPingEvent ping, string routingKey, CancellationToken ct)
    {
        await using var scope = scopes.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<GimanoDbContext>();
        var p = new GeoPoint(ping.Lat, ping.Lng);
        var pt = p.ToPoint();
        double? heading = ping.Heading is >= 0 and <= 360 ? ping.Heading : null;

        // Driver dot (latest ping wins).
        var pos = await db.DriverPositions.FindAsync([ping.UserId], ct);
        if (pos is null) db.DriverPositions.Add(pos = new DriverPosition { UserId = ping.UserId, Location = pt });
        if (pos.UpdatedAt <= ping.Ts)
        {
            pos.Location = pt;
            pos.Heading = heading ?? pos.Heading;
            pos.SpeedKmh = ping.SpeedKmh;
            pos.Mood = ping.Mood ?? pos.Mood;
            pos.UpdatedAt = ping.Ts;
        }
        await db.SaveChangesAsync(ct);

        // Snap: PostGIS KNN within 25 m, then heading compatibility in memory.
        var candidates = await db.RoadSegments.AsNoTracking()
            .Where(s => s.Geom.IsWithinDistance(pt, SegmentSnapper.MaxSnapDistanceM))
            .OrderBy(s => s.Geom.Distance(pt))
            .Take(6)
            .Select(s => new { s.Id, s.Oneway, s.Geom })
            .ToListAsync(ct);
        var segmentId = SegmentSnapper.Snap(
            candidates.Select(c => new SnapCandidate(c.Id, c.Oneway, GeoMath.FromLineString(c.Geom))), p, heading, ping.SpeedKmh);
        if (segmentId is { } id) window.Add(id, new SpeedSample(ping.UserId, ping.SpeedKmh, ping.Ts));
    }
}
