using Gimano.Core.Data;
using Gimano.Core.Domain;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Core.Alerts;

/// <summary>
/// Privacy rule ("no precise per-user location history"): a road report is a precise point and time. Once it has expired
/// (plus a short grace period for events still in flight: thanks, reputation), the report keeps its public content but loses
/// its reporter, and its "still there?" votes are deleted. Points and reputation already earned stay on the user; they
/// carry no location. Issue and place votes are unlinked the same way when they close.
/// </summary>
public static class ReportUnlinking
{
    public static readonly TimeSpan Grace = TimeSpan.FromHours(1);

    /// <summary>Returns (reports unlinked, votes deleted).</summary>
    public static async Task<(int Reports, int Votes)> RunAsync(GimanoDbContext db, DateTimeOffset now, CancellationToken ct)
    {
        var cut = now - Grace;
        var done = db.Reports.Where(r => r.ExpiresAt <= cut).Select(r => r.Id);
        var votes = await db.ReportVotes.Where(v => done.Contains(v.ReportId)).ExecuteDeleteAsync(ct);
        var reports = await db.Reports.Where(r => r.ReporterId != null && r.ExpiresAt <= cut)
            .ExecuteUpdateAsync(s => s.SetProperty(r => r.ReporterId, (Guid?)null), ct);
        return (reports, votes);
    }
}
