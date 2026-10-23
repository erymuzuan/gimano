using System.Security.Claims;
using Gimano.Api.Auth;
using Gimano.Core.Data;
using Gimano.Core.Identity;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Caching.Memory;

namespace Gimano.Api.Identity;

/// <summary>Marks an endpoint as requiring <c>X-Gimano-Ts</c> / <c>X-Gimano-Sig</c> (docs/identity-and-reputation.md §2).</summary>
public sealed class RequireSignatureMetadata;

public static class SignatureEndpointExtensions
{
    public static TBuilder RequireSignature<TBuilder>(this TBuilder b) where TBuilder : IEndpointConventionBuilder =>
        b.WithMetadata(new RequireSignatureMetadata());
}

/// <summary>Current public keys per user (5-minute cache, invalidated on attach/rotate).</summary>
public sealed class UserKeyCache(IMemoryCache cache, IServiceScopeFactory scopes)
{
    public async Task<byte[]?> CurrentAsync(Guid userId, CancellationToken ct) =>
        await cache.GetOrCreateAsync("ukey:" + userId, async e =>
        {
            e.AbsoluteExpirationRelativeToNow = TimeSpan.FromMinutes(5);
            await using var scope = scopes.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<GimanoDbContext>();
            return await db.UserKeys.AsNoTracking().Where(k => k.UserId == userId && k.RevokedAt == null)
                .OrderByDescending(k => k.Id).Select(k => k.PublicKey).FirstOrDefaultAsync(ct);
        });

    public void Invalidate(Guid userId) => cache.Remove("ukey:" + userId);
}

/// <summary>10-minute replay cache of (userId, ts, sig).</summary>
public sealed class ReplayCache(IMemoryCache cache)
{
    public static readonly TimeSpan Window = TimeSpan.FromMinutes(10);

    /// <summary>True if first seen (and remembers it); false if it's a replay.</summary>
    public bool TryAdd(string key)
    {
        var k = "replay:" + key;
        if (cache.TryGetValue(k, out _)) return false;
        cache.Set(k, true, Window);
        return true;
    }
}

public enum SignatureDecision { Verify, AllowUnsigned, RejectNoKey }

/// <summary>
/// Per-account enforcement (docs/identity-and-reputation.md §2):
/// keyed accounts must always sign; key-less legacy accounts may post unsigned while Identity:AllowLegacyUnsigned
/// (default true) unless the kill switch Identity:RequireSignatures=true rejects every unsigned request.
/// </summary>
public static class SignaturePolicy
{
    public static SignatureDecision Decide(bool hasKey, bool requireAll, bool allowLegacyUnsigned) =>
        hasKey ? SignatureDecision.Verify
        : requireAll || !allowLegacyUnsigned ? SignatureDecision.RejectNoKey
        : SignatureDecision.AllowUnsigned;
}

/// <summary>
/// Verifies the Ed25519 request signature on endpoints marked with <see cref="RequireSignatureMetadata"/>, per
/// <see cref="SignaturePolicy"/>. Runs after authentication (needs the user id from the JWT).
/// </summary>
public sealed class SignatureMiddleware(RequestDelegate next, IConfiguration config, TimeProvider clock,
    UserKeyCache keys, ReplayCache replay, IProblemDetailsService problems)
{
    public const string ProblemBase = "https://gimano.bukitbunga.com/problems/";
    /// <summary>Kill switch: reject every unsigned request, legacy accounts included.</summary>
    private readonly bool _requireAll = config.GetValue("Identity:RequireSignatures", false);
    private readonly bool _allowLegacy = config.GetValue("Identity:AllowLegacyUnsigned", true);

    public async Task InvokeAsync(HttpContext ctx)
    {
        if (ctx.GetEndpoint()?.Metadata.GetMetadata<RequireSignatureMetadata>() is null || ctx.User.UserId() is not { } uid)
        {
            await next(ctx);
            return;
        }
        var key = await keys.CurrentAsync(uid, ctx.RequestAborted);
        bool ok; string type, detail;
        switch (SignaturePolicy.Decide(key is not null, _requireAll, _allowLegacy))
        {
            case SignatureDecision.AllowUnsigned:
                ctx.Items[LegacyUnsignedItem] = true;
                await next(ctx);
                return;
            case SignatureDecision.RejectNoKey:
                (ok, type, detail) = (false, "no-key", "Signed requests are required: update Gimano, then attach a key with POST /api/v1/me/keys.");
                break;
            default:
                (ok, type, detail) = await VerifyAsync(ctx, uid, key!);
                break;
        }
        if (ok) { await next(ctx); return; }

        ctx.Response.StatusCode = StatusCodes.Status401Unauthorized;
        await problems.WriteAsync(new ProblemDetailsContext
        {
            HttpContext = ctx,
            ProblemDetails = new ProblemDetails { Status = 401, Type = ProblemBase + type, Title = "Request signature required", Detail = detail },
        });
    }

    public const string LegacyUnsignedItem = "gimano.legacy-unsigned";

    private async Task<(bool, string, string)> VerifyAsync(HttpContext ctx, Guid uid, byte[] key)
    {
        var tsHeader = ctx.Request.Headers["X-Gimano-Ts"].ToString();
        var sigHeader = ctx.Request.Headers["X-Gimano-Sig"].ToString();
        if (tsHeader.Length == 0 || sigHeader.Length == 0)
            return (false, "signature-missing", "This endpoint needs X-Gimano-Ts and X-Gimano-Sig (see docs/identity-and-reputation.md).");
        if (!long.TryParse(tsHeader, out var ts) || !Signatures.InWindow(ts, clock.GetUtcNow()))
            return (false, "signature-expired", "X-Gimano-Ts must be unix seconds within ±300 s of server time.");
        if (!Signatures.TryBase64(sigHeader, out var sig) || sig.Length != 64)
            return (false, "signature-invalid", "X-Gimano-Sig must be a base64 Ed25519 signature.");
        ctx.Request.EnableBuffering();
        byte[] body;
        using (var ms = new MemoryStream())
        {
            await ctx.Request.Body.CopyToAsync(ms, ctx.RequestAborted);
            body = ms.ToArray();
        }
        ctx.Request.Body.Position = 0;

        var canonical = Signatures.RequestCanonical(ctx.Request.Method, PathAndQuery(ctx), ts, body);
        if (!Signatures.Verify(key, canonical, sig))
            return (false, "signature-invalid", "The signature doesn't match the request (method, path+query, ts, sha256(body)) or the current key.");
        if (!replay.TryAdd($"{uid:N}:{ts}:{sigHeader}"))
            return (false, "signature-replayed", "This signed request was already used.");
        return (true, "", "");
    }

    /// <summary>The request target exactly as sent (path + query), e.g. "/api/v1/places/c1/verify?x=1".</summary>
    internal static string PathAndQuery(HttpContext ctx) =>
        ctx.Features.Get<IHttpRequestFeature>()?.RawTarget is { Length: > 0 } raw && raw.StartsWith('/')
            ? raw
            : ctx.Request.PathBase + ctx.Request.Path + ctx.Request.QueryString;
}
