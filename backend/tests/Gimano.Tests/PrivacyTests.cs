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
    public void Feedback_location_is_rounded_to_2dp_for_new_and_legacy_shapes()
    {
        var legacy = FeedbackEndpoints.Normalize(new CreateFeedbackRequest(FeedbackCategory.Bug, "x",
            Context: new FeedbackContext("0.1.0 (1)", "android", "16", "SM-S921B", "ms", 3.118765, 101.619432, "r1", "n1")));
        Assert.Equal((3.12, 101.62), (legacy.Location!.Lat, legacy.Location.Lng));
        Assert.Equal(("0.1.0 (1)", "android", "SM-S921B"), (legacy.Device!.AppVersion, legacy.Device.Platform, legacy.Device.DeviceModel));
        Assert.Equal(("r1", "n1"), (legacy.RouteId, legacy.PlaceId));

        var modern = FeedbackEndpoints.Normalize(new CreateFeedbackRequest(FeedbackCategory.Bug, "x", Location: new CoarseLocation(3.1249, 101.6251)));
        Assert.Equal((3.12, 101.63), (modern.Location!.Lat, modern.Location.Lng));
        Assert.Null(modern.Device);
        Assert.Null(FeedbackEndpoints.Normalize(new CreateFeedbackRequest(FeedbackCategory.Bug, "x")).Location);
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
