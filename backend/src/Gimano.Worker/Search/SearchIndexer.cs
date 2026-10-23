using Gimano.Core.Data;
using Gimano.Core.Messaging;
using Gimano.Core.Search;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Worker.Search;

/// <summary>Consumes <c>place.changed</c>: re-indexes (or deletes) the place in OpenSearch.</summary>
public sealed class SearchIndexer(RabbitMqConnection connection, IServiceScopeFactory scopes, PlaceSearchIndex index, ILogger<SearchIndexer> logger)
    : RabbitConsumer<PlaceChangedEvent>(connection, logger)
{
    private bool _indexReady;

    protected override string QueueName => "gimano.worker.search";
    protected override IReadOnlyList<string> RoutingKeyBindings => [RoutingKeys.PlaceChanged];

    protected override async Task HandleAsync(PlaceChangedEvent message, string routingKey, CancellationToken ct)
    {
        if (!_indexReady)
        {
            await index.EnsureIndexAsync(ct: ct);
            _indexReady = true;
        }
        await using var scope = scopes.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<GimanoDbContext>();
        var place = await db.Places.AsNoTracking().FirstOrDefaultAsync(p => p.Id == message.PlaceId, ct);
        if (place is null) await index.DeleteAsync(message.PlaceId, ct);
        else await index.BulkIndexAsync([place], ct);
    }
}
