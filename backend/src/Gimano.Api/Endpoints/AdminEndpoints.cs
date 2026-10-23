using System.Security.Cryptography;
using System.Text;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Gimano.Core.Messaging;
using Gimano.Core.Support;
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Api.Endpoints;

public sealed record AdminFeedbackItem(Guid Id, FeedbackCategory Category, string Message, int? Rating, FeedbackScreen? Screen, FeedbackStatus Status,
    DeviceInfo? Device, double? Lat, double? Lng, string? RouteId, string? PlaceId, bool HasScreenshot, bool HasContact, DateTimeOffset CreatedAt, string? ExternalUrl);
public sealed record AdminCrashGroup(string Signature, string Exception, int Count, DateTimeOffset FirstSeen, DateTimeOffset LastSeen, CrashGroupStatus Status);
public sealed record AdminCrashReport(Guid Id, CrashKind Kind, string Exception, string? Message, string? Stack, string? Screen, long? UptimeS,
    DeviceInfo? Device, DateTimeOffset? OccurredAt, bool HasContact);
public sealed record AdminStatusRequest(string Status);
public sealed record AdminSearchMiss(string Query, string Cell, string Week, int Count);

/// <summary>
/// Minimal maintainer view, protected by an admin key (X-Admin-Key = Admin:Key; disabled when not configured).
/// Contact details are decrypted ONLY on an explicit per-item reveal, which is audit-logged without the values.
/// </summary>
public static class AdminEndpoints
{
    public static RouteGroupBuilder MapAdmin(this RouteGroupBuilder api)
    {
        var g = api.MapGroup("/admin").WithTags("Admin").AllowAnonymous().AddEndpointFilter<AdminKeyFilter>().ExcludeFromDescription();
        g.MapGet("/feedback", ListFeedback);
        g.MapPatch("/feedback/{id:guid}", SetFeedbackStatus);
        g.MapGet("/feedback/{id:guid}/screenshot", Screenshot);
        g.MapGet("/crash-groups", ListCrashGroups);
        g.MapGet("/crash-groups/{signature}/reports", CrashReports);
        g.MapPatch("/crash-groups/{signature}", SetCrashStatus);
        g.MapPost("/contacts/{subject}/{id:guid}/reveal", RevealContact);
        g.MapGet("/search-misses", SearchMissReport);
        g.MapPost("/search/aliases/reload", (Services.AliasStore store) => TypedResults.Ok(new { rows = store.Reload() }));
        return api;
    }

    /// <summary>Anonymous zero-result counts: query, ~11 km cell, ISO week, count (nothing else exists to show).</summary>
    private static async Task<Ok<List<AdminSearchMiss>>> SearchMissReport(string? week, int? minCount, int? take, GimanoDbContext db, TimeProvider clock, CancellationToken ct)
    {
        var w = string.IsNullOrEmpty(week) ? Core.Support.SearchMisses.Week(clock.GetUtcNow()) : week;
        var min = Math.Max(1, minCount ?? 1);
        var rows = await db.SearchMisses.AsNoTracking().Where(m => m.Week == w && m.Count >= min)
            .OrderByDescending(m => m.Count).ThenBy(m => m.Query).Take(Math.Clamp(take ?? 200, 1, 1000))
            .Select(m => new AdminSearchMiss(m.Query, m.Cell, m.Week, m.Count)).ToListAsync(ct);
        return TypedResults.Ok(rows);
    }

    private static async Task<Ok<List<AdminFeedbackItem>>> ListFeedback(string? status, int? take, GimanoDbContext db, CancellationToken ct)
    {
        var q = db.Feedback.AsNoTracking();
        if (GimanoJson.TryParseWire<FeedbackStatus>(status, out var st)) q = q.Where(f => f.Status == st);
        var rows = await q.OrderByDescending(f => f.CreatedAt).Take(Math.Clamp(take ?? 50, 1, 200))
            .Select(f => new AdminFeedbackItem(f.Id, f.Category, f.Message, f.Rating, f.Screen, f.Status, f.Device, f.Lat, f.Lng, f.RouteId, f.PlaceId,
                f.Screenshot != null, db.ReportContacts.Any(c => c.SubjectType == ContactSubject.Feedback && c.SubjectId == f.Id), f.CreatedAt, f.ExternalUrl))
            .ToListAsync(ct);
        return TypedResults.Ok(rows);
    }

    private static async Task<Results<NoContent, NotFound, ValidationProblem>> SetFeedbackStatus(Guid id, AdminStatusRequest req, GimanoDbContext db,
        IEventPublisher bus, TimeProvider clock, CancellationToken ct)
    {
        if (!GimanoJson.TryParseWire<FeedbackStatus>(req.Status, out var status)) return Validation.Problem("status", "received|triaged|planned|done|wont_fix");
        var f = await db.Feedback.FirstOrDefaultAsync(x => x.Id == id, ct);
        if (f is null) return TypedResults.NotFound();
        f.Status = status;
        f.ClosedAt = status is FeedbackStatus.Done or FeedbackStatus.WontFix ? f.ClosedAt ?? clock.GetUtcNow() : null;
        await db.SaveChangesAsync(ct);
        await bus.PublishAsync(RoutingKeys.FeedbackStatusChanged, new FeedbackStatusEvent(id, status), ct);
        return TypedResults.NoContent();
    }

