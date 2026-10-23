using Gimano.Core.Data;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Core.BackOffice;

/// <summary>
/// Back-office housekeeping, run by the worker's hourly retention job next to ReportRetention (which already deletes
/// contacts, crash reports and groups): dead agent tokens after 30 days, audit rows after a year.
/// </summary>
public static class BackOfficeRetention
{
    public static readonly TimeSpan DeadTokens = TimeSpan.FromDays(30);
    public static readonly TimeSpan Audit = TimeSpan.FromDays(365);

    public static async Task<(int Tokens, int Audit)> RunAsync(GimanoDbContext db, DateTimeOffset now, CancellationToken ct)
    {
        var tokenCut = now - DeadTokens;
        var auditCut = now - Audit;
        var tokens = await db.AgentTokens.Where(t => (t.RevokedAt ?? t.ExpiresAt) <= tokenCut).ExecuteDeleteAsync(ct);
        var audit = await db.AuditLog.Where(a => a.At <= auditCut).ExecuteDeleteAsync(ct);
        return (tokens, audit);
    }
}
