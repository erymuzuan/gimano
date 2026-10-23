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

public class ExceptionMappingRegressionTests
{
    private sealed class Problems : Microsoft.AspNetCore.Http.IProblemDetailsService
    {
        public Microsoft.AspNetCore.Mvc.ProblemDetails? Last;
        public ValueTask WriteAsync(ProblemDetailsContext context) { Last = context.ProblemDetails; return ValueTask.CompletedTask; }
    }

    public static TheoryData<Exception> Races() =>
    [
        // Two drivers vote on the same hazard at once: the second save hits the reports.xmin concurrency token.
        new Microsoft.EntityFrameworkCore.DbUpdateConcurrencyException("The database operation was expected to affect 1 row(s)"),
        // A double-tapped vote: both requests pass the "already voted?" check, the second insert hits the unique index.
        new Microsoft.EntityFrameworkCore.DbUpdateException("save failed",
            new Npgsql.PostgresException("duplicate key value violates unique constraint \"ix_place_votes_place_id_voter_id\"", "ERROR", "ERROR", "23505")),
    ];

    [Theory]
    [MemberData(nameof(Races))]
    public async Task Request_races_are_409_without_leaking_database_details(Exception ex)
    {
        var problems = new Problems();
        var handler = new Gimano.Api.ApiExceptionHandler(problems, Microsoft.Extensions.Logging.Abstractions.NullLogger<Gimano.Api.ApiExceptionHandler>.Instance);
        var ctx = new DefaultHttpContext();
        await handler.TryHandleAsync(ctx, ex, CancellationToken.None);
        Assert.Equal(409, ctx.Response.StatusCode);
        Assert.Equal(409, problems.Last!.Status);
        Assert.DoesNotContain("constraint", problems.Last.Detail);
        Assert.DoesNotContain("row(s)", problems.Last.Detail);
    }

    [Fact]
    public async Task Other_database_errors_stay_500()
    {
        var problems = new Problems();
        var handler = new Gimano.Api.ApiExceptionHandler(problems, Microsoft.Extensions.Logging.Abstractions.NullLogger<Gimano.Api.ApiExceptionHandler>.Instance);
        var ctx = new DefaultHttpContext();
        await handler.TryHandleAsync(ctx, new Microsoft.EntityFrameworkCore.DbUpdateException("x",
            new Npgsql.PostgresException("value too long", "ERROR", "ERROR", "22001")), CancellationToken.None);
        Assert.Equal(500, ctx.Response.StatusCode);
    }
}

public class ReportVoteRegressionTests
{
    [Fact]
    public void Flip_flopping_a_vote_earns_points_once_and_keeps_confirmations_honest()
    {
        // Simulates ReportService.VoteAsync: true → false → true → false → true by one voter.
        bool? previous = null;
        int confirmations = 0, awards = 0;
        foreach (var answer in new[] { true, false, true, false, true })
        {
            var e = Gimano.Core.Alerts.AlertRules.OnVote(previous, answer);
            confirmations = Math.Max(0, confirmations + e.ConfirmationsDelta);
            if (e.AwardPoints) awards++;
            previous = answer;
        }
        Assert.Equal(1, confirmations);   // before: 3 (every switch back to "still there" counted again)
        Assert.Equal(1, awards);          // before: 5 voter awards and 3 reporter "confirmed" awards
    }

    [Fact]
    public void Reporting_the_same_hazard_again_counts_once()
    {
        // ReportService.CreateAsync merge path: the first duplicate from a driver is a "still there"; repeats are no-ops.
        var first = Gimano.Core.Alerts.AlertRules.OnVote(null, true);
        var again = Gimano.Core.Alerts.AlertRules.OnVote(true, true);
        Assert.Equal((1, true), (first.ConfirmationsDelta, first.AwardPoints));
        Assert.Equal((0, false), (again.ConfirmationsDelta, again.AwardPoints));   // before: +1 confirmation, +3/+1 points every time
    }

