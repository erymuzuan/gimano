using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Gimano.Core.Support;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Core.BackOffice;

/// <summary>What a newly received crash report does to its group (docs/back-office.md "Crash groups").</summary>
public static class CrashLifecycle
{
    /// <summary>A group marked done in build n regresses when a crash arrives from build n or later.</summary>
    public static bool IsRegression(CrashGroupStatus status, int? fixedInBuild, int? eventBuild) =>
        status == CrashGroupStatus.Done && fixedInBuild is { } fixedIn && eventBuild is { } b && b >= fixedIn;

    /// <summary>
    /// Counts the report, keeps the triage summary fields current and reopens the group when needed:
    /// done-with-a-fix-build regresses only for crashes from that build or later (older builds are still in the wild);
    /// any other closed group reopens as open. Reopening clears ClosedAt, so the contact-retention clock goes back to
    /// "90 days after the report" (ReportRetention).
    /// </summary>
    public static void OnReport(CrashGroup g, CrashReport r, DateTimeOffset now)
    {
        g.Count++;
        g.LastSeen = now;
        if (r.Message is { Length: > 0 } m) g.Message = Limit(m, 500);
        g.Platform ??= Limit(r.Device?.Platform?.Trim().ToLowerInvariant(), 10);
        g.Kind ??= r.Kind;
        g.TopFrame ??= CrashScrubber.TopFrame(r.Stack);
        g.FirstVersion ??= Limit(r.Device?.AppVersion, 30);
        var build = r.Device?.AppBuild;
        if (build is { } b && (g.LatestBuild is null || b >= g.LatestBuild))
        {
            g.LatestBuild = b;
            g.LatestVersion = Limit(r.Device!.AppVersion, 30);
        }
        else if (g.LatestBuild is null && r.Device?.AppVersion is { } v) g.LatestVersion = Limit(v, 30);

        if (g.Status == CrashGroupStatus.Done && g.FixedInBuild is not null)
        {
            if (IsRegression(g.Status, g.FixedInBuild, build)) { g.Status = CrashGroupStatus.Regressed; g.ClosedAt = null; }
        }
        else if (CrashGroup.IsClosed(g.Status)) { g.Status = CrashGroupStatus.Open; g.ClosedAt = null; }
    }

    /// <summary>
    /// <see cref="OnReport"/> as one atomic UPDATE of an existing group (concurrent reports never lose a count or a
    /// status change). Every SET reads the row's old values, as OnReport does. Returns 0 when the group does not exist.
    /// </summary>
    public static Task<int> BumpAsync(GimanoDbContext db, CrashReport r, DateTimeOffset now, CancellationToken ct)
    {
        var build = r.Device?.AppBuild;
        var version = Limit(r.Device?.AppVersion, 30);
        var platform = Limit(r.Device?.Platform?.Trim().ToLowerInvariant(), 10);
        var message = r.Message is { Length: > 0 } m ? Limit(m, 500) : null;
        var topFrame = CrashScrubber.TopFrame(r.Stack);
        CrashKind? kind = r.Kind;
        const CrashGroupStatus done = CrashGroupStatus.Done, wontFix = CrashGroupStatus.WontFix;
        return db.CrashGroups.Where(g => g.Signature == r.Signature).ExecuteUpdateAsync(s => s
            .SetProperty(g => g.Count, g => g.Count + 1)
            .SetProperty(g => g.LastSeen, now)
            .SetProperty(g => g.Message, g => message ?? g.Message)
            .SetProperty(g => g.Platform, g => g.Platform ?? platform)
            .SetProperty(g => g.Kind, g => g.Kind ?? kind)
            .SetProperty(g => g.TopFrame, g => g.TopFrame ?? topFrame)
            .SetProperty(g => g.FirstVersion, g => g.FirstVersion ?? version)
            .SetProperty(g => g.LatestVersion, g => build != null && (g.LatestBuild == null || build >= g.LatestBuild) ? version
                : g.LatestBuild == null && version != null ? version : g.LatestVersion)
            .SetProperty(g => g.LatestBuild, g => build != null && (g.LatestBuild == null || build >= g.LatestBuild) ? build : g.LatestBuild)
            .SetProperty(g => g.Status, g => g.Status == done && g.FixedInBuild != null
                ? (build != null && build >= g.FixedInBuild ? CrashGroupStatus.Regressed : done)
                : (g.Status == done || g.Status == wontFix ? CrashGroupStatus.Open : g.Status))
            .SetProperty(g => g.ClosedAt, g => g.Status == done && g.FixedInBuild != null
                ? (build != null && build >= g.FixedInBuild ? null : g.ClosedAt)
                : (g.Status == done || g.Status == wontFix ? null : g.ClosedAt)), ct);
    }

    private static string? Limit(string? s, int max) => s is null ? null : s.Length <= max ? s : s[..max];
}
