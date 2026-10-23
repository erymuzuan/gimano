using System.Text.Json;
using Gimano.Core.Contracts;
using Gimano.Core.Geo;
using Gimano.Core.Messaging;
using RabbitMQ.Client;
using RabbitMQ.Client.Events;

namespace Gimano.Api.Live;

/// <summary>Latest anonymised driver snapshot from the worker (in memory; never stored).</summary>
public sealed class DriverSnapshotStore(TimeProvider clock)
{
    public static readonly TimeSpan MaxAge = TimeSpan.FromSeconds(30);
    private volatile DriversSnapshot? _latest;

    public void Set(DriversSnapshot s) => _latest = s;

    public IReadOnlyList<DriverDto> InBBox(BBox b)
    {
        var s = _latest;
        if (s is null || clock.GetUtcNow() - s.At > MaxAge) return [];
        return s.Drivers.Where(d => b.Contains(d.Lat, d.Lng)).Take(5000).ToList();
    }
}

/// <summary>Per-instance exclusive, auto-delete, auto-ack queue for <c>drivers.snapshot</c> on the transient exchange.</summary>
public sealed class DriverSnapshotConsumer(LocationStreamConnection connection, DriverSnapshotStore store, ILogger<DriverSnapshotConsumer> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var delay = TimeSpan.FromSeconds(2);
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                var conn = await connection.GetAsync(stoppingToken);
                await using var ch = await conn.CreateChannelAsync(cancellationToken: stoppingToken);
                await ch.ExchangeDeclareAsync(LocationStream.Exchange, ExchangeType.Topic, durable: false, autoDelete: false, cancellationToken: stoppingToken);
                var q = await ch.QueueDeclareAsync("", durable: false, exclusive: true, autoDelete: true,
                    arguments: new Dictionary<string, object?> { ["x-max-length"] = 2, ["x-overflow"] = "drop-head" }, cancellationToken: stoppingToken);
                await ch.QueueBindAsync(q.QueueName, LocationStream.Exchange, LocationStream.DriversSnapshotKey, cancellationToken: stoppingToken);
                var consumer = new AsyncEventingBasicConsumer(ch);
                consumer.ReceivedAsync += (_, ea) =>
                {
                    try
                    {
                        if (JsonSerializer.Deserialize<DriversSnapshot>(ea.Body.Span, GimanoJson.Options) is { } s) store.Set(s);
                    }
                    catch (JsonException) { }
                    return Task.CompletedTask;
                };
                await ch.BasicConsumeAsync(q.QueueName, autoAck: true, consumer, stoppingToken);
                delay = TimeSpan.FromSeconds(2);
                while (!stoppingToken.IsCancellationRequested && ch.IsOpen) await Task.Delay(1000, stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { return; }
            catch (Exception ex)
            {
                logger.LogWarning("Driver snapshot consumer not connected ({Message}); retrying", ex.Message);
                await Task.Delay(delay, stoppingToken).ContinueWith(_ => { });
                delay = TimeSpan.FromSeconds(Math.Min(30, delay.TotalSeconds * 2));
            }
        }
    }
}
