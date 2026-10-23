using System.Diagnostics;
using Gimano.Core.Messaging;
using Gimano.Core.Traffic;
using RabbitMQ.Client;
using RabbitMQ.Client.Events;

namespace Gimano.Worker.Traffic;

public sealed class TrafficMetrics
{
    public long Messages, Batches, Pings, Snapped, Unsnapped, NoIndex, Malformed, FlushRows, FlushCount;
    public double FlushMsTotal, FlushMsMax;
    private readonly Lock _lock = new();

    public void RecordFlush(int rows, TimeSpan elapsed)
    {
        lock (_lock)
        {
            FlushRows += rows; FlushCount++;
            FlushMsTotal += elapsed.TotalMilliseconds;
            FlushMsMax = Math.Max(FlushMsMax, elapsed.TotalMilliseconds);
        }
    }

    public (long Messages, long Batches, long Pings, long Snapped, long Unsnapped, long NoIndex, long Malformed, long FlushRows, long FlushCount, double FlushMsAvg, double FlushMsMax) TakeSnapshot()
    {
        lock (_lock)
        {
            var s = (Interlocked.Exchange(ref Messages, 0), Interlocked.Exchange(ref Batches, 0), Interlocked.Exchange(ref Pings, 0),
                Interlocked.Exchange(ref Snapped, 0), Interlocked.Exchange(ref Unsnapped, 0), Interlocked.Exchange(ref NoIndex, 0),
                Interlocked.Exchange(ref Malformed, 0), FlushRows, FlushCount, FlushCount == 0 ? 0 : FlushMsTotal / FlushCount, FlushMsMax);
            FlushRows = 0; FlushCount = 0; FlushMsTotal = 0; FlushMsMax = 0;
            return s;
        }
    }
}

