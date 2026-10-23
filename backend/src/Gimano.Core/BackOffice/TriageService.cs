using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Core.BackOffice;

/// <summary>
/// The one place back-office data is read and changed: by maintainers (Blazor), the admin API and the MCP tools.
/// Every write is audited. Agents get the same views as maintainers minus unsealed contacts and unshared screenshots.
/// </summary>
public sealed class TriageService(GimanoDbContext db, TimeProvider clock, ContactProtector? contacts = null)
{
    public const int MaxTake = 200;
    private static readonly FeedbackStatus[] ClosedFeedback = [FeedbackStatus.Done, FeedbackStatus.WontFix];
    private static readonly CrashStatus[] ClosedCrash = [CrashStatus.Fixed, CrashStatus.Ignored];

    // ---- Overview ---------------------------------------------------------------------------------------------

    public async Task<BackOfficeStats> StatsAsync(CancellationToken ct = default)
    {
        var since = clock.GetUtcNow().AddHours(-24);
        var openFeedback = await db.Feedback.CountAsync(f => !ClosedFeedback.Contains(f.Status), ct);
        var openCrashes = await db.CrashGroups.CountAsync(g => !ClosedCrash.Contains(g.Status), ct);
        var newFeedback = await db.Feedback.CountAsync(f => f.Status == FeedbackStatus.Received && f.CreatedAt > since, ct);
        var newCrashes = await db.CrashGroups.CountAsync(g => g.Status == CrashStatus.New && g.FirstSeenAt > since, ct);
        var regressions = await db.CrashGroups.CountAsync(g => g.Status == CrashStatus.Regressed, ct);
        var runs = await db.AgentRuns.CountAsync(r => r.Status == AgentRunStatus.WaitingForReview, ct);
        var replies = await db.ReplyDrafts.CountAsync(r => r.Status == ReplyStatus.Draft, ct);
        return new(openFeedback, openCrashes, newFeedback + newCrashes, regressions, runs, replies);
    }

    /// <summary>Open feedback and crash groups in one list: regressions first, then by last activity.</summary>
    public async Task<IReadOnlyList<QueueRow>> QueueAsync(string? type = null, int take = 100, CancellationToken ct = default)
    {
        take = Math.Clamp(take, 1, MaxTake);
        var rows = new List<QueueRow>();
        if (type is null or "crash")
        {
            var groups = await db.CrashGroups.AsNoTracking().Where(g => !ClosedCrash.Contains(g.Status))
                .OrderByDescending(g => g.LastSeenAt).Take(take).ToListAsync(ct);
            rows.AddRange(groups.Select(g => new QueueRow("crash", g.Signature, $"{ShortException(g.Exception)}{(g.Message is null ? "" : ": " + g.Message)}",
                g.TopFrame ?? g.Exception, GimanoJson.ToWire(g.Kind), g.EventCount, VersionRange(g.FirstVersion, g.LatestVersion),
                GimanoJson.ToWire(g.Status), g.Severity, false, false, g.LastSeenAt)));
        }
        if (type is null or "feedback")
        {
            var items = await db.Feedback.AsNoTracking().Where(f => !ClosedFeedback.Contains(f.Status))
                .OrderByDescending(f => f.CreatedAt).Take(take)
                .Select(f => new
                {
                    f.Id, f.Category, f.Message, f.Screen, f.Status, f.Severity, f.CreatedAt, AppVersion = f.Context!.AppVersion,
                    Platform = f.Context!.Platform, HasScreenshot = f.Screenshot != null,
                    HasContact = db.ReportContacts.Any(c => c.FeedbackId == f.Id),
                }).ToListAsync(ct);
            rows.AddRange(items.Select(f => new QueueRow("feedback", f.Id.ToString(), FirstLine(f.Message, 120),
                $"{GimanoJson.ToWire(f.Category)} · {(f.Screen is { } s ? GimanoJson.ToWire(s) : "no screen")}",
                GimanoJson.ToWire(f.Category), 1, f.AppVersion is null ? null : $"{f.Platform} {f.AppVersion}".Trim(),
                GimanoJson.ToWire(f.Status), f.Severity, f.HasContact, f.HasScreenshot, f.CreatedAt)));
        }
        return rows.OrderByDescending(r => r.Status == "regressed").ThenBy(r => r.Severity ?? Severity.S4)
            .ThenByDescending(r => r.LastSeenAt).Take(take).ToList();
    }

    // ---- Feedback ---------------------------------------------------------------------------------------------

