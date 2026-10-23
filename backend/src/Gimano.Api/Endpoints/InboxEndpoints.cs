using System.Globalization;
using Gimano.Api.Auth;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Api.Endpoints;

/// <summary>News about the user's own public contributions and feedback replies (docs/api.md "Inbox"). Kept 90 days.</summary>
public static class InboxEndpoints
{
    public const int PageSize = 30;

    public static RouteGroupBuilder MapInbox(this RouteGroupBuilder api)
    {
        var me = api.MapGroup("/me/inbox").WithTags("Inbox").RequireAuthorization();
        me.MapGet("", List).WithSummary("Newest first, 30 per page; pass next as cursor.");
        me.MapPost("/read", MarkRead).WithSummary("Everything created at or before upTo is read. The mark never moves back.");
        return api;
    }

    private static async Task<Ok<InboxPage>> List(string? cursor, HttpContext http, GimanoDbContext db, CancellationToken ct)
    {
        var uid = http.User.RequiredUserId();
        var readUpTo = await db.Users.Where(u => u.Id == uid).Select(u => u.InboxReadUpTo).FirstOrDefaultAsync(ct);
        var q = db.InboxItems.AsNoTracking().Where(i => i.UserId == uid);
        if (cursor is not null && DateTimeOffset.TryParse(cursor, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var before))
            q = q.Where(i => i.CreatedAt < before);
        var rows = await q.OrderByDescending(i => i.CreatedAt).Take(PageSize + 1).ToListAsync(ct);
        var unread = await db.InboxItems.CountAsync(i => i.UserId == uid && (readUpTo == null || i.CreatedAt > readUpTo), ct);
        var items = rows.Take(PageSize).Select(i => new InboxItemDto(i.Id, i.Kind, i.Title, i.Body, i.RefType, i.RefId, i.CreatedAt,
            readUpTo is { } r && i.CreatedAt <= r, i.Count)).ToList();
        var next = rows.Count > PageSize ? items[^1].CreatedAt.ToString("O", CultureInfo.InvariantCulture) : null;
        return TypedResults.Ok(new InboxPage(items, next, unread));
    }

    private static async Task<NoContent> MarkRead(InboxReadRequest req, HttpContext http, GimanoDbContext db, TimeProvider clock, CancellationToken ct)
    {
        var uid = http.User.RequiredUserId();
        var upTo = req.UpTo > clock.GetUtcNow() ? clock.GetUtcNow() : req.UpTo;
        await db.Users.Where(u => u.Id == uid && (u.InboxReadUpTo == null || u.InboxReadUpTo < upTo))
            .ExecuteUpdateAsync(s => s.SetProperty(u => u.InboxReadUpTo, upTo), ct);
        return TypedResults.NoContent();
    }
}
