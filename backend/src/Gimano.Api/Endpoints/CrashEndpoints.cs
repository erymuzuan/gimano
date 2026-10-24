using Gimano.Api.Auth;
using Gimano.Core.BackOffice;
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
        api.MapDelete("/crash-reports/{id:guid}/contact", DeleteContact).WithTags("Crash reports").RequireAuthorization()
            .WithSummary("Withdraw the contact left on a crash report (X-Contact-Token from the create response).");
        return api;
    }

    private static async Task<Results<NoContent, NotFound>> DeleteContact(Guid id, HttpContext http, GimanoDbContext db, ContactCrypto crypto, CancellationToken ct)
    {
        if (!crypto.CheckWithdrawToken(ContactSubject.Crash, id, http.Request.Headers[FeedbackEndpoints.ContactTokenHeader].ToString()))
            return TypedResults.NotFound();
        await db.ReportContacts.Where(c => c.SubjectType == ContactSubject.Crash && c.SubjectId == id).ExecuteDeleteAsync(ct);
        return TypedResults.NoContent();
    }

    private static async Task<Results<Created<CrashReportCreated>, ValidationProblem, ProblemHttpResult>> Create(
        CrashReportRequest req, HttpContext http, GimanoDbContext db, ContactCrypto crypto, TimeProvider clock,
        ILogger<CrashReportRequest> log, Microsoft.Extensions.Caching.Memory.IMemoryCache cache, CancellationToken ct)
    {
        if (Validate(req) is { } problem) return problem;
        var uid = http.User.RequiredUserId();
        var now = clock.GetUtcNow();
        var withContact = FeedbackEndpoints.WillStoreContact(crypto, req.Contact);
        if (await db.CrashReports.CountAsync(c => c.UserId == uid && c.CreatedAt > now.AddDays(-1), ct)
            + UnlinkedReports.Count(cache, "crash", uid, now, TimeSpan.FromDays(1)) >= MaxPerDay)
            return TypedResults.Problem(statusCode: StatusCodes.Status429TooManyRequests, title: "Too many crash reports",
                detail: $"At most {MaxPerDay} crash reports per day.");

        var exception = CrashScrubber.Scrub(req.Exception.Trim(), 300)!;
        var stack = CrashScrubber.Scrub(req.Stack, MaxStack);
        var signature = CrashScrubber.Signature(exception, stack);
        var report = new CrashReport
        {
            // With a volunteered contact the report is not linked to the account (the contact must not be linkable to it).
            UserId = !withContact && await db.Users.AnyAsync(u => u.Id == uid, ct) ? uid : null,
            Signature = signature, Kind = req.Kind, Exception = exception,
            Message = CrashScrubber.Scrub(req.Message, 500), Stack = stack, Threads = CrashScrubber.Scrub(req.Threads, MaxStack),
            Screen = req.Screen?.Length > 32 ? req.Screen[..32] : req.Screen, UptimeS = req.UptimeS, Device = req.Device,
            OccurredAt = req.OccurredAt is { } o && o <= now.AddMinutes(5) ? o : now, CreatedAt = now,
        };
        await RecordAsync(db, report, exception, now, d => FeedbackEndpoints.StoreContact(d, crypto, ContactSubject.Crash, report.Id, req.Contact, now, log), ct);
        if (withContact) UnlinkedReports.Add(cache, "crash", uid, now, TimeSpan.FromDays(1));
        return TypedResults.Created($"/api/v1/crash-reports/{report.Id}", new CrashReportCreated(report.Id, signature,
            withContact ? crypto.WithdrawToken(ContactSubject.Crash, report.Id) : null));
    }

    /// <summary>
    /// Saves a crash report into its group. An existing group is bumped with one atomic UPDATE (count + 1, last seen, summary
    /// fields, and a closed group reopens or regresses: CrashLifecycle), so concurrent reports never lose a count. A new group is
    /// inserted with count 1; if another request inserted it first (unique signature), this one falls back to the bump.
    /// </summary>
    internal static async Task RecordAsync(GimanoDbContext db, CrashReport report, string exception, DateTimeOffset now,
        Action<GimanoDbContext> addContact, CancellationToken ct)
    {
        var signature = report.Signature;
        Task<int> BumpAsync() => CrashLifecycle.BumpAsync(db, report, now, ct);

        if (await BumpAsync() == 0)
        {
            var group = new CrashGroup { Signature = signature, Exception = exception, FirstSeen = now };
            CrashLifecycle.OnReport(group, report, now);   // count 1, summary fields
            db.CrashGroups.Add(group);
            db.CrashReports.Add(report);
            addContact(db);
            try
            {
                await db.SaveChangesAsync(ct);
                return;
            }
            catch (DbUpdateException ex) when (ex.InnerException is Npgsql.PostgresException { SqlState: Npgsql.PostgresErrorCodes.UniqueViolation })
            {
                // Another request created the same group concurrently: count this one as a bump of that group.
                db.ChangeTracker.Clear();
                await BumpAsync();
            }
        }
        db.CrashReports.Add(report);
        addContact(db);
        await db.SaveChangesAsync(ct);
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
