using System.Collections.Concurrent;
using System.Text.Json;
using Gimano.Core.Contracts;
using Gimano.Core.Geo;
using Gimano.Core.Messaging;
using Microsoft.AspNetCore.SignalR;

namespace Gimano.Api.Live;

public interface ILiveClient
{
    Task AlertCreated(ReportDto report);
    Task AlertUpdated(ReportDto report);
    Task AlertExpired(Guid reportId);
    Task JamUpdated(JamDto jam);
}

/// <summary>SignalR hub at <c>/hubs/live</c>. Anonymous access is allowed (the live map is public).</summary>
public sealed class LiveHub(LiveSubscriptions subscriptions) : Hub<ILiveClient>
{
    /// <summary>Replaces the caller's viewport subscription. <paramref name="bbox"/> = [minLng, minLat, maxLng, maxLat].</summary>
    public void Subscribe(double[] bbox)
    {
        if (!BBox.TryCreate(bbox, out var b))
            throw new HubException("bbox must be [minLng, minLat, maxLng, maxLat] in WGS84.");
        subscriptions.Set(Context.ConnectionId, b);
    }

    public override Task OnDisconnectedAsync(Exception? exception)
    {
        subscriptions.Remove(Context.ConnectionId);
        return base.OnDisconnectedAsync(exception);
    }
}

/// <summary>In-memory connection → viewport registry (per API instance).</summary>
public sealed class LiveSubscriptions
{
    private readonly ConcurrentDictionary<string, BBox> _byConnection = new();

    public void Set(string connectionId, BBox bbox) => _byConnection[connectionId] = bbox.Expand(500);
    public void Remove(string connectionId) => _byConnection.TryRemove(connectionId, out _);
    public int Count => _byConnection.Count;

    public IReadOnlyList<string> Containing(double lat, double lng) =>
        _byConnection.Where(kv => kv.Value.Contains(lat, lng)).Select(kv => kv.Key).ToList();

    public IReadOnlyList<string> Intersecting(BBox b) =>
        _byConnection.Where(kv => kv.Value.Intersects(b)).Select(kv => kv.Key).ToList();

    public IReadOnlyList<string> All() => _byConnection.Keys.ToList();
}

/// <summary>Fans RabbitMQ events out to SignalR clients whose viewport they touch. One exclusive queue per API instance.</summary>
public sealed class LiveFanoutConsumer(
    RabbitMqConnection connection, IHubContext<LiveHub, ILiveClient> hub, LiveSubscriptions subs, ILogger<LiveFanoutConsumer> logger)
    : RabbitConsumer<JsonElement>(connection, logger)
{
    protected override string? QueueName => null;

    protected override IReadOnlyList<string> RoutingKeyBindings =>
        [RoutingKeys.ReportCreated, RoutingKeys.ReportUpdated, RoutingKeys.ReportExpired, RoutingKeys.JamUpdated, RoutingKeys.JamCleared];

    protected override async Task HandleAsync(JsonElement message, string routingKey, CancellationToken ct)
    {
        if (subs.Count == 0) return;
        switch (routingKey)
        {
            case RoutingKeys.ReportCreated or RoutingKeys.ReportUpdated:
            {
                var r = message.Deserialize<ReportDto>(GimanoJson.Options)!;
                var ids = subs.Containing(r.Lat, r.Lng);
                if (ids.Count == 0) return;
                var clients = hub.Clients.Clients(ids);
                await (routingKey == RoutingKeys.ReportCreated ? clients.AlertCreated(r) : clients.AlertUpdated(r));
                break;
            }
            case RoutingKeys.ReportExpired:
            {
                var e = message.Deserialize<ReportExpiredEvent>(GimanoJson.Options)!;
                await hub.Clients.Clients(subs.All()).AlertExpired(e.ReportId);
                break;
            }
            case RoutingKeys.JamUpdated:
            {
                var j = message.Deserialize<JamDto>(GimanoJson.Options)!;
                var ids = subs.Intersecting(BBox.Of(Polyline.Decode(j.Polyline)));
                if (ids.Count > 0) await hub.Clients.Clients(ids).JamUpdated(j);
                break;
            }
            case RoutingKeys.JamCleared:
            {
                // Sent as JamUpdated with level 0 → clients remove the jam (documented in docs/api.md).
                var j = message.Deserialize<JamDto>(GimanoJson.Options)!;
                var ids = j.Polyline.Length > 0 ? subs.Intersecting(BBox.Of(Polyline.Decode(j.Polyline))) : subs.All();
                if (ids.Count > 0) await hub.Clients.Clients(ids).JamUpdated(j with { Level = 0 });
                break;
            }
        }
    }
}