    public async Task<IReadOnlyList<FeedbackListItem>> SearchFeedbackAsync(FeedbackQuery q, CancellationToken ct = default)
    {
        var query = db.Feedback.AsNoTracking();
        if (q.Status is { } st) query = query.Where(f => f.Status == st);
        if (q.OpenOnly) query = query.Where(f => !ClosedFeedback.Contains(f.Status));
        if (q.Category is { } cat) query = query.Where(f => f.Category == cat);
        if (q.Screen is { } scr) query = query.Where(f => f.Screen == scr);
        if (q.Since is { } since) query = query.Where(f => f.CreatedAt >= since);
        if (!string.IsNullOrWhiteSpace(q.Platform)) query = query.Where(f => f.Context!.Platform == q.Platform);
        if (!string.IsNullOrWhiteSpace(q.Text))
        {
            var pattern = "%" + EscapeLike(q.Text.Trim()) + "%";
            query = query.Where(f => EF.Functions.ILike(f.Message, pattern));
        }
        return await query.OrderByDescending(f => f.CreatedAt).Take(Math.Clamp(q.Take, 1, MaxTake))
            .Select(f => new FeedbackListItem(f.Id, f.Category, f.Message, f.Rating, f.Screen, f.Status, f.Severity,
                f.Context!.AppVersion, f.Context!.Platform, f.Screenshot != null, db.ReportContacts.Any(c => c.FeedbackId == f.Id), f.CreatedAt))
            .ToListAsync(ct);
    }

    public async Task<FeedbackView?> GetFeedbackAsync(Guid id, CancellationToken ct = default)
    {
        var f = await db.Feedback.AsNoTracking().Where(x => x.Id == id).Select(x => new
        {
            x.Id, x.Category, x.Message, x.Rating, x.Screen, x.Status, x.Severity, x.Device, x.Context, HasScreenshot = x.Screenshot != null,
            x.ScreenshotSharedWithAgents, x.DuplicateOfId, x.CrashSignature, x.TriageNote, x.ExternalUrl, x.CreatedAt, x.UpdatedAt, x.ClosedAt,
        }).FirstOrDefaultAsync(ct);
        if (f is null) return null;
        var contact = await db.ReportContacts.AsNoTracking().Where(c => c.FeedbackId == id)
            .Select(c => new ContactFlags(c.HasEmail, c.HasWhatsapp, c.ExpiresAt)).FirstOrDefaultAsync(ct);
        return new(f.Id, f.Category, f.Message, f.Rating, f.Screen, f.Status, f.Severity, f.Device, f.Context, f.HasScreenshot,
            f.ScreenshotSharedWithAgents, contact, f.DuplicateOfId, f.CrashSignature, f.TriageNote, f.ExternalUrl, f.CreatedAt, f.UpdatedAt, f.ClosedAt);
    }

    /// <summary>Maintainers always; agents only when a maintainer shared that one screenshot.</summary>
    public async Task<(byte[] Bytes, string ContentType)?> GetScreenshotAsync(Guid id, Actor actor, CancellationToken ct = default)
    {
        actor.Require(AgentScopes.Read);
        var s = await db.Feedback.AsNoTracking().Where(f => f.Id == id && f.Screenshot != null)
            .Select(f => new { f.Screenshot, f.ScreenshotContentType, f.ScreenshotSharedWithAgents }).FirstOrDefaultAsync(ct);
        if (s is null) return null;
        if (actor.IsAgent)
        {
            if (!s.ScreenshotSharedWithAgents) throw new ScopeException("This screenshot has not been shared with agents.");
            await AuditAsync(actor, "feedback.screenshot", "feedback", id.ToString(), null, ct);
        }
        return (s.Screenshot!, s.ScreenshotContentType ?? "image/png");
    }

    public async Task ShareScreenshotWithAgentsAsync(Guid id, bool shared, Actor actor, CancellationToken ct = default)
    {
        actor.RequireHuman("share a screenshot with agents");
        var n = await db.Feedback.Where(f => f.Id == id).ExecuteUpdateAsync(s => s.SetProperty(f => f.ScreenshotSharedWithAgents, shared), ct);
        if (n == 0) throw new KeyNotFoundException("Feedback not found.");
        await AuditAsync(actor, shared ? "feedback.screenshot.share" : "feedback.screenshot.unshare", "feedback", id.ToString(), null, ct);
    }

