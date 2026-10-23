using Gimano.Api.Auth;
using Gimano.Api.Identity;
using Gimano.Api.Live;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Gimano.Core.Identity;
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Caching.Memory;

namespace Gimano.Api.Endpoints;

public sealed record AttestChallengeResponse(string Challenge);
public sealed record AttestRequest(string KeyId, string Attestation);

/// <summary>Pseudonymous identity with an Ed25519 key per pseudonym (docs/identity-and-reputation.md §1).</summary>
public static class IdentityEndpoints
{
    public static RouteGroupBuilder MapIdentity(this RouteGroupBuilder api)
    {
        api.MapPost("/auth/register", Register).WithTags("Auth").AllowAnonymous()
            .WithSummary("Register a pseudonym by its Ed25519 public key; returns userId, displayName and a JWT.");
        api.MapPost("/auth/token", Token).WithTags("Auth").AllowAnonymous()
            .WithSummary("Signature login: {userId, ts, sig} or, for recovery-code restores, {publicKey, ts, sig}.");
        var me = api.MapGroup("/me").WithTags("Auth").RequireAuthorization();
        me.MapPost("/keys", AttachKey).WithSummary("Attach (legacy accounts: JWT only) or rotate (sigByOldKey) the signing key.");
        me.MapGet("/attest/challenge", Challenge).WithSummary("iOS App Attest: a one-time challenge for POST /me/attest.");
        me.MapPost("/attest", Attest).WithSummary("iOS App Attest: attest the app's key once; votes then carry assertions.");
        return api;
    }

    private static async Task<Results<Ok<DeviceAuthResponse>, ValidationProblem, Conflict>> Register(
        RegisterRequest req, GimanoDbContext db, JwtTokenService jwt, MoodCache moods, TimeProvider clock, CancellationToken ct)
    {
        if (!Signatures.TryBase64(req.PublicKey, out var pk) || pk.Length != 32)
            return Validation.Problem("publicKey", "publicKey must be a base64 32-byte Ed25519 public key.");
        if (await db.UserKeys.AnyAsync(k => k.PublicKey == pk, ct)) return TypedResults.Conflict();
        var now = clock.GetUtcNow();
        var user = new User
        {
            Platform = req.Platform, Locale = string.IsNullOrWhiteSpace(req.Locale) ? "ms-MY" : req.Locale,
            DisplayName = AuthAndMeEndpoints.RandomDisplayName(), JoinedAt = now, LastSeenAt = now,
        };
        db.Users.Add(user);
        db.UserKeys.Add(new UserKey { UserId = user.Id, PublicKey = pk, CreatedAt = now });
        await db.SaveChangesAsync(ct);
        moods.Set(user.Id, user.Mood);
        return TypedResults.Ok(new DeviceAuthResponse(jwt.Issue(user.Id, user.DisplayName, user.Mood), user.Id, user.DisplayName));
    }

    public const int KeyLoginsPerMinutePerIp = 10;

