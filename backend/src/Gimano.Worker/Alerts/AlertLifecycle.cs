using System.Text.Json;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Gimano.Core.Messaging;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Worker.Alerts;

/// <summary>
/// Consumes <c>report.created</c> / <c>report.voted</c> to maintain reporter reputation.
/// TTL changes from votes are applied synchronously by the API (it must return the updated Report).
/// </summary>
public sealed class AlertLifecycle(RabbitMqConnection connection, IServiceScopeFactory scopes, ILogger<AlertLifecycle> logger)
    : RabbitConsumer<JsonElement>(connection, logger)
{
    public const double ReputationGainPerConfirmation = 0.02;
    public const double ReputationLossPerRejection = 0.05;

    protected override string QueueName => "gimano.worker.alerts";
    protected override IReadOnlyList<string> RoutingKeyBindings => [RoutingKeys.ReportCreated, RoutingKeys.ReportVoted];

    protected override async Task HandleAsync(JsonElement message, string routingKey, CancellationToken ct)
    {
        if (routingKey == RoutingKeys.ReportCreated)
        {
            var r = message.Deserialize<ReportDto>(GimanoJson.Options)!;
            // Privacy rule: coordinates in logs are rounded to 2 dp at most.
            logger.LogInformation("Report {Id} {Type}/{Subtype} near {Lat:F2},{Lng:F2} expires {Expires:t}", r.Id, r.Type, r.Subtype, r.Lat, r.Lng, r.ExpiresAt);
            return;
        }

        var vote = message.Deserialize<ReportVotedEvent>(GimanoJson.Options)!;
        await using var scope = scopes.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<GimanoDbContext>();
        var report = await db.Reports.Include(r => r.Reporter).FirstOrDefaultAsync(r => r.Id == vote.ReportId, ct);
        if (report?.Reporter is null || report.ReporterId == vote.UserId) return;

        report.Reporter.Reputation = Math.Clamp(report.Reporter.Reputation +
            (vote.StillThere ? ReputationGainPerConfirmation : -ReputationLossPerRejection), 0.5, 2.0);
        await db.SaveChangesAsync(ct);
    }
}

/// <summary>Every 30 s: marks reports past their TTL as expired and publishes <c>report.expired</c>.</summary>
public sealed class AlertExpirySweeper(IServiceScopeFactory scopes, IEventPublisher bus, TimeProvider clock, ILogger<AlertExpirySweeper> logger)
    : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(30));
        do
        {
            try
            {
                await using var scope = scopes.CreateAsyncScope();
                var db = scope.ServiceProvider.GetRequiredService<GimanoDbContext>();
                var now = clock.GetUtcNow();
                var due = await db.Reports.Where(r => r.Status == ReportStatus.Active && r.ExpiresAt <= now)
                    .OrderBy(r => r.ExpiresAt).Select(r => r.Id).Take(1000).ToListAsync(stoppingToken);
                if (due.Count == 0) continue;
                await db.Reports.Where(r => due.Contains(r.Id))
                    .ExecuteUpdateAsync(s => s.SetProperty(r => r.Status, ReportStatus.Expired), stoppingToken);
                foreach (var id in due) await bus.PublishAsync(RoutingKeys.ReportExpired, new ReportExpiredEvent(id), stoppingToken);
                logger.LogInformation("Expired {Count} reports", due.Count);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                logger.LogError(ex, "Expiry sweep failed");
            }
        } while (await timer.WaitForNextTickAsync(stoppingToken));
    }
}