    public async Task<FeedbackView> TriageFeedbackAsync(Guid id, FeedbackTriage t, Actor actor, CancellationToken ct = default)
    {
        actor.Require(AgentScopes.TriageWrite);
        var f = await db.Feedback.FirstOrDefaultAsync(x => x.Id == id, ct) ?? throw new KeyNotFoundException("Feedback not found.");
        var now = clock.GetUtcNow();
        var changes = new List<string>();
        if (t.Status is { } st && st != f.Status)
        {
            f.Status = st;
            changes.Add($"status={GimanoJson.ToWire(st)}");
            await ApplyClosedAsync(f, now, ct);
        }
        if (t.Severity is { } sev && sev != f.Severity) { f.Severity = sev; changes.Add($"severity={GimanoJson.ToWire(sev)}"); }
        if (t.Category is { } cat && cat != f.Category) { f.Category = cat; changes.Add($"category={GimanoJson.ToWire(cat)}"); }
        if (t.DuplicateOfId is { } dup)
        {
            if (dup == id || !await db.Feedback.AnyAsync(x => x.Id == dup, ct)) throw new ArgumentException("duplicateOfId must be another feedback item.");
            f.DuplicateOfId = dup;
            changes.Add($"duplicate_of={dup}");
        }
        if (t.CrashSignature is { } sig)
        {
            if (!await db.CrashGroups.AnyAsync(g => g.Signature == sig, ct)) throw new ArgumentException("Unknown crash signature.");
            f.CrashSignature = sig;
            changes.Add($"crash={sig[..Math.Min(12, sig.Length)]}");
        }
        if (t.Note is { } note) { f.TriageNote = Limit(note, 2000); changes.Add("note"); }
        if (t.ExternalUrl is { } url) { f.ExternalUrl = ValidateGitHubUrl(url); changes.Add("link"); }
        f.UpdatedAt = now;
        await db.SaveChangesAsync(ct);
        await AuditAsync(actor, "feedback.triage", "feedback", id.ToString(), string.Join(' ', changes), ct);
        return (await GetFeedbackAsync(id, ct))!;
    }

    /// <summary>Closing starts the 30-day clock on the volunteered contact; reopening never extends past the 90-day cap.</summary>
    private async Task ApplyClosedAsync(Feedback f, DateTimeOffset now, CancellationToken ct)
    {
        if (ClosedFeedback.Contains(f.Status))
        {
            f.ClosedAt ??= now;
            var contact = await db.ReportContacts.FirstOrDefaultAsync(c => c.FeedbackId == f.Id, ct);
            if (contact is not null) contact.ExpiresAt = ContactRetention.ExpiresAt(contact.CreatedAt, f.ClosedAt);
        }
        else f.ClosedAt = null;
    }

    // ---- Crashes ----------------------------------------------------------------------------------------------

    public async Task<IReadOnlyList<CrashGroupItem>> CrashGroupsAsync(CrashQuery q, CancellationToken ct = default)
    {
        var query = db.CrashGroups.AsNoTracking();
        if (q.Status is { } st) query = query.Where(g => g.Status == st);
        if (q.OpenOnly) query = query.Where(g => !ClosedCrash.Contains(g.Status));
        if (!string.IsNullOrWhiteSpace(q.Platform)) query = query.Where(g => g.Platform == q.Platform);
        if (!string.IsNullOrWhiteSpace(q.Text))
        {
            var pattern = "%" + EscapeLike(q.Text.Trim()) + "%";
            query = query.Where(g => EF.Functions.ILike(g.Exception, pattern) || EF.Functions.ILike(g.TopFrame!, pattern)
                || EF.Functions.ILike(g.Message!, pattern) || g.Signature.StartsWith(q.Text.Trim()));
        }
        return await query.OrderByDescending(g => g.LastSeenAt).Take(Math.Clamp(q.Take, 1, MaxTake)).Select(g => ToItem(g)).ToListAsync(ct);
    }

    public async Task<CrashGroupView?> GetCrashGroupAsync(string signature, CancellationToken ct = default)
    {
        var g = await db.CrashGroups.AsNoTracking().FirstOrDefaultAsync(x => x.Signature == signature, ct);
        if (g is null) return null;
        // Distribution over the retained events (≤ 90 days); grouped in memory because Device is a JSON column.
        var recent = await db.CrashEvents.AsNoTracking().Where(e => e.Signature == signature)
            .OrderByDescending(e => e.ReceivedAt).Take(2000).Select(e => new { e.Device, e.Screen, e.Stack }).ToListAsync(ct);
        static IReadOnlyList<Bucket> By<T>(IEnumerable<T> xs, Func<T, string?> key) =>
            xs.GroupBy(x => key(x) ?? "unknown").Select(x => new Bucket(x.Key, x.Count())).OrderByDescending(b => b.Count).Take(8).ToList();
        return new(ToItem(g), g.FixedInBuild, g.TriageNote, g.Analysis, g.AnalysisBy, recent.FirstOrDefault()?.Stack,
            By(recent, e => e.Device.AppBuild is { } b ? $"{e.Device.AppVersion} ({b})" : e.Device.AppVersion),
            By(recent, e => $"{e.Device.Platform} {e.Device.OsVersion}".Trim()),
            By(recent, e => e.Device.DeviceModel),
            By(recent, e => e.Screen),
            g.EventCount - recent.Count);
    }

    public async Task<IReadOnlyList<CrashEventView>> CrashEventsAsync(string signature, int take = 20, CancellationToken ct = default) =>
        await db.CrashEvents.AsNoTracking().Where(e => e.Signature == signature).OrderByDescending(e => e.ReceivedAt)
            .Take(Math.Clamp(take, 1, MaxTake))
            .Select(e => new CrashEventView(e.Id, e.OccurredAt, e.ReceivedAt, e.Kind, e.Message, e.Stack, e.Threads, e.Screen, e.UptimeS, e.Device, e.HasContact))
            .ToListAsync(ct);