    private static async Task<IResult> Screenshot(Guid id, GimanoDbContext db, CancellationToken ct)
    {
        var f = await db.Feedback.AsNoTracking().Where(x => x.Id == id).Select(x => new { x.Screenshot, x.ScreenshotContentType }).FirstOrDefaultAsync(ct);
        return f?.Screenshot is null ? Results.NotFound() : Results.File(f.Screenshot, f.ScreenshotContentType ?? "application/octet-stream");
    }

    private static async Task<Ok<List<AdminCrashGroup>>> ListCrashGroups(string? status, GimanoDbContext db, CancellationToken ct)
    {
        var q = db.CrashGroups.AsNoTracking();
        if (GimanoJson.TryParseWire<CrashGroupStatus>(status, out var st)) q = q.Where(g => g.Status == st);
        return TypedResults.Ok(await q.OrderByDescending(g => g.LastSeen).Take(200)
            .Select(g => new AdminCrashGroup(g.Signature, g.Exception, g.Count, g.FirstSeen, g.LastSeen, g.Status)).ToListAsync(ct));
    }

    private static async Task<Ok<List<AdminCrashReport>>> CrashReports(string signature, GimanoDbContext db, CancellationToken ct) =>
        TypedResults.Ok(await db.CrashReports.AsNoTracking().Where(c => c.Signature == signature).OrderByDescending(c => c.CreatedAt).Take(50)
            .Select(c => new AdminCrashReport(c.Id, c.Kind, c.Exception, c.Message, c.Stack, c.Screen, c.UptimeS, c.Device, c.OccurredAt,
                db.ReportContacts.Any(x => x.SubjectType == ContactSubject.Crash && x.SubjectId == c.Id)))
            .ToListAsync(ct));

    private static async Task<Results<NoContent, NotFound, ValidationProblem>> SetCrashStatus(string signature, AdminStatusRequest req, GimanoDbContext db,
        TimeProvider clock, CancellationToken ct)
    {
        if (!GimanoJson.TryParseWire<CrashGroupStatus>(req.Status, out var status)) return Validation.Problem("status", "open|done|wont_fix");
        var g = await db.CrashGroups.FirstOrDefaultAsync(x => x.Signature == signature, ct);
        if (g is null) return TypedResults.NotFound();
        g.Status = status;
        g.ClosedAt = status == CrashGroupStatus.Open ? null : g.ClosedAt ?? clock.GetUtcNow();
        await db.SaveChangesAsync(ct);
        return TypedResults.NoContent();
    }

    /// <summary>Explicit per-item reveal. The audit log records who/what/when, never the contact values.</summary>
    private static async Task<Results<Ok<ContactDetails>, NotFound, ValidationProblem>> RevealContact(string subject, Guid id, HttpContext http,
        GimanoDbContext db, ContactCrypto crypto, ILogger<AdminKeyFilter> log, CancellationToken ct)
    {
        if (!GimanoJson.TryParseWire<ContactSubject>(subject, out var type)) return Validation.Problem("subject", "feedback|crash");
        var c = await db.ReportContacts.AsNoTracking().FirstOrDefaultAsync(x => x.SubjectType == type && x.SubjectId == id, ct);
        if (c is null) return TypedResults.NotFound();
        log.LogWarning("AUDIT contact revealed: {Subject} {Id} by admin key {KeyFingerprint} from {Ip}",
            type, id, AdminKeyFilter.Fingerprint(http), http.Connection.RemoteIpAddress);
        return TypedResults.Ok(crypto.Decrypt(c));
    }
}

public sealed class AdminKeyFilter(IConfiguration config) : IEndpointFilter
{
    public async ValueTask<object?> InvokeAsync(EndpointFilterInvocationContext context, EndpointFilterDelegate next)
    {
        var expected = config["Admin:Key"];
        if (string.IsNullOrEmpty(expected) || expected.Length < 24) return Results.NotFound(); // disabled
        var given = context.HttpContext.Request.Headers["X-Admin-Key"].ToString();
        var a = SHA256.HashData(Encoding.UTF8.GetBytes(given));
        var b = SHA256.HashData(Encoding.UTF8.GetBytes(expected));
        if (!CryptographicOperations.FixedTimeEquals(a, b)) return Results.Unauthorized();
        return await next(context);
    }

    /// <summary>Short, non-reversible fingerprint of the admin key used (for the audit log).</summary>
    public static string Fingerprint(HttpContext http) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(http.Request.Headers["X-Admin-Key"].ToString())))[..8];
}
