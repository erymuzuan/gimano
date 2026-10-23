using System.Net.Http.Json;
using System.Text.Json.Nodes;

namespace Gimano.Tests;

/// <summary>
/// Against a running API with real OSM data (GIMANO_API_URL, e.g. http://100.101.150.9:5080); skipped otherwise.
/// Run: GIMANO_API_URL=http://100.101.150.9:5080 dotnet test --solution Gimano.slnx --filter-class "*ApiSmoke*"
/// </summary>
public class ApiSmokeTests
{
    private static HttpClient? Client()
    {
        var url = Environment.GetEnvironmentVariable("GIMANO_API_URL");
        return string.IsNullOrEmpty(url) ? null : new HttpClient { BaseAddress = new Uri(url.TrimEnd('/') + "/api/v1/") };
    }

    [Theory]
    [InlineData(3.15794, 101.71231)]    // KLCC (Suria KLCC)
    [InlineData(3.157868, 101.710562)]  // KLCC Park side
    public async Task Reverse_at_KLCC_is_in_Kuala_Lumpur(double lat, double lng)
    {
        using var http = Client();
        if (http is null) { Assert.Skip("GIMANO_API_URL not set"); return; }
        var r = await http.GetFromJsonAsync<JsonObject>(FormattableString.Invariant($"geocode/reverse?lat={lat}&lng={lng}"));
        Assert.Equal("Kuala Lumpur", r!["city"]?.GetValue<string>());
        Assert.Contains("Kuala Lumpur", r["label"]!.GetValue<string>());
        Assert.DoesNotContain("Ampang Jaya", r["label"]!.GetValue<string>());
    }

    [Fact]
    public async Task Reverse_next_to_a_shop_lists_it_nearby()
    {
        using var http = Client();
        if (http is null) { Assert.Skip("GIMANO_API_URL not set"); return; }
        // 7-Eleven, 8 Jalan SS 2/61, Petaling Jaya; picked ~20 m away
        var r = await http.GetFromJsonAsync<JsonObject>("geocode/reverse?lat=3.117360&lng=101.621838");
        Assert.Contains(r!["nearby"]!.AsArray(), n => n!["name"]!.GetValue<string>() == "7-Eleven" && n["distanceM"]!.GetValue<double>() <= 50);
        Assert.Equal("Petaling Jaya", r["city"]?.GetValue<string>());
    }
}