    public async Task<IReadOnlyList<FeedbackListItem>> LinkedFeedbackAsync(string signature, CancellationToken ct = default) =>
        await db.Feedback.AsNoTracking().Where(f => f.CrashSignature == signature).OrderByDescending(f => f.CreatedAt).Take(50)
            .Select(f => new FeedbackListItem(f.Id, f.Category, f.Message, f.Rating, f.Screen, f.Status, f.Severity,
                f.Context!.AppVersion, f.Context!.Platform, f.Screenshot != null, db.ReportContacts.Any(c => c.FeedbackId == f.Id), f.CreatedAt))
            .ToListAsync(ct);

    public async Task<CrashGroupView> TriageCrashAsync(string signature, CrashTriage t, Actor actor, CancellationToken ct = default)
    {
        actor.Require(AgentScopes.TriageWrite);
        var g = await db.CrashGroups.FirstOrDefaultAsync(x => x.Signature == signature, ct) ?? throw new KeyNotFoundException("Crash group not found.");
        var changes = new List<string>();
        var wasFixed = g.Status == CrashStatus.Fixed;
        if (t.Status is { } st && st != g.Status) { g.Status = st; changes.Add($"status={GimanoJson.ToWire(st)}"); }
        if (t.Severity is { } sev && sev != g.Severity) { g.Severity = sev; changes.Add($"severity={GimanoJson.ToWire(sev)}"); }
        if (t.FixedInBuild is { } fb) { g.FixedInBuild = fb; changes.Add($"fixed_in_build={fb}"); }
        if (t.FixedInVersion is { } fv) { g.FixedInVersion = Limit(fv, 30); changes.Add($"fixed_in={fv}"); }
        if (t.Note is { } note) { g.TriageNote = Limit(note, 2000); changes.Add("note"); }
        if (t.ExternalUrl is { } url) { g.ExternalUrl = ValidateGitHubUrl(url); changes.Add("link"); }
        if (t.Analysis is { } analysis) { g.Analysis = Limit(analysis, 20_000); g.AnalysisBy = actor.Name; changes.Add("analysis"); }
        if (g.Status == CrashStatus.Fixed && g.FixedInBuild is null)
            throw new ArgumentException("Marking a group fixed needs fixedInBuild (the first build that contains the fix), so regressions can be detected.");
        g.UpdatedAt = clock.GetUtcNow();
        await db.SaveChangesAsync(ct);
        if (!wasFixed && g.Status == CrashStatus.Fixed) await DraftFixedRepliesAsync(g, ct);
        await AuditAsync(actor, "crash.triage", "crash", signature, string.Join(' ', changes), ct);
        return (await GetCrashGroupAsync(signature, ct))!;
    }

    /// <summary>When a group is fixed, linked feedback reporters get a reply draft for a maintainer to approve.</summary>
    private async Task DraftFixedRepliesAsync(CrashGroup g, CancellationToken ct)
    {
        var linked = await db.Feedback.Where(f => f.CrashSignature == g.Signature && !ClosedFeedback.Contains(f.Status) && f.UserId != null)
            .Select(f => f.Id).ToListAsync(ct);
        var now = clock.GetUtcNow();
        var version = g.FixedInVersion ?? $"build {g.FixedInBuild}";
        foreach (var id in linked)
        {
            if (await db.ReplyDrafts.AnyAsync(r => r.FeedbackId == id && r.Status == ReplyStatus.Draft, ct)) continue;
            db.ReplyDrafts.Add(new ReplyDraft
            {
                FeedbackId = id, NewStatus = FeedbackStatus.Done, DraftedBy = "system", CreatedAt = now,
                Message = $"Terima kasih! Masalah yang anda laporkan sudah dibaiki dalam versi {version}. / Thanks! The problem you reported is fixed in {version}.",
            });
        }
        await db.SaveChangesAsync(ct);
    }

    // ---- Contacts (maintainers only) ----------------------------------------------------------------------------

    /// <summary>Unseals volunteered contact details. Human only, reason required, always audited.</summary>
    public async Task<UnsealedContact> RevealContactAsync(string targetType, Guid id, string reason, Actor actor, CancellationToken ct = default)
    {
        actor.RequireHuman("reveal contact details");
        if (string.IsNullOrWhiteSpace(reason) || reason.Trim().Length < 5) throw new ArgumentException("Give a reason (at least 5 characters); it is logged.");
        if (contacts is null) throw new InvalidOperationException("Contact:EncryptionKey is not configured on this host.");
        var row = targetType switch
        {
            "feedback" => await db.ReportContacts.AsNoTracking().FirstOrDefaultAsync(c => c.FeedbackId == id, ct),
            "crash" => await db.ReportContacts.AsNoTracking().FirstOrDefaultAsync(c => c.CrashEventId == id, ct),
            _ => throw new ArgumentException("targetType must be feedback or crash."),
        } ?? throw new KeyNotFoundException("No contact details on this report (withdrawn or expired).");
        await AuditAsync(actor, "contact.reveal", targetType, id.ToString(), Limit(reason.Trim(), 300), ct);
        return contacts.Unseal(row.Sealed);
    }

