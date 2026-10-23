using Gimano.Api.Auth;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Gimano.Core.Support;
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Api.Endpoints;

/// <summary>Own crash reporting (no third-party SDK): scrubbed on the phone, re-scrubbed here, grouped by signature, 90-day retention.</summary>
public static class CrashEndpoints
{
    public const int MaxPerDay = 20;
    public const int MaxStack = 32 * 1024;

    public static RouteGroupBuilder MapCrashReports(this RouteGroupBuilder api)
    {
        api.MapPost("/crash-reports", Create).WithTags("Crash reports").RequireAuthorization()
            .WithMetadata(new Microsoft.AspNetCore.Mvc.RequestSizeLimitAttribute(256 * 1024))
            .WithSummary("Submit a crash record (sent only with the user's consent). 201 {id, signature}; 429 above 20/day.");
        return api;
    }

    private static async Task<Results<Created<CrashReportCreated>, ValidationProblem, ProblemHttpResult>> Create(
        CrashReportRequest req, HttpContext http, GimanoDbContext db, ContactCrypto crypto, TimeProvider clock,
        ILogger<CrashReportRequest> log, CancellationToken ct)
    {
        if (Validate(req) is { } problem) return problem;
        var uid = http.User.RequiredUserId();
        var now = clock.GetUtcNow();
        if (await db.CrashReports.CountAsync(c => c.UserId == uid && c.CreatedAt > now.AddDays(-1), ct) >= MaxPerDay)
            return TypedResults.Problem(statusCode: StatusCodes.Status429TooManyRequests, title: "Too many crash reports",
                detail: $"At most {MaxPerDay} crash reports per day.");

        var exception = CrashScrubber.Scrub(req.Exception.Trim(), 300)!;
        var stack = CrashScrubber.Scrub(req.Stack, MaxStack);
        var signature = CrashScrubber.Signature(exception, stack);
        var report = new CrashReport
        {
            UserId = await db.Users.AnyAsync(u => u.Id == uid, ct) ? uid : null,
            Signature = signature, Kind = req.Kind, Exception = exception,
            Message = CrashScrubber.Scrub(req.Message, 500), Stack = stack, Threads = CrashScrubber.Scrub(req.Threads, MaxStack),
            Screen = req.Screen?.Length > 32 ? req.Screen[..32] : req.Screen, UptimeS = req.UptimeS, Device = req.Device,
            OccurredAt = req.OccurredAt is { } o && o <= now.AddMinutes(5) ? o : now, CreatedAt = now,
        };
        var group = await db.CrashGroups.FirstOrDefaultAsync(g => g.Signature == signature, ct);
        if (group is null) db.CrashGroups.Add(group = new CrashGroup { Signature = signature, Exception = exception, FirstSeen = now });
        group.Count++;
        group.LastSeen = now;
        // A closed group that crashes again reopens (its contact retention restarts from the new report).
        if (group.Status != CrashGroupStatus.Open) { group.Status = CrashGroupStatus.Open; group.ClosedAt = null; }
        db.CrashReports.Add(report);
        FeedbackEndpoints.StoreContact(db, crypto, ContactSubject.Crash, report.Id, req.Contact, now, log);
        try { await db.SaveChangesAsync(ct); }
        catch (DbUpdateException) when (group.Count == 1)
        {
            // Another request created the same group concurrently: retry as an increment.
            db.ChangeTracker.Clear();
            var g = await db.CrashGroups.FirstAsync(x => x.Signature == signature, ct);
            g.Count++; g.LastSeen = now;
            db.CrashReports.Add(report);
            FeedbackEndpoints.StoreContact(db, crypto, ContactSubject.Crash, report.Id, req.Contact, now, log);
            await db.SaveChangesAsync(ct);
        }
        return TypedResults.Created($"/api/v1/crash-reports/{report.Id}", new CrashReportCreated(report.Id, signature));
    }

    internal static ValidationProblem? Validate(CrashReportRequest req)
    {
        if (string.IsNullOrWhiteSpace(req.Exception) || req.Exception.Length > 300) return Validation.Problem("exception", "exception is required (≤ 300 chars).");
        if (req.Message is { Length: > 2000 }) return Validation.Problem("message", "message is too long.");
        if (req.Stack is { Length: > MaxStack } || req.Threads is { Length: > MaxStack }) return Validation.Problem("stack", "stack/threads are limited to 32 KB.");
        if (ContactCrypto.Validate(req.Contact) is { } e) return Validation.Problem("contact", e);
        return FeedbackEndpoints.ValidateDevice(req.Device, "device");
    }
}
