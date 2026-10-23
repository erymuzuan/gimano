using Gimano.Api.Auth;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Gimano.Core.Messaging;
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Api.Endpoints;

public static class FeedbackEndpoints
{
    public const int MaxMessageLength = 2000;
    public const int MaxScreenshotBytes = 1_500_000;
    public const int MaxPerHour = 10;

    public static RouteGroupBuilder MapFeedback(this RouteGroupBuilder api)
    {
        // Anonymous users have a device token too, so authorisation is still required.
        api.MapPost("/feedback", Create).WithTags("Feedback").RequireAuthorization()
            .WithSummary("Send feedback (optionally with a screenshot ≤ 1.5 MB). 413 if too big, 429 above 10/hour.");
        api.MapGet("/me/feedback", Mine).WithTags("Feedback").RequireAuthorization();
        return api;
    }

    private static async Task<Results<Created<FeedbackCreatedResponse>, ValidationProblem, ProblemHttpResult>> Create(
        CreateFeedbackRequest req, HttpContext http, GimanoDbContext db, IEventPublisher bus, TimeProvider clock, CancellationToken ct)
    {
        if (Validate(req) is { } problem) return problem;

        byte[]? screenshot = null;
        string? contentType = null;
        if (!string.IsNullOrWhiteSpace(req.Screenshot))
        {
            switch (DecodeScreenshot(req.Screenshot))
            {
                case { Error: ScreenshotError.TooLarge }:
                    return TypedResults.Problem(statusCode: StatusCodes.Status413PayloadTooLarge, title: "Screenshot too large",
                        detail: $"Screenshots are limited to {MaxScreenshotBytes / 1_000_000.0:0.#} MB after decoding.");
                case { Error: ScreenshotError.Invalid }:
                    return Validation.Problem("screenshot", "screenshot must be a base64 PNG or JPEG image.");
                case var ok:
                    (screenshot, contentType) = (ok.Bytes, ok.ContentType);
                    break;
            }
        }

        var uid = http.User.RequiredUserId();
        if (!await db.Users.AnyAsync(u => u.Id == uid, ct)) throw new UnauthorizedAccessException("This account was deleted.");
        var now = clock.GetUtcNow();
        var recent = await db.Feedback.CountAsync(f => f.UserId == uid && f.CreatedAt > now.AddHours(-1), ct);
        if (recent >= MaxPerHour)
        {
            http.Response.Headers.RetryAfter = "3600";
            return TypedResults.Problem(statusCode: StatusCodes.Status429TooManyRequests, title: "Too much feedback",
                detail: $"At most {MaxPerHour} feedback messages per hour. Terima kasih!");
        }

        var fb = new Feedback
        {
            UserId = uid, Category = req.Category, Message = req.Message.Trim(), Rating = req.Rating, Screen = req.Screen,
            Context = Scrub(req.Context), Screenshot = screenshot, ScreenshotContentType = contentType, CreatedAt = now,
        };
        db.Feedback.Add(fb);
        await db.SaveChangesAsync(ct);
        await bus.PublishAsync(RoutingKeys.FeedbackCreated, new FeedbackCreatedEvent(fb.Id, fb.Category, fb.CreatedAt), ct);
        return TypedResults.Created($"/api/v1/me/feedback/{fb.Id}", new FeedbackCreatedResponse(fb.Id, fb.CreatedAt));
    }

    private static async Task<Ok<List<FeedbackSummary>>> Mine(HttpContext http, GimanoDbContext db, CancellationToken ct)
    {
        var uid = http.User.RequiredUserId();
        var rows = await db.Feedback.AsNoTracking().Where(f => f.UserId == uid).OrderByDescending(f => f.CreatedAt).Take(100)
            .Select(f => new FeedbackSummary(f.Id, f.Category, f.Message, f.Status, f.CreatedAt)).ToListAsync(ct);
        return TypedResults.Ok(rows);
    }

    /// <summary>
    /// Privacy rule: location only at ≈ 1 km (2 dp). The app sends context only when "Include diagnostics" is ticked;
    /// nothing in it identifies the device.
    /// </summary>
    internal static FeedbackContext? Scrub(FeedbackContext? c) => c is null ? null : c with
    {
        Lat = c.Lat is { } lat ? Math.Round(lat, 2) : null,
        Lng = c.Lng is { } lng ? Math.Round(lng, 2) : null,
    };

    internal static ValidationProblem? Validate(CreateFeedbackRequest req)
    {
        if (string.IsNullOrWhiteSpace(req.Message) || req.Message.Trim().Length > MaxMessageLength)
            return Validation.Problem("message", $"message is required (1–{MaxMessageLength} chars).");
        if (req.Rating is < 1 or > 5) return Validation.Problem("rating", "rating must be 1–5.");
        if (req.Context is { } c)
        {
            if (c.Lat is { } lat && c.Lng is { } lng && !Validation.ValidLatLng(lat, lng)) return Validation.Problem("context.lat", "Invalid coordinates.");
            if (new[] { c.AppVersion, c.Platform, c.OsVersion, c.DeviceModel, c.Locale, c.RouteId, c.PlaceId }.Any(v => v is { Length: > 100 }))
                return Validation.Problem("context", "context fields are max 100 chars.");
        }
        return null;
    }

    internal enum ScreenshotError { None, Invalid, TooLarge }

    internal readonly record struct DecodedScreenshot(ScreenshotError Error, byte[]? Bytes, string? ContentType);

    /// <summary>Accepts raw base64 or a data: URL; PNG or JPEG only; ≤ 1.5 MB decoded.</summary>
    internal static DecodedScreenshot DecodeScreenshot(string input)
    {
        var b64 = input.Trim();
        var comma = b64.IndexOf(',');
        if (b64.StartsWith("data:", StringComparison.OrdinalIgnoreCase) && comma > 0) b64 = b64[(comma + 1)..];
        // Cheap size check before decoding: 4 base64 chars → 3 bytes.
        if ((long)b64.Length * 3 / 4 > MaxScreenshotBytes + 3) return new(ScreenshotError.TooLarge, null, null);

        var buffer = new byte[b64.Length * 3 / 4 + 3];
        if (!Convert.TryFromBase64String(b64, buffer, out var n)) return new(ScreenshotError.Invalid, null, null);
        if (n > MaxScreenshotBytes) return new(ScreenshotError.TooLarge, null, null);
        var bytes = buffer.AsSpan(0, n).ToArray();

        string? type = bytes switch
        {
            [0x89, 0x50, 0x4E, 0x47, ..] => "image/png",
            [0xFF, 0xD8, 0xFF, ..] => "image/jpeg",
            _ => null,
        };
        return type is null ? new(ScreenshotError.Invalid, null, null) : new(ScreenshotError.None, bytes, type);
    }
}
