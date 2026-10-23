using System.Threading.Channels;
using Gimano.Core.Messaging;
using RabbitMQ.Client;

namespace Gimano.Api.Live;

/// <summary>
/// Lock-free hand-off for POST /locations: requests enqueue into a bounded in-memory channel (drop-oldest when full)
/// and return 202 immediately; one background task coalesces whatever is queued (up to 500 batches) into a single
/// transient AMQP message on the non-durable <c>gimano.locations</c> exchange. No publisher confirms, no DB.
/// If the broker is down, batches are dropped (logged at most once per 30 s).
/// </summary>
public sealed class LocationStreamPublisher : BackgroundService
{
    public const int QueueCapacity = 100_000;
    public const int MaxBatchesPerMessage = 500;

    private readonly RabbitMqConnection connection;
    private readonly TimeProvider clock;
    private readonly ILogger<LocationStreamPublisher> logger;
    private readonly Channel<LocationBatchMessage> _queue;

    public LocationStreamPublisher(RabbitMqConnection connection, TimeProvider clock, ILogger<LocationStreamPublisher> logger)
    {
        this.connection = connection;
        this.clock = clock;
        this.logger = logger;
        _queue = Channel.CreateBounded<LocationBatchMessage>(
            new BoundedChannelOptions(QueueCapacity) { FullMode = BoundedChannelFullMode.DropOldest, SingleReader = true },
            _ => Interlocked.Increment(ref _droppedFull));
    }

    private long _accepted, _published, _messages, _droppedFull, _droppedBroker;
    private DateTimeOffset _lastWarn = DateTimeOffset.MinValue;

    public LocationStreamStats Stats => new(Interlocked.Read(ref _accepted), Interlocked.Read(ref _published),
        Interlocked.Read(ref _messages), Interlocked.Read(ref _droppedFull), Interlocked.Read(ref _droppedBroker));

    /// <summary>Never blocks, never throws.</summary>
    public void Enqueue(LocationBatchMessage batch)
    {
        Interlocked.Increment(ref _accepted);
        _queue.Writer.TryWrite(batch);
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var batch = new List<LocationBatchMessage>(MaxBatchesPerMessage);
        IChannel? channel = null;
        var props = new BasicProperties { ContentType = "application/json", DeliveryMode = DeliveryModes.Transient };
        var statsAt = clock.GetUtcNow();
        var lastStats = Stats;

        while (await _queue.Reader.WaitToReadAsync(stoppingToken))
        {
            batch.Clear();
            while (batch.Count < MaxBatchesPerMessage && _queue.Reader.TryRead(out var b)) batch.Add(b);
            if (batch.Count == 0) continue;

            try
            {
                if (channel is not { IsOpen: true })
                {
                    var conn = await connection.GetAsync(stoppingToken);
                    channel = await conn.CreateChannelAsync(cancellationToken: stoppingToken);
                    await channel.ExchangeDeclareAsync(LocationStream.Exchange, ExchangeType.Topic, durable: false, autoDelete: false, cancellationToken: stoppingToken);
                }
                await channel.BasicPublishAsync(LocationStream.Exchange, LocationStream.RoutingKey, mandatory: false, props,
                    LocationWire.Encode(batch), stoppingToken);
                Interlocked.Add(ref _published, batch.Count);
                Interlocked.Increment(ref _messages);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                Interlocked.Add(ref _droppedBroker, batch.Count);
                var now = clock.GetUtcNow();
                if (now - _lastWarn > TimeSpan.FromSeconds(30))
                {
                    _lastWarn = now;
                    logger.LogWarning("Location stream unavailable ({Message}); dropping pings", ex.Message);
                }
                channel = null;
                // Don't spin on a dead broker: drain for a second, then retry.
                await Task.Delay(1000, stoppingToken);
            }

            var t = clock.GetUtcNow();
            if (t - statsAt >= TimeSpan.FromSeconds(30))
            {
                var s = Stats;
                var secs = (t - statsAt).TotalSeconds;
                logger.LogInformation("Location stream: {Rate:n0} batches/s accepted, {Pub:n0} published in {Msgs:n0} messages, dropped {Full} (queue full) {Broker} (broker)",
                    (s.Accepted - lastStats.Accepted) / secs, s.Published - lastStats.Published, s.Messages - lastStats.Messages,
                    s.DroppedFull - lastStats.DroppedFull, s.DroppedBroker - lastStats.DroppedBroker);
                statsAt = t;
                lastStats = s;
            }
        }
    }
}

public readonly record struct LocationStreamStats(long Accepted, long Published, long Messages, long DroppedFull, long DroppedBroker);
