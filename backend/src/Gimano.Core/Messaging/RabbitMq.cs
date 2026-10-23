using System.Text.Json;
using Gimano.Core.Contracts;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using RabbitMQ.Client;
using RabbitMQ.Client.Events;

namespace Gimano.Core.Messaging;

public sealed class RabbitMqOptions
{
    public const string Section = "RabbitMq";
    public string Host { get; set; } = "localhost";
    public int Port { get; set; } = 5672;
    public string User { get; set; } = "guest";
    public string Password { get; set; } = "";
    public string VirtualHost { get; set; } = "/";
}

/// <summary>Owns an AMQP connection (lazy, auto-recovering). The durable event bus uses this one.</summary>
public class RabbitMqConnection(IOptions<RabbitMqOptions> options, ILogger<RabbitMqConnection> logger) : IAsyncDisposable
{
    private readonly SemaphoreSlim _gate = new(1, 1);
    private IConnection? _connection;

    public bool IsOpen => _connection?.IsOpen ?? false;

    protected virtual string NameSuffix => "";

    public async Task<IConnection> GetAsync(CancellationToken ct = default)
    {
        if (_connection is { IsOpen: true }) return _connection;
        await _gate.WaitAsync(ct);
        try
        {
            if (_connection is { IsOpen: true }) return _connection;
            if (_connection is not null) await _connection.DisposeAsync();
            var o = options.Value;
            var factory = new ConnectionFactory
            {
                HostName = o.Host, Port = o.Port, UserName = o.User, Password = o.Password, VirtualHost = o.VirtualHost,
                AutomaticRecoveryEnabled = true, TopologyRecoveryEnabled = true,
                ClientProvidedName = $"gimano-{AppDomain.CurrentDomain.FriendlyName}{NameSuffix}",
                RequestedConnectionTimeout = TimeSpan.FromSeconds(5),
            };
            _connection = await factory.CreateConnectionAsync(ct);
            await using var ch = await _connection.CreateChannelAsync(cancellationToken: ct);
            await ch.ExchangeDeclareAsync(RoutingKeys.Exchange, ExchangeType.Topic, durable: true, cancellationToken: ct);
            logger.LogInformation("Connected to RabbitMQ {Host}:{Port}", o.Host, o.Port);
            return _connection;
        }
        finally
        {
            _gate.Release();
        }
    }

    public async ValueTask DisposeAsync()
    {
        if (_connection is not null) await _connection.DisposeAsync();
    }
}

/// <summary>
/// A second connection for the high-volume transient location stream, so its traffic (and any channel/connection
/// error on it) never affects the durable event bus.
/// </summary>
public sealed class LocationStreamConnection(IOptions<RabbitMqOptions> options, ILogger<RabbitMqConnection> logger)
    : RabbitMqConnection(options, logger)
{
    protected override string NameSuffix => "-locations";
}

public interface IEventPublisher
{
    /// <summary>Publishes to <c>gimano.events</c>. Never throws: returns false (and logs) if the broker is unavailable.</summary>
    Task<bool> PublishAsync<T>(string routingKey, T payload, CancellationToken ct = default);
}

public sealed class RabbitEventPublisher(RabbitMqConnection connection, ILogger<RabbitEventPublisher> logger) : IEventPublisher, IAsyncDisposable
{
    private readonly SemaphoreSlim _gate = new(1, 1);
    private IChannel? _channel;
    private DateTimeOffset _retryAfter = DateTimeOffset.MinValue;

