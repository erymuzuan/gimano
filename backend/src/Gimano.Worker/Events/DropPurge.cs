using Gimano.Core.Data;
using Gimano.Core.Events;

namespace Gimano.Worker.Events;

/// <summary>Hourly: removes event short links (docs/events.md §5.6) that expired or were deleted, blob and all.</summary>
public sealed class DropPurge(IServiceScopeFactory scopes, TimeProvider clock, ILogger<DropPurge> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromHours(1));
        do
        {
            try
            {
                await using var scope = scopes.CreateAsyncScope();
                var store = new EfDropStore(scope.ServiceProvider.GetRequiredService<GimanoDbContext>());
                var n = await store.PurgeAsync(clock.GetUtcNow(), stoppingToken);
                if (n > 0) logger.LogInformation("Drop purge: removed {Count} expired or deleted event links", n);
            }
            catch (Exception ex) when (ex is not OperationCanceledException) { logger.LogWarning("Drop purge failed: {Message}", ex.Message); }
        } while (await timer.WaitForNextTickAsync(stoppingToken));
    }
}
