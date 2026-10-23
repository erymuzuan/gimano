using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Core.BackOffice;

/// <summary>Stores incoming crash reports (grouped by signature) and seals volunteered contact details.</summary>
public sealed class ReportIntake(GimanoDbContext db, TimeProvider clock, ContactProtector? contacts = null)
{
    public const int MaxStackChars = 32_768;
    public const int MaxMessageChars = 500;

    public bool CanSealContacts => contacts is not null;

    public async Task<CrashReportCreatedResponse> AddCrashAsync(CreateCrashReportRequest req, CancellationToken ct = default)
    {
        var now = clock.GetUtcNow();
        var platform = (req.Device.Platform ?? "unknown").Trim().ToLowerInvariant();
        var exception = Limit(req.Exception.Trim(), 200);
        var stack = Limit(req.Stack, MaxStackChars);
        var signature = CrashSignature.Compute(platform, exception, stack);
        var message = req.Message is null ? null : Limit(req.Message, MaxMessageChars);
        // The phone's clock can be wrong; never trust it beyond "now".
        var occurredAt = req.OccurredAt > now ? now : req.OccurredAt;

        await using var tx = await db.Database.BeginTransactionAsync(ct);
        // Upsert without a failing statement (a unique violation would abort the Postgres transaction).
        var kind = req.Kind.ToString();
        var status = nameof(CrashStatus.New);
        var topFrame = CrashSignature.TopFrame(stack);
        await db.Database.ExecuteSqlInterpolatedAsync($"""
            INSERT INTO crash_groups (signature, platform, kind, exception, message, top_frame, status, event_count,
                                      first_seen_at, last_seen_at, first_version, updated_at)
            VALUES ({signature}, {platform}, {kind}, {exception}, {message}, {topFrame}, {status}, 0, {now}, {now}, {req.Device.AppVersion}, {now})
            ON CONFLICT (signature) DO NOTHING
            """, ct);
        await db.CrashGroups.Where(g => g.Signature == signature).ExecuteUpdateAsync(u => u
            .SetProperty(g => g.EventCount, g => g.EventCount + 1)
            .SetProperty(g => g.LastSeenAt, now), ct);
        var group = await db.CrashGroups.FirstAsync(g => g.Signature == signature, ct);
        group.Message = message ?? group.Message;
        if (req.Device.AppBuild is { } build && (group.LatestBuild is null || build >= group.LatestBuild))
        {
            group.LatestBuild = build;
            group.LatestVersion = req.Device.AppVersion;
        }
        if (CrashLifecycle.ShouldReopen(group.Status, group.FixedInBuild, req.Device.AppBuild)) group.Status = CrashStatus.Regressed;
        group.UpdatedAt = now;

        var ev = new CrashEvent
        {
            Signature = signature, OccurredAt = occurredAt, ReceivedAt = now, Kind = req.Kind, Exception = exception, Message = message,
            Stack = stack, Threads = req.Threads is null ? null : Limit(req.Threads, MaxStackChars), Screen = req.Screen is null ? null : Limit(req.Screen, 20),
            UptimeS = req.UptimeS, Device = TrimDevice(req.Device), HasContact = HasContact(req.Contact) && contacts is not null,
        };
        db.CrashEvents.Add(ev);
        if (ev.HasContact) db.ReportContacts.Add(SealContact(req.Contact!, now, crashEventId: ev.Id));
        await db.SaveChangesAsync(ct);
        await tx.CommitAsync(ct);
        return new(ev.Id, signature);
    }

    /// <summary>Seals the contact for a feedback item that is being added in the same unit of work.</summary>
    public void AttachContact(Feedback feedback, ContactDetails? contact)
    {
        if (!HasContact(contact) || contacts is null) return;
        db.ReportContacts.Add(SealContact(contact!, feedback.CreatedAt, feedbackId: feedback.Id));
    }

    public static bool HasContact(ContactDetails? c) =>
        c is { Consent: true } && (!string.IsNullOrWhiteSpace(c.Email) || !string.IsNullOrWhiteSpace(c.Whatsapp));

    private ReportContact SealContact(ContactDetails c, DateTimeOffset now, Guid? feedbackId = null, Guid? crashEventId = null) => new()
    {
        FeedbackId = feedbackId, CrashEventId = crashEventId, Sealed = contacts!.Seal(c),
        HasEmail = !string.IsNullOrWhiteSpace(c.Email), HasWhatsapp = !string.IsNullOrWhiteSpace(c.Whatsapp),
        CreatedAt = now, ExpiresAt = ContactRetention.ExpiresAt(now, null),
    };

    public static DeviceInfo TrimDevice(DeviceInfo d) => d with
    {
        AppVersion = Limit(d.AppVersion, 30), Platform = Limit(d.Platform, 10), OsVersion = Limit(d.OsVersion, 30),
        DeviceModel = Limit(d.DeviceModel, 60), Manufacturer = Limit(d.Manufacturer, 60), Locale = Limit(d.Locale, 20),
        Theme = Limit(d.Theme, 10), DataMode = Limit(d.DataMode, 10), LocationPermission = Limit(d.LocationPermission, 12),
    };

    [return: System.Diagnostics.CodeAnalysis.NotNullIfNotNull(nameof(s))]
    private static string? Limit(string? s, int max) => s is null ? null : s.Length <= max ? s : s[..max];
}

public static class CrashLifecycle
{
    /// <summary>A fixed group reopens when an event arrives from the build the fix shipped in, or later.</summary>
    public static bool ShouldReopen(CrashStatus status, int? fixedInBuild, int? eventBuild) =>
        status == CrashStatus.Fixed && fixedInBuild is { } fixedIn && eventBuild is { } b && b >= fixedIn;
}
