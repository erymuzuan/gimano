using System.Diagnostics;
using Gimano.Core.Traffic;
using NetTopologySuite.Geometries;
using Npgsql;

namespace Gimano.Worker.Traffic;

/// <summary>Loads road_segments into a <see cref="RoadIndex"/> at startup and reloads it when the importer replaces them.</summary>
public sealed class RoadIndexHolder(NpgsqlDataSource dataSource, ILogger<RoadIndexHolder> logger) : BackgroundService
{
    private volatile RoadIndex? _index;
    private (long Count, long MaxId) _loadedVersion = (-1, -1);

    public RoadIndex? Index => _index;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var delay = TimeSpan.FromSeconds(5);
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                var version = await VersionAsync(stoppingToken);
                if (version != _loadedVersion && version.Count > 0) await LoadAsync(version, stoppingToken);
                delay = TimeSpan.FromMinutes(5);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                logger.LogWarning("Road index load failed ({Message}); retrying", ex.Message);
                delay = TimeSpan.FromSeconds(15);
            }
            await Task.Delay(delay, stoppingToken);
        }
    }

    private async Task<(long Count, long MaxId)> VersionAsync(CancellationToken ct)
    {
        await using var cmd = dataSource.CreateCommand("SELECT count(*), coalesce(max(id), 0) FROM road_segments");
        await using var r = await cmd.ExecuteReaderAsync(ct);
        await r.ReadAsync(ct);
        return (r.GetInt64(0), r.GetInt64(1));
    }

    private async Task LoadAsync((long Count, long MaxId) version, CancellationToken ct)
    {
        var sw = Stopwatch.StartNew();
        var before = GC.GetTotalMemory(true);
        var b = new RoadIndex.Builder((int)version.Count);
        await using (var cmd = dataSource.CreateCommand("SELECT id, oneway, geom FROM road_segments"))
        await using (var r = await cmd.ExecuteReaderAsync(ct))
        {
            var buf = new double[512];
            while (await r.ReadAsync(ct))
            {
                var ls = r.GetFieldValue<LineString>(2);
                var seq = ls.CoordinateSequence;
                if (buf.Length < seq.Count * 2) buf = new double[seq.Count * 2];
                for (var i = 0; i < seq.Count; i++) { buf[2 * i] = seq.GetX(i); buf[2 * i + 1] = seq.GetY(i); }
                b.Add(r.GetInt64(0), r.GetBoolean(1), buf.AsSpan(0, seq.Count * 2));
            }
        }
        var index = b.Build();
        _index = index;
        _loadedVersion = version;
        var after = GC.GetTotalMemory(true);
        logger.LogInformation("Road index: {Segments:n0} segments, {Vertices:n0} vertices, {Cells:n0} cells, ~{MB:n0} MB (heap +{Heap:n0} MB) in {Seconds:n1}s",
            index.Count, index.Vertices, index.Cells, index.EstimatedBytes / 1_048_576.0, (after - before) / 1_048_576.0, sw.Elapsed.TotalSeconds);
    }
}
