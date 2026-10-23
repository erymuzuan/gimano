using Gimano.Core.Data;
using Gimano.Core.Support;

namespace Gimano.Worker.Support;

/// <summary>Hourly: contact details (30 days after close, 90 days max), crash reports (90 days), search-miss counts (&lt; 5 after the week; 26 weeks).</summary>
public sealed class ReportRetentionJob(IServiceScopeFactory scopes, TimeProvider clock, ILogger<ReportRetentionJob> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromHours(1));
        do
        {
            try
            {
                await using var scope = scopes.CreateAsyncScope();
                var db = scope.ServiceProvider.GetRequiredService<GimanoDbContext>();
                var (contacts, crashes, groups) = await ReportRetention.RunAsync(db, clock.GetUtcNow(), stoppingToken);
                var misses = await SearchMisses.RetentionAsync(db, clock.GetUtcNow(), stoppingToken);
                if (misses > 0) logger.LogInformation("Retention: dropped {Count} search-miss rows", misses);
                if (contacts + crashes + groups > 0)
                    logger.LogInformation("Retention: deleted {Contacts} contacts, {Crashes} crash reports, {Groups} crash groups", contacts, crashes, groups);
            }
            catch (Exception ex) when (ex is not OperationCanceledException) { logger.LogWarning("Report retention failed: {Message}", ex.Message); }
        } while (await timer.WaitForNextTickAsync(stoppingToken));
    }
}
