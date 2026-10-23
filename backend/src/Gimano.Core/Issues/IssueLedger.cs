using System.Security.Cryptography;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Gimano.Core.Identity;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Core.Issues;

/// <summary>Database side of the issue state machine, shared by the API (after reports/votes) and the worker (every 15 min).</summary>
public static class IssueLedger
{
    public static DateTimeOffset HourOf(DateTimeOffset t)
    {
        var u = t.ToUniversalTime();
        return new DateTimeOffset(u.Year, u.Month, u.Day, u.Hour, 0, 0, TimeSpan.Zero);
    }

    /// <summary>
    /// Passive evidence from anonymous hourly counts. "Since" counts only full hours after the report (an accident's
    /// traffic just before it was reported must not refute it); counts with unknown direction match any direction.
    /// </summary>
    public static async Task<IssueEvidence> EvidenceAsync(GimanoDbContext db, Issue i, DateTimeOffset now, CancellationToken ct)
    {
        if (i.WayId is not { } way || i.Seq is not { } seq) return IssueEvidence.None;
        var rows = db.SegmentTraversalCounts.AsNoTracking().Where(c => c.WayId == way && c.Seq == seq);
        if (i.Direction != IssueDirection.Both)
            rows = rows.Where(c => c.Direction == i.Direction || c.Direction == IssueDirection.Both);
        var since = HourOf(i.CreatedAt).AddHours(1);
        var from14 = now.AddDays(-14);
        var baseFrom = i.CreatedAt.AddDays(-7);
        var list = await rows.Where(c => c.Hour >= baseFrom || c.Hour >= from14).Select(c => new { c.Hour, c.Count }).ToListAsync(ct);
        return new IssueEvidence(
            list.Where(c => c.Hour >= since).Sum(c => c.Count),
            list.Where(c => c.Hour >= from14).Sum(c => c.Count),
            list.Where(c => c.Hour >= baseFrom && c.Hour < HourOf(i.CreatedAt)).Sum(c => c.Count) / 7.0);
    }

    public static async Task<byte[]> SaltAsync(GimanoDbContext db, Guid issueId, CancellationToken ct)
    {
        var s = await db.IssueSalts.FindAsync([issueId], ct);
        if (s is null) db.IssueSalts.Add(s = new IssueSalt { IssueId = issueId, Salt = RandomNumberGenerator.GetBytes(32) });
        return s.Salt;
    }

    /// <summary>
    /// Moves the issue to <paramref name="next"/> and settles reputation: reporter accuracy (confirmed → accepted; refuted by
    /// votes before ever being confirmed → rejected), voter accuracy (each vote scored once), and on closing, unlinks every vote
    /// (VoterId → HMAC(per-issue salt), time → day; salt deleted 30 days later). Caller saves and publishes.
    /// </summary>
    public static async Task<bool> MoveAsync(GimanoDbContext db, Issue issue, IssueState next, string reason, DateTimeOffset now, CancellationToken ct)
    {
        var wasConfirmed = issue.ConfirmedAt is not null;
        if (!IssueRules.Transition(issue, next, now, reason)) return false;

        var touched = new Dictionary<Guid, User>();
        async Task<User?> UserAsync(Guid id)
        {
            if (touched.TryGetValue(id, out var u)) return u;
            u = await db.Users.FirstOrDefaultAsync(x => x.Id == id, ct);
            if (u is not null) touched[id] = u;
            return u;
        }

        if (issue.CreatedBy is { } reporterId && await UserAsync(reporterId) is { } reporter)
        {
            if (next == IssueState.Confirmed && !wasConfirmed) reporter.PlacesAccepted++;
            if (next == IssueState.Refuted && !wasConfirmed && reason.StartsWith("votes", StringComparison.Ordinal)) reporter.PlacesRejected++;
        }

        var votes = await db.IssueVotes.Where(v => v.IssueId == issue.Id && v.VoterId != null).ToListAsync(ct);
        foreach (var v in votes.Where(v => !v.Scored && !v.IsReport))
        {
            if (IssueRules.Agreed(v.Answer, next) is not { } agreed) continue;
            if (await UserAsync(v.VoterId!.Value) is { } voter)
            {
                voter.VerifierVotes++;
                if (agreed) voter.VerifierAgreed++;
            }
            v.Scored = true;
        }

        if (!IssueRules.IsOpen(next))
        {
            var salt = await SaltAsync(db, issue.Id, ct);
            foreach (var v in votes)
            {
                v.VoterHash = Reputation.VoterHash(salt, v.VoterId!.Value);
                v.VoterId = null;
                v.Day = DateOnly.FromDateTime((v.CreatedAt ?? now).UtcDateTime);
                v.CreatedAt = null;
            }
            if (await db.IssueSalts.FindAsync([issue.Id], ct) is { } s) s.ResolvedAt = now;
        }
        await db.SaveChangesAsync(ct);
        foreach (var u in touched.Values) await Ledger.RecomputeAsync(db, u, now, ct);
        return true;
    }

    /// <summary>Runs the rules with fresh evidence and applies any transition; returns the new state if it changed.</summary>
    public static async Task<IssueState?> EvaluateAsync(GimanoDbContext db, Issue issue, DateTimeOffset now, CancellationToken ct, IssueEvidence? evidence = null)
    {
        var e = evidence ?? await EvidenceAsync(db, issue, now, ct);
        var next = IssueRules.Next(issue, e, now);
        if (next == issue.State) return null;
        var reason = next switch
        {
            IssueState.Expired => "expired",
            IssueState.Refuted when IssueRules.PassiveRefutes(issue, e) => $"traffic {e.TraversalsSince}",
            IssueState.Refuted => $"votes cleared {issue.WeightedCleared:0.#}",
            IssueState.Confirmed => IssueRules.PassiveSupports(issue, e, now) ? $"votes {issue.WeightedStill:0.#} + no traffic" : $"votes {issue.WeightedStill:0.#}",
            _ => next.ToString(),
        };
        return await MoveAsync(db, issue, next, reason, now, ct) ? next : null;
    }
}
