using Gimano.Core.BackOffice;
using Gimano.Core.Data;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Worker.Feedback;

/// <summary>
/// Hourly: enforces the retention promises in docs/api.md. Contact details past their expiry (30 days after the report
/// closes, 90 days max), crash events after 90 days, Inbox items after 90 days, dead agent tokens after 30 days,
/// audit rows after a year.
/// </summary>
public sealed class BackOfficeRetention(IServiceScopeFactory scopes, TimeProvider clock, ILogger<BackOfficeRetention> logger) : BackgroundService
{
    public static readonly TimeSpan CrashEvents = TimeSpan.FromDays(90);
    public static readonly TimeSpan InboxItems = TimeSpan.FromDays(90);
    public static readonly TimeSpan DeadTokens = TimeSpan.FromDays(30);
    public static readonly TimeSpan Audit = TimeSpan.FromDays(365);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromHours(1));
        do
        {
            try
            {
                await using var scope = scopes.CreateAsyncScope();
                var db = scope.ServiceProvider.GetRequiredService<GimanoDbContext>();
                var now = clock.GetUtcNow();
                var contacts = await db.ReportContacts.Where(c => c.ExpiresAt <= now).ExecuteDeleteAsync(stoppingToken);
                var events = await db.CrashEvents.Where(e => e.ReceivedAt <= now - CrashEvents).ExecuteDeleteAsync(stoppingToken);
                var inbox = await db.InboxItems.Where(i => i.CreatedAt <= now - InboxItems).ExecuteDeleteAsync(stoppingToken);
                var tokens = await db.AgentTokens.Where(t => (t.RevokedAt ?? t.ExpiresAt) <= now - DeadTokens).ExecuteDeleteAsync(stoppingToken);
                var audit = await db.AuditLog.Where(a => a.At <= now - Audit).ExecuteDeleteAsync(stoppingToken);
                if (contacts + events + inbox + tokens + audit > 0)
                    logger.LogInformation("Retention: deleted {Contacts} contacts, {Events} crash events, {Inbox} inbox items, {Tokens} tokens, {Audit} audit rows",
                        contacts, events, inbox, tokens, audit);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                logger.LogWarning(ex, "Back-office retention sweep failed");
            }
        } while (await timer.WaitForNextTickAsync(stoppingToken));
    }
}
