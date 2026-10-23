using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Core.Support;

/// <summary>Retention for volunteered contacts and crash reports (docs/api.md "Contact details", "Crash reports").</summary>
public static class ReportRetention
{
    public static readonly TimeSpan ContactMax = TimeSpan.FromDays(90);
    public static readonly TimeSpan ContactAfterClose = TimeSpan.FromDays(30);
    public static readonly TimeSpan CrashReports = TimeSpan.FromDays(90);

    /// <summary>Pure rule: should this contact be deleted now?</summary>
    public static bool ContactExpired(DateTimeOffset createdAt, DateTimeOffset? reportClosedAt, DateTimeOffset now) =>
        now - createdAt >= ContactMax || (reportClosedAt is { } c && now - c >= ContactAfterClose);

    public static async Task<(int Contacts, int Crashes, int Groups)> RunAsync(GimanoDbContext db, DateTimeOffset now, CancellationToken ct)
    {
        var maxCut = now - ContactMax;
        var closeCut = now - ContactAfterClose;
        var closedFeedback = db.Feedback.Where(f => f.ClosedAt != null && f.ClosedAt <= closeCut).Select(f => f.Id);
        var closedCrashes = db.CrashReports.Where(r => db.CrashGroups.Any(g => g.Signature == r.Signature && g.ClosedAt != null && g.ClosedAt <= closeCut)).Select(r => r.Id);
        var contacts = await db.ReportContacts.Where(c => c.CreatedAt <= maxCut
                || (c.SubjectType == ContactSubject.Feedback && closedFeedback.Contains(c.SubjectId))
                || (c.SubjectType == ContactSubject.Crash && closedCrashes.Contains(c.SubjectId)))
            .ExecuteDeleteAsync(ct);

        var crashCut = now - CrashReports;
        var oldCrashIds = db.CrashReports.Where(r => r.CreatedAt <= crashCut).Select(r => r.Id);
        contacts += await db.ReportContacts.Where(c => c.SubjectType == ContactSubject.Crash && oldCrashIds.Contains(c.SubjectId)).ExecuteDeleteAsync(ct);
        var crashes = await db.CrashReports.Where(r => r.CreatedAt <= crashCut).ExecuteDeleteAsync(ct);
        var groups = await db.CrashGroups.Where(g => g.LastSeen <= crashCut && !db.CrashReports.Any(r => r.Signature == g.Signature)).ExecuteDeleteAsync(ct);
        return (contacts, crashes, groups);
    }
}
