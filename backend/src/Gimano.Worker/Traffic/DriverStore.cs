using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text.Json;
using Gimano.Core.Contracts;
using Gimano.Core.Messaging;
using Gimano.Core.Traffic;
using RabbitMQ.Client;

namespace Gimano.Worker.Traffic;

/// <summary>Latest position per ping session, in memory only (privacy rule: never persisted, never keyed by user).</summary>
public sealed class DriverStore(TimeProvider clock)
{
    public static readonly TimeSpan StaleAfter = TimeSpan.FromMinutes(2);

    public sealed class State
    {
        public double Lat, Lng, Heading, SpeedKmh;
        public string Mood = "happy";
        public long TsUnixMs;
    }

    private readonly ConcurrentDictionary<Guid, State> _drivers = new();

    public int Count => _drivers.Count;

    public void Update(Guid sessionId, double lat, double lng, double heading, double speedKmh, string mood, long tsUnixMs)
    {
        var s = _drivers.GetOrAdd(sessionId, _ => new State());
        lock (s)
        {
            if (tsUnixMs < s.TsUnixMs) return;
            s.Lat = lat; s.Lng = lng; s.SpeedKmh = speedKmh; s.Mood = mood; s.TsUnixMs = tsUnixMs;
            if (heading >= 0) s.Heading = heading;
        }
    }

    /// <summary>All drivers seen in the last 2 min (evicting older ones). Keyed by rotating session id, never a user id.</summary>
    public List<State> Current()
    {
        var cutoff = clock.GetUtcNow().Add(-StaleAfter).ToUnixTimeMilliseconds();
        var list = new List<State>(_drivers.Count);
        foreach (var (id, s) in _drivers)
        {
            lock (s)
            {
                if (s.TsUnixMs < cutoff) { _drivers.TryRemove(id, out _); continue; }
                list.Add(new State { Lat = s.Lat, Lng = s.Lng, Heading = s.Heading, SpeedKmh = s.SpeedKmh, Mood = s.Mood, TsUnixMs = s.TsUnixMs });
            }
        }
        return list;
    }
}

/// <summary>
/// Every 5 s: publishes the anonymised driver cells (≥ 3 drivers, rounded) to the API over the transient stream.
/// Nothing is written to the database: driver positions exist only in this process's memory.
/// </summary>
public sealed class DriverSnapshotPublisher(DriverStore store, LocationStreamConnection connection, TrafficMetrics metrics, ILogger<DriverSnapshotPublisher> logger)
    : BackgroundService
{
    public static readonly TimeSpan Interval = TimeSpan.FromSeconds(5);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(Interval);
        IChannel? channel = null;
        var props = new BasicProperties { ContentType = "application/json", DeliveryMode = DeliveryModes.Transient, Expiration = "10000" };
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            try
            {
                var sw = Stopwatch.StartNew();
                var cells = DriverCells.Anonymise(store.Current().Select(d => (d.Lat, d.Lng, d.Heading, d.Mood)));
                var body = JsonSerializer.SerializeToUtf8Bytes(new DriversSnapshot(DateTimeOffset.UtcNow, cells), GimanoJson.Options);
                if (channel is not { IsOpen: true })
                {
                    var conn = await connection.GetAsync(stoppingToken);
                    channel = await conn.CreateChannelAsync(cancellationToken: stoppingToken);
                    await channel.ExchangeDeclareAsync(LocationStream.Exchange, ExchangeType.Topic, durable: false, autoDelete: false, cancellationToken: stoppingToken);
                }
                await channel.BasicPublishAsync(LocationStream.Exchange, LocationStream.DriversSnapshotKey, false, props, body, stoppingToken);
                metrics.RecordFlush(cells.Count, sw.Elapsed);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                channel = null;
                logger.LogWarning("Driver snapshot publish failed: {Message}", ex.Message);
            }
        }
    }
}