    // ---- Agent runs ---------------------------------------------------------------------------------------------

    public async Task<AgentRunView> StartRunAsync(string title, string targetType, string targetId, Actor actor, CancellationToken ct = default)
    {
        actor.Require(AgentScopes.RunsWrite);
        await EnsureTargetAsync(targetType, targetId, ct);
        var now = clock.GetUtcNow();
        var run = new AgentRun
        {
            Title = Limit(title, 200), TargetType = targetType, TargetId = targetId, Agent = actor.Name, StartedAt = now, UpdatedAt = now,
            Steps = [new AgentRunStep(now, "Started", null)],
        };
        db.AgentRuns.Add(run);
        if (targetType == "crash")
            await db.CrashGroups.Where(g => g.Signature == targetId && (g.Status == CrashStatus.New || g.Status == CrashStatus.Triaged || g.Status == CrashStatus.Regressed))
                .ExecuteUpdateAsync(s => s.SetProperty(g => g.Status, CrashStatus.Fixing), ct);
        await db.SaveChangesAsync(ct);
        await AuditAsync(actor, "run.start", "run", run.Id.ToString(), $"{targetType}:{targetId}", ct);
        return ToView(run);
    }

    public async Task<AgentRunView> LogRunStepAsync(int id, string title, string? detail, Actor actor, CancellationToken ct = default)
    {
        actor.Require(AgentScopes.RunsWrite);
        var run = await db.AgentRuns.FirstOrDefaultAsync(r => r.Id == id, ct) ?? throw new KeyNotFoundException("Run not found.");
        if (run.Steps.Count >= 200) throw new ArgumentException("A run keeps at most 200 steps.");
        var now = clock.GetUtcNow();
        run.Steps = [.. run.Steps, new AgentRunStep(now, Limit(title, 200), detail is null ? null : Limit(detail, 4000))];
        run.UpdatedAt = now;
        await db.SaveChangesAsync(ct);
        return ToView(run);
    }

    public async Task<AgentRunView> UpdateRunAsync(int id, AgentRunUpdate u, Actor actor, CancellationToken ct = default)
    {
        actor.Require(AgentScopes.RunsWrite);
        var run = await db.AgentRuns.FirstOrDefaultAsync(r => r.Id == id, ct) ?? throw new KeyNotFoundException("Run not found.");
        // An agent can hand a run to review, fail or cancel it; only a maintainer marks it done.
        if (u.Status == AgentRunStatus.Done) actor.RequireHuman("mark a run done");
        if (u.Status is { } st) run.Status = st;
        if (u.Branch is { } br)
        {
            if (actor.IsAgent && !br.StartsWith("agent/", StringComparison.Ordinal)) throw new ArgumentException("Agent branches must start with agent/.");
            run.Branch = Limit(br, 120);
        }
        if (u.PrUrl is { } pr) run.PrUrl = ValidateGitHubUrl(pr);
        if (u.Summary is { } sum) run.Summary = Limit(sum, 20_000);
        run.UpdatedAt = clock.GetUtcNow();
        await db.SaveChangesAsync(ct);
        await AuditAsync(actor, "run.update", "run", id.ToString(), u.Status is { } s2 ? $"status={GimanoJson.ToWire(s2)}" : null, ct);
        return ToView(run);
    }

    public async Task<IReadOnlyList<AgentRunView>> RunsAsync(string? targetType = null, string? targetId = null, int take = 50, CancellationToken ct = default)
    {
        var q = db.AgentRuns.AsNoTracking();
        if (targetType is not null) q = q.Where(r => r.TargetType == targetType);
        if (targetId is not null) q = q.Where(r => r.TargetId == targetId);
        var runs = await q.OrderByDescending(r => r.UpdatedAt).Take(Math.Clamp(take, 1, MaxTake)).ToListAsync(ct);
        return runs.Select(ToView).ToList();
    }

    public async Task<AgentRunView?> GetRunAsync(int id, CancellationToken ct = default) =>
        await db.AgentRuns.AsNoTracking().FirstOrDefaultAsync(r => r.Id == id, ct) is { } r ? ToView(r) : null;

    // ---- Replies ------------------------------------------------------------------------------------------------

