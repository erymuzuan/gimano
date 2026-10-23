using System.Text.RegularExpressions;
using Gimano.Api.Endpoints;
using Gimano.Api.Live;
using Gimano.Core.Contracts;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.Extensions.Configuration;

namespace Gimano.Tests;

public class PrivacyTests
{
    private sealed class FixedClock(DateTimeOffset now) : TimeProvider
    {
        public DateTimeOffset Now = now;
        public override DateTimeOffset GetUtcNow() => Now;
    }

    [Fact]
    public void Ping_sessions_are_stable_within_the_hour_and_unlinkable_across_hours()
    {
        var clock = new FixedClock(new DateTimeOffset(2026, 10, 23, 8, 5, 0, TimeSpan.Zero));
        var sessions = new PingSessions(clock);
        var user = Guid.NewGuid();
        var a = sessions.For(user);
        Assert.Equal(a, sessions.For(user));
        Assert.NotEqual(user, a);
        Assert.NotEqual(a, sessions.For(Guid.NewGuid()));
        clock.Now = clock.Now.AddHours(1);
        Assert.NotEqual(a, sessions.For(user));
    }

    [Fact]
    public void Feedback_location_is_rounded_to_2dp()
    {
        var c = FeedbackEndpoints.Scrub(new FeedbackContext("1", "ios", "26", "iPhone17,3", "ms", 3.118765, 101.619432, null, null))!;
        Assert.Equal(3.12, c.Lat);
        Assert.Equal(101.62, c.Lng);
        Assert.Null(FeedbackEndpoints.Scrub(null));
        Assert.Null(FeedbackEndpoints.Scrub(new FeedbackContext(null, null, null, null, null, null, null, null, null))!.Lat);
    }

    [Fact]
    public async Task Legacy_saved_places_answer_410_when_disabled()
    {
        var off = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> { ["Features:LegacySavedPlaces"] = "false" }).Build();
        var on = new ConfigurationBuilder().Build();
        var ctx = new DefaultEndpointFilterInvocationContext(new DefaultHttpContext());
        var next = new EndpointFilterDelegate(_ => ValueTask.FromResult<object?>("ok"));

        var gone = await new LegacySavedPlacesFilter(off).InvokeAsync(ctx, next);
        Assert.Equal(StatusCodes.Status410Gone, Assert.IsType<ProblemHttpResult>(gone).StatusCode);
        Assert.Equal("ok", await new LegacySavedPlacesFilter(on).InvokeAsync(ctx, next));
    }

    [Fact]
    public void No_log_call_formats_full_precision_coordinates_or_request_bodies()
    {
        var src = FindDir("backend/src");
        Assert.NotNull(src);
        var offenders = new List<string>();
        var logCall = new Regex(@"Log(Information|Warning|Error|Debug|Trace|Critical)\((?<args>[^;]*)\);", RegexOptions.Singleline);
        foreach (var file in Directory.EnumerateFiles(src!, "*.cs", SearchOption.AllDirectories).Where(f => !f.Contains("/obj/") && !f.Contains("/bin/")))
            foreach (Match m in logCall.Matches(File.ReadAllText(file)))
            {
                var args = m.Groups["args"].Value;
                // {Lat}/{Lng} placeholders must carry a ≤ 2 dp format.
                foreach (Match ph in Regex.Matches(args, @"\{(Lat|Lng|Lon|Latitude|Longitude)(:(?<fmt>[^}]*))?\}"))
                    if (ph.Groups["fmt"].Value is not ("F0" or "F1" or "F2")) offenders.Add($"{Path.GetFileName(file)}: {ph.Value}");
                if (Regex.IsMatch(args, @"Request\.Body|ReadAsStringAsync|\{Body[:}]", RegexOptions.IgnoreCase)) offenders.Add($"{Path.GetFileName(file)}: body in log");
            }
        Assert.Empty(offenders);
    }

    private static string? FindDir(string rel)
    {
        for (var d = new DirectoryInfo(AppContext.BaseDirectory); d is not null; d = d.Parent)
            if (Directory.Exists(Path.Combine(d.FullName, rel))) return Path.Combine(d.FullName, rel);
        return null;
    }
}