    /// <summary>
    /// Signature login. {userId, ts, sig}: sig over "GIMANO-AUTH\n"+userId+"\n"+ts.
    /// {publicKey, ts, sig}: sig over "GIMANO1-LOGIN\n"+publicKey+"\n"+ts — resolves the account that owns this (current) key,
    /// for recovery-code restores where only the seed is known. ±300 s, replay-protected, key logins rate-limited per IP.
    /// </summary>
    private static async Task<Results<Ok<TokenResponse>, ProblemHttpResult>> Token(
        TokenRequest req, HttpContext http, GimanoDbContext db, JwtTokenService jwt, ReplayCache replay,
        Microsoft.Extensions.Caching.Memory.IMemoryCache cache, IConfiguration config, TimeProvider clock, CancellationToken ct)
    {
        var unauthorized = TypedResults.Problem(statusCode: 401, title: "Signature login failed", type: SignatureMiddleware.ProblemBase + "auth-failed");
        if (!Signatures.InWindow(req.Ts, clock.GetUtcNow()) || !Signatures.TryBase64(req.Sig, out var sig)) return unauthorized;

        User? user;
        if (req.UserId is { } userId)
        {
            user = await db.Users.FirstOrDefaultAsync(u => u.Id == userId, ct);
            if (user is null) return unauthorized;
            var key = await db.UserKeys.Where(k => k.UserId == userId && k.RevokedAt == null).OrderByDescending(k => k.Id).Select(k => k.PublicKey).FirstOrDefaultAsync(ct);
            if (key is null || !Signatures.Verify(key, Signatures.AuthCanonical(userId, req.Ts), sig)) return unauthorized;
        }
        else if (!string.IsNullOrWhiteSpace(req.PublicKey))
        {
            var ip = EdgeSetup.ClientKey(http, EdgeSetup.TrustedProxies(config));
            var bucket = cache.GetOrCreate("keylogin:" + ip, e => { e.AbsoluteExpirationRelativeToNow = TimeSpan.FromMinutes(1); return new int[1]; })!;
            if (Interlocked.Increment(ref bucket[0]) > KeyLoginsPerMinutePerIp)
            {
                http.Response.Headers.RetryAfter = "60";
                return TypedResults.Problem(statusCode: 429, title: "Too many sign-in attempts", type: SignatureMiddleware.ProblemBase + "rate-limited");
            }
            if (!Signatures.TryBase64(req.PublicKey, out var pk) || pk.Length != 32) return unauthorized;
            if (!Signatures.Verify(pk, Signatures.KeyLoginCanonical(req.PublicKey.Trim(), req.Ts), sig)) return unauthorized;
            var owner = await db.UserKeys.Where(k => k.PublicKey == pk && k.RevokedAt == null).Select(k => k.UserId).FirstOrDefaultAsync(ct);
            user = owner == Guid.Empty ? null : await db.Users.FirstOrDefaultAsync(u => u.Id == owner, ct);
            if (user is null) return unauthorized;
        }
        else return unauthorized;

        if (!replay.TryAdd($"auth:{req.Ts}:{req.Sig}")) return unauthorized;
        user.LastSeenAt = clock.GetUtcNow();
        await db.SaveChangesAsync(ct);
        return TypedResults.Ok(new TokenResponse(jwt.Issue(user.Id, user.DisplayName, user.Mood), user.Id, user.DisplayName));
    }

    private static async Task<Results<NoContent, ValidationProblem, ProblemHttpResult, Conflict>> AttachKey(
        AttachKeyRequest req, HttpContext http, GimanoDbContext db, UserKeyCache cache, TimeProvider clock, CancellationToken ct)
    {
        var uid = http.User.RequiredUserId();
        if (!Signatures.TryBase64(req.PublicKey, out var pk) || pk.Length != 32)
            return Validation.Problem("publicKey", "publicKey must be a base64 32-byte Ed25519 public key.");
        if (await db.UserKeys.AnyAsync(k => k.PublicKey == pk, ct)) return TypedResults.Conflict();
        var current = await db.UserKeys.Where(k => k.UserId == uid && k.RevokedAt == null).OrderByDescending(k => k.Id).FirstOrDefaultAsync(ct);
        if (current is not null)
        {
            // Rotation must be signed by the current key; a JWT alone can't replace a pseudonym's key.
            if (!Signatures.TryBase64(req.SigByOldKey, out var sig)
                || !Signatures.Verify(current.PublicKey, Signatures.KeyRotationCanonical(uid, req.PublicKey.Trim()), sig))
                return TypedResults.Problem(statusCode: 401, title: "Key rotation needs sigByOldKey", type: SignatureMiddleware.ProblemBase + "rotation-unsigned",
                    detail: "sigByOldKey = Ed25519(currentKey, \"GIMANO-KEY\\n\" + userId + \"\\n\" + publicKey).");
            current.RevokedAt = clock.GetUtcNow();
        }
        db.UserKeys.Add(new UserKey { UserId = uid, PublicKey = pk, CreatedAt = clock.GetUtcNow() });
        await db.SaveChangesAsync(ct);
        await db.InboxItems.Where(i => i.UserId == uid && i.AggregateKey == Gimano.Core.Community.InboxRules.LegacyUpdateKey).ExecuteDeleteAsync(ct);
        cache.Invalidate(uid);
        return TypedResults.NoContent();
    }

    private static Ok<AttestChallengeResponse> Challenge(HttpContext http, AttestationService att) =>
        TypedResults.Ok(new AttestChallengeResponse(Convert.ToBase64String(att.NewChallenge(http.User.RequiredUserId()))));

    private static async Task<Results<NoContent, ProblemHttpResult>> Attest(AttestRequest req, HttpContext http, AttestationService att, CancellationToken ct)
    {
        try
        {
            await att.AttestIosKeyAsync(http.User.RequiredUserId(), req.KeyId, req.Attestation, ct);
            return TypedResults.NoContent();
        }
        catch (Exception ex) when (ex is System.Security.Cryptography.CryptographicException or FormatException or System.Formats.Cbor.CborContentException)
        {
            return TypedResults.Problem(statusCode: 400, title: "Attestation rejected", detail: ex.Message, type: SignatureMiddleware.ProblemBase + "attestation-invalid");
        }
    }
}