    public async Task<ReplyView> DraftReplyAsync(Guid feedbackId, string message, FeedbackStatus? newStatus, Actor actor, CancellationToken ct = default)
    {
        actor.Require(AgentScopes.RepliesDraft);
        if (string.IsNullOrWhiteSpace(message) || message.Trim().Length > 1000) throw new ArgumentException("message is required (1–1000 chars).");
        if (!await db.Feedback.AnyAsync(f => f.Id == feedbackId, ct)) throw new KeyNotFoundException("Feedback not found.");
        var draft = new ReplyDraft
        {
            FeedbackId = feedbackId, Message = message.Trim(), NewStatus = newStatus, DraftedBy = actor.Name, CreatedAt = clock.GetUtcNow(),
        };
        db.ReplyDrafts.Add(draft);
        await db.SaveChangesAsync(ct);
        await AuditAsync(actor, "reply.draft", "feedback", feedbackId.ToString(), null, ct);
        return (await RepliesAsync(feedbackId: feedbackId, ct: ct)).First(r => r.Id == draft.Id);
    }

    public async Task<IReadOnlyList<ReplyView>> RepliesAsync(ReplyStatus? status = null, Guid? feedbackId = null, int take = 100, CancellationToken ct = default)
    {
        var q = db.ReplyDrafts.AsNoTracking();
        if (status is { } s) q = q.Where(r => r.Status == s);
        if (feedbackId is { } fid) q = q.Where(r => r.FeedbackId == fid);
        return await q.OrderByDescending(r => r.CreatedAt).Take(Math.Clamp(take, 1, MaxTake))
            .Join(db.Feedback, r => r.FeedbackId, f => f.Id, (r, f) => new ReplyView(r.Id, r.FeedbackId, f.Message, r.Message, r.NewStatus,
                r.Status, r.DraftedBy, r.CreatedAt, r.DecidedBy, r.DecidedAt, f.UserId != null))
            .ToListAsync(ct);
    }

    /// <summary>Human only: sends the (possibly edited) reply to the reporter's in-app Inbox and applies the new status.</summary>
    public async Task ApproveReplyAsync(Guid replyId, string? editedMessage, Actor actor, CancellationToken ct = default)
    {
        actor.RequireHuman("send a reply");
        var r = await db.ReplyDrafts.FirstOrDefaultAsync(x => x.Id == replyId, ct) ?? throw new KeyNotFoundException("Reply not found.");
        if (r.Status != ReplyStatus.Draft) throw new InvalidOperationException("This reply was already decided.");
        var f = await db.Feedback.FirstAsync(x => x.Id == r.FeedbackId, ct);
        var now = clock.GetUtcNow();
        if (!string.IsNullOrWhiteSpace(editedMessage)) r.Message = Limit(editedMessage.Trim(), 1000);
        if (r.NewStatus is { } st && st != f.Status)
        {
            f.Status = st;
            f.UpdatedAt = now;
            await ApplyClosedAsync(f, now, ct);
        }
        if (f.UserId is { } uid)
        {
            var locale = await db.Users.Where(u => u.Id == uid).Select(u => u.Locale).FirstOrDefaultAsync(ct) ?? "ms";
            db.InboxItems.Add(new InboxItem
            {
                UserId = uid, Kind = "feedback_status", Title = InboxTitle(f.Status, locale), Body = r.Message,
                RefType = "feedback", RefId = f.Id.ToString(), CreatedAt = now,
            });
        }
        r.Status = ReplyStatus.Sent;
        r.DecidedBy = actor.Name;
        r.DecidedAt = now;
        await db.SaveChangesAsync(ct);
        await AuditAsync(actor, "reply.send", "feedback", f.Id.ToString(), f.UserId is null ? "reporter deleted their account; status only" : null, ct);
    }

    public async Task DiscardReplyAsync(Guid replyId, Actor actor, CancellationToken ct = default)
    {
        actor.RequireHuman("discard a reply");
        var now = clock.GetUtcNow();
        var n = await db.ReplyDrafts.Where(r => r.Id == replyId && r.Status == ReplyStatus.Draft).ExecuteUpdateAsync(s => s
            .SetProperty(r => r.Status, ReplyStatus.Discarded).SetProperty(r => r.DecidedBy, actor.Name).SetProperty(r => r.DecidedAt, now), ct);
        if (n == 0) throw new KeyNotFoundException("No draft with that id.");
        await AuditAsync(actor, "reply.discard", "reply", replyId.ToString(), null, ct);
    }

    internal static string InboxTitle(FeedbackStatus status, string locale)
    {
        var ms = locale.StartsWith("ms", StringComparison.OrdinalIgnoreCase);
        return (status, ms) switch
        {
            (FeedbackStatus.Done, true) => "Maklum balas anda: sudah dibaiki",
            (FeedbackStatus.Done, false) => "Your feedback: fixed",
            (FeedbackStatus.Planned, true) => "Maklum balas anda: dalam perancangan",
            (FeedbackStatus.Planned, false) => "Your feedback: planned",
            (FeedbackStatus.WontFix, true) => "Maklum balas anda: ditutup",
            (FeedbackStatus.WontFix, false) => "Your feedback: closed",
            (_, true) => "Balasan untuk maklum balas anda",
            _ => "A reply to your feedback",
        };
    }

    // ---- Agent tokens -------------------------------------------------------------------------------------------

