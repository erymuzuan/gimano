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

    /// <summary>When the sweep deletes a contact at the latest: min(created + 90 d, closed + 30 d). Reopening (closedAt null) restores created + 90 d.</summary>
    public static DateTimeOffset ContactExpiresAt(DateTimeOffset createdAt, DateTimeOffset? reportClosedAt) =>
        reportClosedAt is { } c && c + ContactAfterClose < createdAt + ContactMax ? c + ContactAfterClose : createdAt + ContactMax;

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

    /// <summary>
    /// Reports that carry a volunteered contact must not be linked to an account. New ones are stored without the user id;
    /// this also unlinks any older ones (stored before that rule). Returns the number of reports unlinked.
    /// </summary>
    public static async Task<int> UnlinkContactReportsAsync(GimanoDbContext db, CancellationToken ct)
    {
        var fb = db.ReportContacts.Where(c => c.SubjectType == ContactSubject.Feedback).Select(c => c.SubjectId);
        var cr = db.ReportContacts.Where(c => c.SubjectType == ContactSubject.Crash).Select(c => c.SubjectId);
        return await db.Feedback.Where(f => f.UserId != null && fb.Contains(f.Id)).ExecuteUpdateAsync(s => s.SetProperty(f => f.UserId, (Guid?)null), ct)
               + await db.CrashReports.Where(r => r.UserId != null && cr.Contains(r.Id)).ExecuteUpdateAsync(s => s.SetProperty(r => r.UserId, (Guid?)null), ct);
    }

    /// <summary>The privacy policy: feedback (message, rating, device info, coarse location, screenshot) is kept up to 24 months.</summary>
    public static readonly TimeSpan Feedback = TimeSpan.FromDays(730);

    /// <summary>Deletes feedback older than 24 months, with any contact left on it (reply drafts cascade).</summary>
    public static async Task<int> FeedbackAsync(GimanoDbContext db, DateTimeOffset now, CancellationToken ct)
    {
        var cut = now - Feedback;
        var old = db.Feedback.Where(f => f.CreatedAt <= cut).Select(f => f.Id);
        await db.ReportContacts.Where(c => c.SubjectType == ContactSubject.Feedback && old.Contains(c.SubjectId)).ExecuteDeleteAsync(ct);
        return await db.Feedback.Where(f => f.CreatedAt <= cut).ExecuteDeleteAsync(ct);
    }
}
