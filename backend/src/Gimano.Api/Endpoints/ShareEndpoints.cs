using Gimano.Api.Auth;
using Gimano.Api.Live;
using Microsoft.AspNetCore.Http.HttpResults;

namespace Gimano.Api.Endpoints;

public sealed record CreateShareRequest(int ExpiresInMinutes);
public sealed record CreateShareResponse(string ShareId, string WriteToken, DateTimeOffset ExpiresAt);
public sealed record ShareBlobRequest(string Nonce, string Ciphertext);
public sealed record ShareBlobResponse(string? Nonce, string? Ciphertext, DateTimeOffset? UpdatedAt, DateTimeOffset ExpiresAt);

/// <summary>"Share my drive" (docs/api.md): an in-memory relay for end-to-end encrypted position blobs.</summary>
public static class ShareEndpoints
{
    public static RouteGroupBuilder MapShares(this RouteGroupBuilder api)
    {
        var g = api.MapGroup("/shares").WithTags("Share my drive");
        g.MapPost("", Create).RequireAuthorization();
        g.MapPut("/{shareId}", Put).AllowAnonymous().WithMetadata(new Microsoft.AspNetCore.Mvc.RequestSizeLimitAttribute(4096));
        g.MapGet("/{shareId}", Get).AllowAnonymous().RequireCors(EdgeSetup.SharesCorsPolicy);
        g.MapDelete("/{shareId}", Delete).AllowAnonymous();
        return api;
    }

    private static Results<Created<CreateShareResponse>, ValidationProblem, ProblemHttpResult> Create(CreateShareRequest req, HttpContext http, ShareStore store)
    {
        var (r, created) = store.Create(http.User.RequiredUserId(), req.ExpiresInMinutes);
        return r switch
        {
            ShareStore.Result.Invalid => Validation.Problem("expiresInMinutes", $"expiresInMinutes must be {ShareStore.MinMinutes}–{ShareStore.MaxMinutes}."),
            ShareStore.Result.TooMany => TypedResults.Problem(statusCode: 429, title: "Too many active shares", detail: $"At most {ShareStore.MaxActivePerUser} active shares; stop one first."),
            _ => TypedResults.Created($"/api/v1/shares/{created!.ShareId}", new CreateShareResponse(created.ShareId, created.WriteToken, created.ExpiresAt)),
        };
    }

    private static IResult Put(string shareId, ShareBlobRequest req, HttpContext http, ShareStore store)
    {
        if (!TryB64(req.Nonce, out var nonce) || !TryB64(req.Ciphertext, out var ct))
            return TypedResults.ValidationProblem(new Dictionary<string, string[]> { ["ciphertext"] = ["nonce and ciphertext must be base64."] });
        return Map(store.Put(shareId, Token(http), nonce, ct), TypedResults.NoContent());
    }

    private static IResult Get(string shareId, ShareStore store)
    {
        var (r, blob) = store.Get(shareId);
        if (r != ShareStore.Result.Ok) return Map(r, null!);
        return TypedResults.Ok(new ShareBlobResponse(
            blob!.Nonce is null ? null : Convert.ToBase64String(blob.Nonce),
            blob.Ciphertext is null ? null : Convert.ToBase64String(blob.Ciphertext),
            blob.UpdatedAt, blob.ExpiresAt));
    }

    private static IResult Delete(string shareId, HttpContext http, ShareStore store) =>
        Map(store.Delete(shareId, Token(http)), TypedResults.NoContent());

    private static IResult Map(ShareStore.Result r, IResult ok) => r switch
    {
        ShareStore.Result.Ok => ok,
        ShareStore.Result.NotFound => TypedResults.Problem(statusCode: 404, title: "Unknown share"),
        ShareStore.Result.Gone => TypedResults.Problem(statusCode: 410, title: "This share has ended"),
        ShareStore.Result.Unauthorized => TypedResults.Problem(statusCode: 401, title: "Invalid share token"),
        ShareStore.Result.TooLarge => TypedResults.Problem(statusCode: 413, title: "Blob too large", detail: $"ciphertext ≤ {ShareStore.MaxCiphertextBytes} bytes."),
        _ => TypedResults.Problem(statusCode: 400, title: "Invalid blob", detail: $"nonce must be {ShareStore.NonceBytes} bytes; ciphertext must include the 16-byte tag."),
    };

    /// <summary><c>Authorization: Share &lt;writeToken&gt;</c></summary>
    internal static string? Token(HttpContext http)
    {
        var h = http.Request.Headers.Authorization.ToString();
        return h.StartsWith("Share ", StringComparison.Ordinal) ? h[6..].Trim() : null;
    }

    private static bool TryB64(string? s, out byte[] bytes)
    {
        bytes = [];
        if (string.IsNullOrEmpty(s) || s.Length > 4096) return false;
        var t = s.Replace('-', '+').Replace('_', '/');
        t += new string('=', (4 - t.Length % 4) % 4);
        try { bytes = Convert.FromBase64String(t); return true; } catch (FormatException) { return false; }
    }
}
