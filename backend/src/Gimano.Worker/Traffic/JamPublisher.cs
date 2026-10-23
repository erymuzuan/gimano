using Gimano.Core.Data;
using Gimano.Core.Domain;
using Gimano.Core.Geo;
using Gimano.Core.Messaging;
using Gimano.Core.Traffic;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Worker.Traffic;

/// <summary>Every 30 s: rolls segment speeds up, detects jams, merges adjacent segments and publishes <c>jam.updated</c>.</summary>
public sealed class JamPublisher(
    IServiceScopeFactory scopes, SpeedWindow window, IEventPublisher bus, TimeProvider clock, ILogger<JamPublisher> logger)
    : BackgroundService
{
    public static readonly TimeSpan Interval = TimeSpan.FromSeconds(30);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(Interval);
        do
        {
            try { await TickAsync(stoppingToken); }
            catch (Exception ex) when (ex is not OperationCanceledException) { logger.LogError(ex, "Jam computation failed"); }
        } while (await timer.WaitForNextTickAsync(stoppingToken));
    }

    internal async Task TickAsync(CancellationToken ct)
    {
        var now = clock.GetUtcNow();
        var stats = window.Snapshot().ToDictionary(s => s.SegmentId);

        await using var scope = scopes.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<GimanoDbContext>();

        var jammed = new List<JamSegment>();
        if (stats.Count > 0)
        {
            var ids = stats.Keys.ToList();
            var segments = await db.RoadSegments.AsNoTracking().Where(s => ids.Contains(s.Id)).ToListAsync(ct);
            var existing = await db.SegmentSpeeds.Where(s => ids.Contains(s.SegmentId)).ToDictionaryAsync(s => s.SegmentId, ct);
            foreach (var seg in segments)
            {
                var st = stats[seg.Id];
                var level = JamDetector.Level(st.MeanSpeedKmh, seg.FreeFlowKmh, st.DistinctUsers);
                if (!existing.TryGetValue(seg.Id, out var row)) db.SegmentSpeeds.Add(row = new SegmentSpeed { SegmentId = seg.Id });
                row.MeanSpeedKmh = st.MeanSpeedKmh;
                row.FreeFlowKmh = seg.FreeFlowKmh;
                row.Samples = st.Samples;
                row.DistinctUsers = st.DistinctUsers;
                row.Level = level;
                row.UpdatedAt = now;
                if (level > 0)
                    jammed.Add(new JamSegment(seg.Id, seg.FromNode, seg.ToNode, level, st.MeanSpeedKmh, seg.FreeFlowKmh,
                        seg.LengthM, GeoMath.FromLineString(seg.Geom), seg.Name ?? seg.Ref));
            }
        }
        await db.SegmentSpeeds.Where(s => s.UpdatedAt < now - JamDetector.Window).ExecuteDeleteAsync(ct);

        var merged = JamDetector.MergeAdjacent(jammed).Select(JamDetector.ToMergedJam).ToList();
        var current = await db.Jams.ToListAsync(ct);
        var byAnchor = current.ToDictionary(j => j.AnchorSegmentId);
        var keep = new HashSet<Guid>();
        var changed = new List<Jam>();

        foreach (var m in merged)
        {
            if (!byAnchor.TryGetValue(m.AnchorSegmentId, out var jam))
            {
                jam = new Jam { AnchorSegmentId = m.AnchorSegmentId, Geom = GeoMath.ToLineString(m.Geometry) };
                db.Jams.Add(jam);
            }
            jam.Level = m.Level;
            jam.SpeedKmh = m.SpeedKmh;
            jam.DelayS = m.DelayS;
            jam.LengthM = m.LengthM;
            jam.Street = m.Street;
            jam.Geom = GeoMath.ToLineString(m.Geometry);
            jam.UpdatedAt = now;
            keep.Add(jam.Id);
            changed.Add(jam);
        }
        var cleared = current.Where(j => !keep.Contains(j.Id)).ToList();
        db.Jams.RemoveRange(cleared);
        await db.SaveChangesAsync(ct);

        foreach (var j in changed) await bus.PublishAsync(RoutingKeys.JamUpdated, j.ToDto(), ct);
        foreach (var j in cleared) await bus.PublishAsync(RoutingKeys.JamCleared, j.ToDto() with { Level = 0 }, ct);
        if (changed.Count + cleared.Count > 0)
            logger.LogInformation("Jams: {Active} active, {Cleared} cleared ({Segments} live segments)", changed.Count, cleared.Count, stats.Count);
    }
}
