using System.Collections.Concurrent;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Microsoft.EntityFrameworkCore;
using Npgsql;

namespace Gimano.Worker.Traffic;

/// <summary>
/// Passive evidence for complaints (docs/complaints.md principle 3): distinct rotating sessions per segment, direction and
/// hour, counted in memory from the transient ping stream. Only the counts reach the database (segment_traversal_counts);
/// session ids never leave this process and are dropped when the hour ends. Kept 15 days.
/// </summary>
public sealed class TraversalCounter(TimeProvider clock)
{
    private readonly ConcurrentDictionary<(long Segment, IssueDirection Dir, DateTimeOffset Hour), HashSet<Guid>> _sessions = new();

    public static DateTimeOffset HourOf(DateTimeOffset t) => new(t.Year, t.Month, t.Day, t.Hour, 0, 0, TimeSpan.Zero);

    public void Add(long segmentId, IssueDirection dir, Guid sessionId, DateTimeOffset ts)
    {
        var set = _sessions.GetOrAdd((segmentId, dir, HourOf(ts.ToUniversalTime())), _ => []);
        lock (set) set.Add(sessionId);
    }

    /// <summary>Current counts; buckets of past hours are removed after being reported once more.</summary>
    public List<(long Segment, IssueDirection Dir, DateTimeOffset Hour, int Count)> Drain()
    {
        var current = HourOf(clock.GetUtcNow());
        var rows = new List<(long, IssueDirection, DateTimeOffset, int)>();
        foreach (var (key, set) in _sessions)
        {
            int n;
            lock (set) n = set.Count;
            rows.Add((key.Segment, key.Dir, key.Hour, n));
            if (key.Hour < current) _sessions.TryRemove(key, out _);
        }
        return rows;
    }
}

/// <summary>Every minute: writes traversal counts (GREATEST, so a restart mid-hour never lowers a count); hourly retention.</summary>
public sealed class TraversalFlusher(TraversalCounter counter, IServiceScopeFactory scopes, TimeProvider clock, ILogger<TraversalFlusher> logger)
    : BackgroundService
{
    public static readonly TimeSpan Keep = TimeSpan.FromDays(15);
    private DateTimeOffset _lastRetention = DateTimeOffset.MinValue;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromMinutes(1));
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            try { await FlushAsync(stoppingToken); }
            catch (Exception ex) when (ex is not OperationCanceledException) { logger.LogWarning("Traversal flush failed: {Message}", ex.Message); }
        }
    }

    internal async Task FlushAsync(CancellationToken ct)
    {
        var rows = counter.Drain();
        await using var scope = scopes.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<GimanoDbContext>();
        if (rows.Count > 0)
        {
            // Segment ids change at each import: store the stable (OSM way, seq) key.
            var ids = rows.Select(r => r.Segment).Distinct().ToList();
            var keys = await db.RoadSegments.AsNoTracking().Where(s => ids.Contains(s.Id))
                .Select(s => new { s.Id, s.OsmWayId, s.Seq }).ToDictionaryAsync(s => s.Id, ct);
            var conn = (NpgsqlConnection)db.Database.GetDbConnection();
            if (conn.State != System.Data.ConnectionState.Open) await conn.OpenAsync(ct);
            await using var batch = new NpgsqlBatch(conn);
            foreach (var r in rows)
            {
                if (!keys.TryGetValue(r.Segment, out var k)) continue;
                var cmd = new NpgsqlBatchCommand("""
                    INSERT INTO segment_traversal_counts (way_id, seq, direction, hour, count) VALUES ($1, $2, $3, $4, $5)
                    ON CONFLICT (way_id, seq, direction, hour) DO UPDATE SET count = GREATEST(segment_traversal_counts.count, EXCLUDED.count)
                    """);
                cmd.Parameters.Add(new NpgsqlParameter { Value = k.OsmWayId });
                cmd.Parameters.Add(new NpgsqlParameter { Value = k.Seq });
                cmd.Parameters.Add(new NpgsqlParameter { Value = r.Dir.ToString() });
                cmd.Parameters.Add(new NpgsqlParameter { Value = r.Hour });
                cmd.Parameters.Add(new NpgsqlParameter { Value = r.Count });
                batch.BatchCommands.Add(cmd);
            }
            if (batch.BatchCommands.Count > 0) await batch.ExecuteNonQueryAsync(ct);
        }
        var now = clock.GetUtcNow();
        if (now - _lastRetention >= TimeSpan.FromHours(1))
        {
            _lastRetention = now;
            var cutoff = now - Keep;
            await db.SegmentTraversalCounts.Where(c => c.Hour < cutoff).ExecuteDeleteAsync(ct);
        }
    }
}
