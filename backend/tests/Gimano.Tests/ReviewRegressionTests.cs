using System.Security.Claims;
using System.Text;
using Gimano.Api.Identity;
using Gimano.Api.Services;
using Gimano.Core.Contracts;
using Gimano.Core.Domain;
using Gimano.Core.Geo;
using Gimano.Core.Identity;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Caching.Memory;
using Microsoft.Extensions.Configuration;

namespace Gimano.Tests;

/// <summary>Regression tests for the October 2026 backend review (one per fixed finding).</summary>
public class SignatureReplayRegressionTests
{
    private static readonly byte[] Seed = Convert.FromHexString("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");

    private sealed class Clock(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }

    private sealed class Problems : Microsoft.AspNetCore.Http.IProblemDetailsService
    {
        public string? LastType;
        public ValueTask WriteAsync(ProblemDetailsContext context) { LastType = context.ProblemDetails.Type; return ValueTask.CompletedTask; }
    }

    [Fact]
    public async Task A_replay_with_the_signature_re_encoded_is_still_a_replay()
    {
        var uid = Guid.NewGuid();
        const long ts = 1792713600;
        var cache = new MemoryCache(new MemoryCacheOptions());
        cache.Set("ukey:" + uid, Signatures.PublicKeyFromSeed(Seed));   // UserKeyCache hit: no database needed
        var problems = new Problems();
        var passed = 0;
        var mw = new SignatureMiddleware(_ => { passed++; return Task.CompletedTask; }, new ConfigurationBuilder().Build(),
            new Clock(DateTimeOffset.FromUnixTimeSeconds(ts)), new UserKeyCache(cache, null!), new ReplayCache(cache), problems);

        const string body = """{"answer":"still"}""";
        var sig = Signatures.Sign(Seed, Signatures.RequestCanonical("POST", "/api/v1/issues/x/votes", ts, Encoding.UTF8.GetBytes(body))).Signature;
        var std = Convert.ToBase64String(sig);
        var urlSafeUnpadded = std.TrimEnd('=').Replace('+', '-').Replace('/', '_');

        HttpContext Request(string sigHeader)
        {
            var ctx = new DefaultHttpContext();
            ctx.SetEndpoint(new Endpoint(null, new EndpointMetadataCollection(new RequireSignatureMetadata()), "vote"));
            ctx.User = new ClaimsPrincipal(new ClaimsIdentity([new Claim("sub", uid.ToString())], "test"));
            ctx.Request.Method = "POST";
            ctx.Request.Path = "/api/v1/issues/x/votes";
            ctx.Request.Headers["X-Gimano-Ts"] = ts.ToString();
            ctx.Request.Headers["X-Gimano-Sig"] = sigHeader;
            ctx.Request.Body = new MemoryStream(Encoding.UTF8.GetBytes(body));
            return ctx;
        }

        await mw.InvokeAsync(Request(std));
        Assert.Equal(1, passed);

        // Same request, same signature bytes, different base64 spelling: before the fix this passed as a new request.
        var replay = Request(urlSafeUnpadded);
        await mw.InvokeAsync(replay);
        Assert.Equal(1, passed);
        Assert.Equal(401, replay.Response.StatusCode);
        Assert.EndsWith("signature-replayed", problems.LastType);
    }

    [Fact]
    public void Replay_cache_admits_exactly_one_of_many_concurrent_copies()
    {
        var replay = new ReplayCache(new MemoryCache(new MemoryCacheOptions()));
        var wins = 0;
        Parallel.For(0, 64, _ => { if (replay.TryAdd("k")) Interlocked.Increment(ref wins); });
        Assert.Equal(1, wins);
    }
}

public class IssueDirectoryExpiryRegressionTests
{
    private static readonly DateTimeOffset Now = new(2026, 10, 23, 10, 0, 0, TimeSpan.Zero);

    [Fact]
    public void Issues_past_expiry_are_not_routed_around_or_badged_before_the_worker_sweeps_them()
    {
        var line = new List<GeoPoint> { new(3.1000, 101.6000), new(3.1010, 101.6000) };
        var lines = new Dictionary<(long, int), IReadOnlyList<GeoPoint>> { [(7, 0)] = line, [(8, 0)] = line };
        var at = new GeoPoint(3.1005, 101.6).ToPoint();
        // An accident closure (2 h) that ended a minute ago, still "confirmed" in the database until the next 15-minute sweep.
        var expired = new Issue { Kind = IssueKind.RoadClosedTemp, State = IssueState.Confirmed, WayId = 7, Seq = 0, Location = at, ExpiresAt = Now.AddMinutes(-1) };
        var live = new Issue { Kind = IssueKind.RoadClosedTemp, State = IssueState.Confirmed, WayId = 8, Seq = 0, Location = at, ExpiresAt = Now.AddHours(1) };
        var placeTemp = new Issue { Kind = IssueKind.PlaceClosedTemp, State = IssueState.Suspected, PlaceId = "n1", Location = at,
            ExpiresAt = Now.AddSeconds(-30), Payload = new() { ["until"] = "2026-10-22" } };

        var s = IssueDirectory.Compose([expired, live, placeTemp], lines, Now);

        Assert.Equal([live.Id], s.Exclusions.Select(e => e.Issue.Id));
        Assert.DoesNotContain(s.Warnings, w => w.Issue.Id == expired.Id);
        Assert.False(s.IsClosedNow("n1"));
        Assert.False(s.PlaceBadges.ContainsKey("n1"));
    }
}
