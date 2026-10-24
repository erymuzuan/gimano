using Gimano.Api.Auth;
using Gimano.Core.Events;

namespace Gimano.Api.Endpoints;

public sealed record CreateDropResponse(string DropId, string WriteToken, DateTimeOffset ExpiresAt);

/// <summary>
/// "Event short links" (docs/api.md, docs/events.md §5.6): an end-to-end encrypted event pack kept until the event is over.
/// The key never reaches the server. Bodies, drop ids and client IPs are never logged on these routes.
/// </summary>
public static class DropEndpoints
{
    public const string ExpiresHeader = "X-Drop-Expires";
    private const string OctetStream = "application/octet-stream";

    public static RouteGroupBuilder MapDrops(this RouteGroupBuilder api)
    {
        var g = api.MapGroup("/drops").WithTags("Event short links");
        g.MapPost("", Create).RequireAuthorization().Accepts<byte[]>(OctetStream);
        g.MapPut("/{dropId}", Put).AllowAnonymous().Accepts<byte[]>(OctetStream);
        g.MapGet("/{dropId}", Get).AllowAnonymous().RequireCors(EdgeSetup.DropsCorsPolicy).RequireRateLimiting(EdgeSetup.DropsReadLimit);
        g.MapDelete("/{dropId}", Delete).AllowAnonymous();
        return api;
    }

    private static async Task<IResult> Create(HttpContext http, DropService drops, CancellationToken ct)
    {
        var (status, blob) = await ReadBlobAsync(http.Request, ct);
        if (blob is null) return status!;
        var (r, created) = await drops.CreateAsync(http.User.RequiredUserId(), blob, http.Request.Headers[ExpiresHeader].ToString(), ct);
        if (r != DropService.Result.Ok) return Map(r);
        NoStore(http);
        return TypedResults.Created($"/api/v1/drops/{created!.DropId}", new CreateDropResponse(created.DropId, created.WriteToken, created.ExpiresAt));
    }

    private static async Task<IResult> Put(string dropId, HttpContext http, DropService drops, CancellationToken ct)
    {
        var (status, blob) = await ReadBlobAsync(http.Request, ct);
        if (blob is null) return status!;
        var (r, _) = await drops.PutAsync(dropId, Token(http), blob, http.Request.Headers[ExpiresHeader].ToString(), ct);
        return r == DropService.Result.Ok ? TypedResults.NoContent() : Map(r);
    }

    internal static async Task<IResult> Get(string dropId, HttpContext http, DropService drops, CancellationToken ct)
    {
        NoStore(http);
        var (r, blob) = await drops.GetAsync(dropId, ct);
        return r == DropService.Result.Ok ? TypedResults.Bytes(blob!, OctetStream) : Map(r);
    }

    private static async Task<IResult> Delete(string dropId, HttpContext http, DropService drops, CancellationToken ct)
    {
        var r = await drops.DeleteAsync(dropId, Token(http), ct);
        return r == DropService.Result.Ok ? TypedResults.NoContent() : Map(r);
    }

    private static void NoStore(HttpContext http) => http.Response.Headers.CacheControl = "no-store";

    internal static IResult Map(DropService.Result r) => r switch
    {
        DropService.Result.NotFound => TypedResults.Problem(statusCode: 404, title: "Unknown link"),
        DropService.Result.Gone => TypedResults.Problem(statusCode: 410, title: "This link has ended"),
        DropService.Result.Unauthorized => TypedResults.Problem(statusCode: 401, title: "Invalid link token"),
        DropService.Result.TooMany => TypedResults.Problem(statusCode: 429, title: "Too many active links",
            detail: $"At most {DropService.MaxLivePerUser} active event links; end one first."),
        DropService.Result.TooLarge => TypedResults.Problem(statusCode: 413, title: "Too large", detail: $"The pack must be ≤ {DropService.MaxBlobBytes} bytes."),
        DropService.Result.InvalidExpiry => Validation.Problem(ExpiresHeader, $"{ExpiresHeader} must be an ISO-8601 date-time."),
        _ => Validation.Problem("body", $"The body must be nonce (24 bytes) ‖ ciphertext with its 16-byte tag (≥ {DropService.MinBlobBytes} bytes)."),
    };

    /// <summary>Reads the raw body up to the limit (+1 byte to detect overflow). Returns the error result when it can't be used.</summary>
    internal static async Task<(IResult?, byte[]?)> ReadBlobAsync(HttpRequest req, CancellationToken ct)
    {
        if (!string.IsNullOrEmpty(req.ContentType) && !req.ContentType.StartsWith(OctetStream, StringComparison.OrdinalIgnoreCase))
            return (TypedResults.Problem(statusCode: 415, title: "Unsupported media type", detail: $"Send the pack as {OctetStream}."), null);
        if (req.ContentLength > DropService.MaxBlobBytes) return (Map(DropService.Result.TooLarge), null);
        var buffer = new byte[DropService.MaxBlobBytes + 1];
        var total = 0;
        int n;
        while (total < buffer.Length && (n = await req.Body.ReadAsync(buffer.AsMemory(total), ct)) > 0) total += n;
        if (total > DropService.MaxBlobBytes) return (Map(DropService.Result.TooLarge), null);
        return (null, buffer[..total]);
    }

    /// <summary><c>Authorization: Drop &lt;writeToken&gt;</c></summary>
    internal static string? Token(HttpContext http)
    {
        var h = http.Request.Headers.Authorization.ToString();
        return h.StartsWith("Drop ", StringComparison.Ordinal) ? h[5..].Trim() : null;
    }

    /// <summary>Request path safe for logs: the drop id is replaced (it is half of a guest's link).</summary>
    public static string LogSafePath(PathString path) =>
        path.StartsWithSegments("/api/v1/drops", out var rest) && rest.HasValue && rest.Value!.Length > 1 ? "/api/v1/drops/{dropId}" : path.ToString();
}