/// <summary>
/// Consumes the transient <c>gimano.locations</c> stream: non-durable queue with 30 s TTL and drop-head overflow,
/// auto-ack, prefetch 2000. Per ping: in-memory driver update + in-memory snap + speed window. No database I/O.
/// </summary>
public sealed class LocationStreamConsumer(
    RabbitMqConnection connection, RoadIndexHolder roads, SpeedWindow window, DriverStore drivers, TrafficMetrics metrics,
    ILogger<LocationStreamConsumer> logger) : BackgroundService
{
    public const ushort Prefetch = 2000;
    public static readonly TimeSpan StatsEvery = TimeSpan.FromSeconds(30);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        _ = Task.Run(() => LogStatsAsync(stoppingToken), stoppingToken);
        var delay = TimeSpan.FromSeconds(2);
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                var conn = await connection.GetAsync(stoppingToken);
                await using var channel = await conn.CreateChannelAsync(cancellationToken: stoppingToken);
                await channel.ExchangeDeclareAsync(LocationStream.Exchange, ExchangeType.Topic, durable: false, autoDelete: false, cancellationToken: stoppingToken);
                await channel.QueueDeclareAsync(LocationStream.TrafficQueue, durable: false, exclusive: false, autoDelete: false,
                    arguments: LocationStream.QueueArguments(), cancellationToken: stoppingToken);
                await channel.QueueBindAsync(LocationStream.TrafficQueue, LocationStream.Exchange, LocationStream.RoutingKey, cancellationToken: stoppingToken);
                await channel.BasicQosAsync(0, Prefetch, false, stoppingToken);
                await RemoveLegacyDurableQueueAsync(conn, stoppingToken);

                var consumer = new AsyncEventingBasicConsumer(channel);
                consumer.ReceivedAsync += (_, ea) =>
                {
                    Handle(ea.Body.Span);
                    return Task.CompletedTask;
                };
                await channel.BasicConsumeAsync(LocationStream.TrafficQueue, autoAck: true, consumer, stoppingToken);
                logger.LogInformation("Consuming {Queue} (transient, auto-ack, prefetch {Prefetch})", LocationStream.TrafficQueue, Prefetch);
                delay = TimeSpan.FromSeconds(2);
                while (!stoppingToken.IsCancellationRequested && channel.IsOpen) await Task.Delay(1000, stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { return; }
            catch (Exception ex)
            {
                logger.LogWarning("Location stream consumer not connected ({Message}); retrying in {Delay}s", ex.Message, delay.TotalSeconds);
                await Task.Delay(delay, stoppingToken).ContinueWith(_ => { });
                delay = TimeSpan.FromSeconds(Math.Min(30, delay.TotalSeconds * 2));
            }
        }
    }

    internal void Handle(ReadOnlySpan<byte> body)
    {
        List<LocationBatchMessage> batches;
        try { batches = LocationWire.Decode(body); }
        catch (Exception) { Interlocked.Increment(ref metrics.Malformed); return; }

        Interlocked.Increment(ref metrics.Messages);
        var index = roads.Index;
        long pings = 0, snapped = 0, unsnapped = 0, noIndex = 0;
        foreach (var b in batches)
        {
            foreach (var p in b.Points)
            {
                pings++;
                drivers.Update(b.SessionId, p.Lat, p.Lng, p.Heading, p.SpeedKmh, b.Mood, p.TsUnixMs);
                if (index is null) { noIndex++; continue; }
                double? heading = p.Heading is >= 0 and <= 360 ? p.Heading : null;
                if (index.Snap(p.Lat, p.Lng, heading, p.SpeedKmh) is { } seg)
                {
                    snapped++;
                    window.Add(seg, new SpeedSample(b.SessionId, p.SpeedKmh, DateTimeOffset.FromUnixTimeMilliseconds(p.TsUnixMs)));
                }
                else unsnapped++;
            }
        }
        Interlocked.Add(ref metrics.Batches, batches.Count);
        Interlocked.Add(ref metrics.Pings, pings);
        Interlocked.Add(ref metrics.Snapped, snapped);
        Interlocked.Add(ref metrics.Unsnapped, unsnapped);
        Interlocked.Add(ref metrics.NoIndex, noIndex);
    }

    private async Task RemoveLegacyDurableQueueAsync(IConnection conn, CancellationToken ct)
    {
        // Before the transient stream, pings went through the durable queue gimano.worker.traffic. Remove it once.
        try
        {
            await using var ch = await conn.CreateChannelAsync(cancellationToken: ct);
            await ch.QueueDeleteAsync("gimano.worker.traffic", ifUnused: false, ifEmpty: false, cancellationToken: ct);
        }
        catch (Exception) { /* not there */ }
    }

    private async Task LogStatsAsync(CancellationToken ct)
    {
        var last = Stopwatch.StartNew();
        while (!ct.IsCancellationRequested)
        {
            await Task.Delay(StatsEvery, ct).ContinueWith(_ => { });
            var s = metrics.TakeSnapshot();
            var secs = last.Elapsed.TotalSeconds;
            last.Restart();
            if (s.Pings == 0 && s.FlushRows == 0) continue;
            var hit = s.Snapped + s.Unsnapped == 0 ? 0 : 100.0 * s.Snapped / (s.Snapped + s.Unsnapped);
            using var proc = Process.GetCurrentProcess();
            logger.LogInformation(
                "Traffic: {Rate:n0} pings/s ({Batches:n0} batches in {Msgs:n0} msgs), snap hit {Hit:n1}%, no-index {NoIndex}, malformed {Bad}; " +
                "drivers {Drivers:n0}; snapshots {Flushes} (last {Rows:n0} dots total) avg {Avg:n1} ms max {Max:n1} ms; working set {WS:n0} MB",
                s.Pings / secs, s.Batches, s.Messages, hit, s.NoIndex, s.Malformed, drivers.Count, s.FlushRows, s.FlushCount,
                s.FlushMsAvg, s.FlushMsMax, proc.WorkingSet64 / 1_048_576.0);
        }
    }
}