    public async Task<CreatedAgentToken> CreateTokenAsync(string name, IEnumerable<string> scopes, TimeSpan lifetime, Actor actor, CancellationToken ct = default)
    {
        actor.RequireHuman("create agent tokens");
        var scopeList = scopes.Distinct().ToArray();
        if (scopeList.Length == 0 || scopeList.Any(s => !AgentScopes.IsValid(s)))
            throw new ArgumentException($"Scopes must be some of: {string.Join(", ", AgentScopes.All)}.");
        if (string.IsNullOrWhiteSpace(name) || name.Length > 60) throw new ArgumentException("name is required (max 60 chars).");
        if (lifetime <= TimeSpan.Zero || lifetime > AgentTokenPolicy.MaxLifetime)
            throw new ArgumentException($"Tokens live at most {AgentTokenPolicy.MaxLifetime.TotalHours:0} hours.");
        var secret = AgentTokenFormat.Generate();
        var now = clock.GetUtcNow();
        var token = new AgentToken
        {
            Name = name.Trim(), Prefix = AgentTokenFormat.Display(secret), TokenHash = AgentTokenFormat.Hash(secret), Scopes = scopeList,
            CreatedBy = actor.Name, CreatedAt = now, ExpiresAt = now + lifetime,
        };
        db.AgentTokens.Add(token);
        await db.SaveChangesAsync(ct);
        await AuditAsync(actor, "token.create", "token", token.Id.ToString(), $"{token.Name} [{string.Join(',', scopeList)}]", ct);
        return new(ToView(token), secret);
    }

    public async Task RevokeTokenAsync(Guid id, Actor actor, CancellationToken ct = default)
    {
        actor.RequireHuman("revoke agent tokens");
        var now = clock.GetUtcNow();
        await db.AgentTokens.Where(t => t.Id == id && t.RevokedAt == null).ExecuteUpdateAsync(s => s.SetProperty(t => t.RevokedAt, now), ct);
        await AuditAsync(actor, "token.revoke", "token", id.ToString(), null, ct);
    }

    public async Task<IReadOnlyList<AgentTokenView>> TokensAsync(CancellationToken ct = default) =>
        (await db.AgentTokens.AsNoTracking().OrderByDescending(t => t.CreatedAt).Take(100).ToListAsync(ct)).Select(ToView).ToList();

    /// <summary>Resolves a bearer secret to an agent actor, or null when unknown, expired or revoked.</summary>
    public async Task<Actor?> AuthenticateAgentAsync(string secret, CancellationToken ct = default)
    {
        if (!secret.StartsWith(AgentTokenFormat.Prefix, StringComparison.Ordinal) || secret.Length > 100) return null;
        var hash = AgentTokenFormat.Hash(secret);
        var now = clock.GetUtcNow();
        var t = await db.AgentTokens.FirstOrDefaultAsync(x => x.TokenHash == hash, ct);
        if (t is null || t.RevokedAt is not null || t.ExpiresAt <= now) return null;
        if (t.LastUsedAt is null || now - t.LastUsedAt > TimeSpan.FromMinutes(1))
        {
            t.LastUsedAt = now;
            await db.SaveChangesAsync(ct);
        }
        return Actor.Agent(t.Name, t.Scopes);
    }

    // ---- Builds & audit -----------------------------------------------------------------------------------------

    public async Task<IReadOnlyList<AppBuildView>> BuildsAsync(string? platform = null, CancellationToken ct = default)
    {
        var q = db.AppBuilds.AsNoTracking();
        if (platform is not null) q = q.Where(b => b.Platform == platform);
        return await q.OrderByDescending(b => b.ReleasedAt).Take(100)
            .Select(b => new AppBuildView(b.Id, b.Platform, b.Version, b.Build, b.Commit, b.SymbolsUrl, b.ReleasedAt)).ToListAsync(ct);
    }

    public async Task<AppBuildView?> ResolveBuildAsync(string platform, int? build, string? version, CancellationToken ct = default)
    {
        var q = db.AppBuilds.AsNoTracking().Where(b => b.Platform == platform);
        if (build is { } n) q = q.Where(b => b.Build == n);
        else if (version is not null) q = q.Where(b => b.Version == version).OrderByDescending(b => b.Build);
        else return null;
        return await q.Select(b => new AppBuildView(b.Id, b.Platform, b.Version, b.Build, b.Commit, b.SymbolsUrl, b.ReleasedAt)).FirstOrDefaultAsync(ct);
    }