    [Theory]
    [InlineData(null, true, 1, true, true)]
    [InlineData(null, false, 0, false, true)]
    [InlineData(false, true, 1, true, false)]
    [InlineData(true, false, -1, false, false)]
    [InlineData(true, true, 0, false, false)]
    public void Vote_effects(bool? previous, bool stillThere, int delta, bool extend, bool award) =>
        Assert.Equal(new Gimano.Core.Alerts.AlertRules.VoteEffect(delta, extend, award), Gimano.Core.Alerts.AlertRules.OnVote(previous, stillThere));

    [Fact]
    public void One_thanks_per_pseudonym_per_report()
    {
        var cache = new MemoryCache(new MemoryCacheOptions());
        var now = DateTimeOffset.UtcNow;
        Guid report = Guid.NewGuid(), a = Guid.NewGuid(), b = Guid.NewGuid();
        Assert.True(ReportService.FirstThanks(cache, report, a, now.AddHours(1), now));
        Assert.False(ReportService.FirstThanks(cache, report, a, now.AddHours(1), now));   // before: every repeat added points + an inbox bump
        Assert.True(ReportService.FirstThanks(cache, report, b, now.AddHours(1), now));
        Assert.True(ReportService.FirstThanks(cache, Guid.NewGuid(), a, now.AddHours(1), now));
    }
}

public class SearchOpenNowRegressionTests
{
    [Fact]
    public void Search_and_nearby_use_confirmed_corrected_hours_like_the_place_card()
    {
        var now = new DateTimeOffset(2026, 10, 23, 7, 0, 0, TimeSpan.Zero);   // 15:00 in Malaysia
        var hoursFix = new Issue { Kind = IssueKind.PlaceHoursWrong, State = IssueState.Confirmed, PlaceId = "n1", ConfirmedAt = now.AddDays(-1),
            Location = new GeoPoint(3.1, 101.6).ToPoint(), Payload = new() { ["openingHours"] = "Mo-Su 08:00-10:00" }, ExpiresAt = now.AddDays(100) };
        var s = IssueDirectory.Compose([hoursFix], new Dictionary<(long, int), IReadOnlyList<GeoPoint>>(), now);
        // The index still has the OSM hours (24/7), so the summary arrives saying open.
        var fromIndex = new PlaceSummary("n1", "Kedai Pak Ali", PlaceCategory.Convenience, "", 3.1, 101.6, null, null, OpenNow: true);

        var result = PlaceService.ApplyIssues([fromIndex], s, "en", new DateTime(2026, 10, 23, 15, 0, 0));

        Assert.False(result.Single().OpenNow);   // before: true in /search and /places/nearby while GET /places/n1 said false
        Assert.NotNull(result.Single().Badge);
    }
}

public class InboxUnreadRegressionTests
{
    [Fact]
    public void An_expired_announcement_is_neither_listed_nor_counted_as_unread()
    {
        var now = new DateTimeOffset(2026, 10, 23, 10, 0, 0, TimeSpan.Zero);
        var visible = Gimano.Core.Community.InboxRules.VisibleAnnouncement(now).Compile();
        Assert.False(visible(new Announcement { Title = "Maintenance tonight", Body = "", CreatedAt = now.AddDays(-1), ExpiresAt = now.AddMinutes(-5) }));
        Assert.True(visible(new Announcement { Title = "New version", Body = "", CreatedAt = now.AddDays(-1), ExpiresAt = null }));
        Assert.True(visible(new Announcement { Title = "Promo", Body = "", CreatedAt = now.AddDays(-1), ExpiresAt = now.AddDays(1) }));
        Assert.False(visible(new Announcement { Title = "Old", Body = "", CreatedAt = now.AddDays(-91) }));
    }
}

public class BBoxRegressionTests
{
    [Theory]
    [InlineData("NaN,NaN,NaN,NaN")]
    [InlineData("101.5,NaN,101.7,3.2")]
    [InlineData("-Infinity,3.0,101.7,3.2")]
    public void Non_finite_bboxes_are_rejected(string bbox) =>
        Assert.False(BBox.TryParse(bbox, out _));   // before: NaN passed validation and the 1°/2° size limits

    [Fact]
    public void A_normal_bbox_still_parses() => Assert.True(BBox.TryParse("101.5,3.0,101.7,3.2", out _));
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
