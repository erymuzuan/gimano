using Gimano.Api.Auth;
using Gimano.Core.BackOffice;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Caching.Memory;

namespace Gimano.Api.Endpoints;

/// <summary>
/// Crash reports the app recorded locally and the user agreed to send (docs/api.md "Crash reports").
/// Stored without the user id: the account is used only for the in-memory daily limit.
/// </summary>
public static class CrashReportEndpoints
{
    public const int MaxPerDay = 20;
    public const int MaxStackChars = 40_000;

    public static RouteGroupBuilder MapCrashReports(this RouteGroupBuilder api)
    {
        api.MapPost("/crash-reports", Create).WithTags("Feedback").RequireAuthorization()
            .WithSummary("Send a scrubbed crash report. Grouped by signature; 429 above 20 per day per install.");
        return api;
    }

    private static async Task<Results<Created<CrashReportCreatedResponse>, ValidationProblem, ProblemHttpResult>> Create(
        CreateCrashReportRequest req, HttpContext http, GimanoDbContext db, ReportIntake intake, IMemoryCache cache, TimeProvider clock,
        CancellationToken ct)
    {
        if (Validate(req) is { } problem) return problem;
        if (req.Stack.Length > MaxStackChars || req.Threads?.Length > MaxStackChars)
            return TypedResults.Problem(statusCode: StatusCodes.Status413PayloadTooLarge, title: "Crash report too large",
                detail: "stack and threads are limited to 32 KB each.");

        var uid = http.User.RequiredUserId();
        if (!await db.Users.AnyAsync(u => u.Id == uid, ct)) throw new UnauthorizedAccessException("This account was deleted.");
        var now = clock.GetUtcNow();
        var key = $"crash-quota:{uid:N}:{now:yyyyMMdd}";
        var count = cache.GetOrCreate(key, e => { e.AbsoluteExpirationRelativeToNow = TimeSpan.FromDays(1); return new Counter(); })!;
        if (Interlocked.Increment(ref count.Value) > MaxPerDay)
        {
            http.Response.Headers.RetryAfter = "86400";
            return TypedResults.Problem(statusCode: StatusCodes.Status429TooManyRequests, title: "Too many crash reports",
                detail: $"At most {MaxPerDay} crash reports per day.");
        }

        var created = await intake.AddCrashAsync(req, ct);
        return TypedResults.Created($"/api/v1/crash-reports/{created.Id}", created);
    }

    private sealed class Counter { public int Value; }

    internal static ValidationProblem? Validate(CreateCrashReportRequest req)
    {
        if (string.IsNullOrWhiteSpace(req.Exception) || req.Exception.Length > 200) return Validation.Problem("exception", "exception is required (max 200 chars).");
        if (string.IsNullOrWhiteSpace(req.Stack)) return Validation.Problem("stack", "stack is required.");
        if (req.Device is null) return Validation.Problem("device", "device is required on crash reports.");
        if (req.Device.Platform is not ("android" or "ios")) return Validation.Problem("device.platform", "platform must be android or ios.");
        if (req.Screen is { Length: > 20 }) return Validation.Problem("screen", "screen is max 20 chars.");
        if (req.UptimeS is < 0) return Validation.Problem("uptimeS", "uptimeS must be ≥ 0.");
        return FeedbackEndpoints.ValidateContact(req.Contact);
    }
}