    public async Task<AppBuildView> AddBuildAsync(string platform, string version, int build, string commit, string? symbolsUrl, Actor actor, CancellationToken ct = default)
    {
        actor.RequireHuman("register builds");
        if (platform is not ("android" or "ios")) throw new ArgumentException("platform must be android or ios.");
        if (string.IsNullOrWhiteSpace(commit) || commit.Length is < 7 or > 40) throw new ArgumentException("commit must be a git sha (7–40 chars).");
        var b = new AppBuild
        {
            Platform = platform, Version = Limit(version.Trim(), 30), Build = build, Commit = commit.Trim(),
            SymbolsUrl = string.IsNullOrWhiteSpace(symbolsUrl) ? null : Limit(symbolsUrl.Trim(), 300), ReleasedAt = clock.GetUtcNow(),
        };
        db.AppBuilds.Add(b);
        await db.SaveChangesAsync(ct);
        await AuditAsync(actor, "build.add", "build", $"{platform}:{build}", $"{version} {commit}", ct);
        return new(b.Id, b.Platform, b.Version, b.Build, b.Commit, b.SymbolsUrl, b.ReleasedAt);
    }

    public async Task<IReadOnlyList<AuditView>> AuditAsync(int take = 200, long? before = null, CancellationToken ct = default)
    {
        var q = db.AuditLog.AsNoTracking();
        if (before is { } b) q = q.Where(a => a.Id < b);
        return await q.OrderByDescending(a => a.Id).Take(Math.Clamp(take, 1, 500))
            .Select(a => new AuditView(a.Id, a.At, a.Actor, a.ActorKind, a.Action, a.TargetType, a.TargetId, a.Detail)).ToListAsync(ct);
    }

    /// <summary>Agents read a lot; only reads of sensitive things (screenshots) are logged individually, every write is.</summary>
    public async Task AuditAsync(Actor actor, string action, string targetType, string? targetId, string? detail, CancellationToken ct = default)
    {
        db.AuditLog.Add(new AuditEntry
        {
            At = clock.GetUtcNow(), Actor = Limit(actor.Name, 100), ActorKind = actor.Kind, Action = action, TargetType = targetType,
            TargetId = targetId is null ? null : Limit(targetId, 80), Detail = string.IsNullOrEmpty(detail) ? null : Limit(detail, 500),
        });
        await db.SaveChangesAsync(ct);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private async Task EnsureTargetAsync(string targetType, string targetId, CancellationToken ct)
    {
        var exists = targetType switch
        {
            "crash" => await db.CrashGroups.AnyAsync(g => g.Signature == targetId, ct),
            "feedback" => Guid.TryParse(targetId, out var id) && await db.Feedback.AnyAsync(f => f.Id == id, ct),
            _ => throw new ArgumentException("targetType must be feedback or crash."),
        };
        if (!exists) throw new KeyNotFoundException($"No {targetType} with id {targetId}.");
    }

    private static CrashGroupItem ToItem(CrashGroup g) => new(g.Signature, g.Platform, g.Kind, g.Exception, g.Message, g.TopFrame, g.Status,
        g.Severity, g.EventCount, g.FirstSeenAt, g.LastSeenAt, g.FirstVersion, g.LatestVersion, g.FixedInVersion, g.ExternalUrl);

    private static AgentRunView ToView(AgentRun r) => new(r.Id, r.Title, r.TargetType, r.TargetId, r.Status, r.Agent, r.StartedAt,
        r.UpdatedAt, r.Branch, r.PrUrl, r.Summary, r.Steps);

    private static AgentTokenView ToView(AgentToken t) => new(t.Id, t.Name, t.Prefix, t.Scopes, t.CreatedBy, t.CreatedAt, t.ExpiresAt, t.RevokedAt, t.LastUsedAt);

    /// <summary>Links may only point at GitHub, so a crafted note cannot smuggle other URLs into the back office.</summary>
    internal static string? ValidateGitHubUrl(string url)
    {
        var u = url.Trim();
        if (u.Length == 0) return null;
        if (!Uri.TryCreate(u, UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttps || uri.Host != "github.com" || u.Length > 300)
            throw new ArgumentException("Links must be https://github.com/… URLs.");
        return u;
    }

    private static string EscapeLike(string s) => s.Replace("\\", "\\\\").Replace("%", "\\%").Replace("_", "\\_");

    private static string Limit(string s, int max) => s.Length <= max ? s : s[..max];

    private static string FirstLine(string s, int max) => Limit(s.Split('\n', 2)[0].Trim(), max);

    private static string ShortException(string e) => e.Contains('.') ? e[(e.LastIndexOf('.') + 1)..] : e;

    private static string? VersionRange(string? first, string? latest) =>
        first is null ? latest : latest is null || latest == first ? first : $"{first}–{latest}";
}

public static class AgentTokenPolicy
{
    public static readonly TimeSpan MaxLifetime = TimeSpan.FromHours(8);
}

public static class ContactRetention
{
    public static readonly TimeSpan Max = TimeSpan.FromDays(90);
    public static readonly TimeSpan AfterClose = TimeSpan.FromDays(30);

    public static DateTimeOffset ExpiresAt(DateTimeOffset createdAt, DateTimeOffset? closedAt)
    {
        var cap = createdAt + Max;
        return closedAt is { } c && c + AfterClose < cap ? c + AfterClose : cap;
    }
}