    public async Task<bool> PublishAsync<T>(string routingKey, T payload, CancellationToken ct = default)
    {
        if (DateTimeOffset.UtcNow < _retryAfter) return false;
        var body = JsonSerializer.SerializeToUtf8Bytes(payload, GimanoJson.Options);
        await _gate.WaitAsync(ct);
        try
        {
            if (_channel is not { IsOpen: true })
            {
                var conn = await connection.GetAsync(ct);
                _channel = await conn.CreateChannelAsync(cancellationToken: ct);
            }
            var props = new BasicProperties
            {
                ContentType = "application/json",
                DeliveryMode = DeliveryModes.Persistent,
                Timestamp = new AmqpTimestamp(DateTimeOffset.UtcNow.ToUnixTimeSeconds()),
                Type = typeof(T).Name,
            };
            await _channel.BasicPublishAsync(RoutingKeys.Exchange, routingKey, mandatory: false, props, body, ct);
            return true;
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            // Back off for a few seconds so an absent broker doesn't add latency to every request.
            _retryAfter = DateTimeOffset.UtcNow.AddSeconds(10);
            logger.LogWarning(ex, "Publishing {RoutingKey} failed; RabbitMQ unavailable", routingKey);
            return false;
        }
        finally
        {
            _gate.Release();
        }
    }

    public async ValueTask DisposeAsync()
    {
        if (_channel is not null) await _channel.DisposeAsync();
    }
}

/// <summary>Base class for a queue consumer bound to <c>gimano.events</c>. Reconnects forever.</summary>
public abstract class RabbitConsumer<T>(RabbitMqConnection connection, ILogger logger) : BackgroundService
{
    /// <summary>Durable queue name; null → server-named exclusive, auto-delete queue (fan-out per instance).</summary>
    protected abstract string? QueueName { get; }
    protected abstract IReadOnlyList<string> RoutingKeyBindings { get; }
    protected virtual ushort Prefetch => 50;

    protected abstract Task HandleAsync(T message, string routingKey, CancellationToken ct);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var delay = TimeSpan.FromSeconds(2);
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                var conn = await connection.GetAsync(stoppingToken);
                await using var channel = await conn.CreateChannelAsync(cancellationToken: stoppingToken);
                await channel.BasicQosAsync(0, Prefetch, false, stoppingToken);
                var q = QueueName is null
                    ? await channel.QueueDeclareAsync("", durable: false, exclusive: true, autoDelete: true, cancellationToken: stoppingToken)
                    : await channel.QueueDeclareAsync(QueueName, durable: true, exclusive: false, autoDelete: false, cancellationToken: stoppingToken);
                foreach (var key in RoutingKeyBindings)
                    await channel.QueueBindAsync(q.QueueName, RoutingKeys.Exchange, key, cancellationToken: stoppingToken);

                var consumer = new AsyncEventingBasicConsumer(channel);
                consumer.ReceivedAsync += async (_, ea) =>
                {
                    try
                    {
                        var msg = JsonSerializer.Deserialize<T>(ea.Body.Span, GimanoJson.Options);
                        if (msg is not null) await HandleAsync(msg, ea.RoutingKey, stoppingToken);
                        await channel.BasicAckAsync(ea.DeliveryTag, false, stoppingToken);
                    }
                    catch (JsonException ex)
                    {
                        logger.LogError(ex, "Dropping malformed {RoutingKey} message", ea.RoutingKey);
                        await channel.BasicNackAsync(ea.DeliveryTag, false, requeue: false, stoppingToken);
                    }
                    catch (Exception ex) when (ex is not OperationCanceledException)
                    {
                        logger.LogError(ex, "Handling {RoutingKey} failed", ea.RoutingKey);
                        await channel.BasicNackAsync(ea.DeliveryTag, false, requeue: !ea.Redelivered, stoppingToken);
                    }
                };
                await channel.BasicConsumeAsync(q.QueueName, autoAck: false, consumer, stoppingToken);
                logger.LogInformation("Consuming {Queue} ({Keys})", q.QueueName, string.Join(", ", RoutingKeyBindings));
                delay = TimeSpan.FromSeconds(2);

                while (!stoppingToken.IsCancellationRequested && channel.IsOpen)
                    await Task.Delay(TimeSpan.FromSeconds(2), stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception ex)
            {
                logger.LogWarning("RabbitMQ consumer {Type} not connected ({Message}); retrying in {Delay}s", GetType().Name, ex.Message, delay.TotalSeconds);
                await Task.Delay(delay, stoppingToken).ContinueWith(_ => { });
                delay = TimeSpan.FromSeconds(Math.Min(30, delay.TotalSeconds * 2));
            }
        }
    }
}
