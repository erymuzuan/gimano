using System.Text.Json;
using System.Text.Json.Nodes;
using Gimano.Api.Services;
using Gimano.Core.Contracts;
using Gimano.Core.Geo;
using Gimano.Core.Search;

namespace Gimano.Tests;

public class SearchQueryBuilderTests
{
    [Fact]
    public void Builds_search_as_you_type_bool_prefix_query()
    {
        var body = SearchQueryBuilder.Build(new SearchQuery("petronas", 3.11, 101.62));
        var mm = body["query"]!["function_score"]!["query"]!["boosting"]!["positive"]!["bool"]!["must"]![0]!["bool"]!["should"]![0]!["multi_match"]!;
        Assert.Equal("bool_prefix", mm["type"]!.GetValue<string>());
        Assert.Equal("petronas", mm["query"]!.GetValue<string>());
        var fields = mm["fields"]!.AsArray().Select(f => f!.GetValue<string>()).ToList();
        Assert.Contains("name", fields);
        Assert.Contains("name._2gram", fields);
        Assert.Contains("name._3gram", fields);
        Assert.Equal(25, body["size"]!.GetValue<int>());   // over-fetch 2×limit+5 for near-duplicate collapsing
    }

    [Fact]
    public void Adds_gauss_geo_decay_when_location_known()
    {
        var body = SearchQueryBuilder.Build(new SearchQuery("kfc", 3.11, 101.62));
        var gauss = body["query"]!["function_score"]!["functions"]!.AsArray().First(f => f!["gauss"] is not null)!["gauss"]!["location"]!;
        Assert.Equal(3.11, gauss["origin"]!["lat"]!.GetValue<double>());
        Assert.Equal(101.62, gauss["origin"]!["lon"]!.GetValue<double>());

        var noLoc = SearchQueryBuilder.Build(new SearchQuery("kfc", null, null));
        Assert.DoesNotContain(noLoc["query"]!["function_score"]!["functions"]!.AsArray(), f => f!["gauss"] is not null);
    }

    [Fact]
    public void Category_and_country_become_filters_and_limit_is_clamped()
    {
        var body = SearchQueryBuilder.Build(new SearchQuery("shell", null, null, 500, PlaceCategory.EvCharging, "sg"));
        var filters = body["query"]!["function_score"]!["query"]!["boosting"]!["positive"]!["bool"]!["filter"]!.AsArray();
        Assert.Contains(filters, f => f!["terms"]?["category"]?.AsArray().Any(c => c!.GetValue<string>() == "ev_charging") == true);
        Assert.Contains(filters, f => f!["term"]?["country"]?.GetValue<string>() == "SG");
        Assert.Equal(SearchQueryBuilder.MaxLimit, body["size"]!.GetValue<int>());
    }

    [Fact]
    public void Default_scope_prefers_malaysia_without_excluding_neighbours()
    {
        var body = SearchQueryBuilder.Build(new SearchQuery("changi", null, null));
        var b = body["query"]!["function_score"]!["query"]!["boosting"]!["positive"]!["bool"]!;
        Assert.DoesNotContain(b["filter"]!.AsArray(), f => f!["term"]?["country"] is not null);
        Assert.Contains(b["should"]!.AsArray(), s => s!["term"]?["country"] is not null);
    }

    [Fact]
    public void Long_queries_also_get_a_fuzzy_whole_word_match()
    {
        var should = SearchQueryBuilder.Build(new SearchQuery("petronaz", null, null))["query"]!["function_score"]!["query"]!["boosting"]!["positive"]!["bool"]!["must"]![0]!["bool"]!["should"]!.AsArray();
        Assert.Equal("AUTO", should[1]!["multi_match"]!["fuzziness"]!.GetValue<string>());
        Assert.Contains(should, x => x!["fuzzy"]?["nameKeys"] is not null);   // "famly mart" → FamilyMart
        var shortQ = SearchQueryBuilder.Build(new SearchQuery("kfc", null, null))["query"]!["function_score"]!["query"]!["boosting"]!["positive"]!["bool"]!["must"]![0]!["bool"]!["should"]!.AsArray();
        Assert.DoesNotContain(shortQ, x => x!["fuzzy"] is not null || x["multi_match"]?["fuzziness"]?.GetValue<string>() == "AUTO");
    }

    [Fact]
    public void Short_queries_are_not_fuzzy()
    {
        Assert.Equal("0", Mm(SearchQueryBuilder.Build(new SearchQuery("kf", null, null)))["fuzziness"]!.GetValue<string>());
        Assert.Equal("AUTO", Mm(SearchQueryBuilder.Build(new SearchQuery("petronas", null, null)))["fuzziness"]!.GetValue<string>());
        static JsonNode Mm(JsonObject b) => b["query"]!["function_score"]!["query"]!["boosting"]!["positive"]!["bool"]!["must"]![0]!["bool"]!["should"]![0]!["multi_match"]!;
    }

    [Fact]
    public void Index_definition_has_search_as_you_type_and_geo_point()
    {
        Assert.Equal(1, SearchQueryBuilder.IndexDefinition(1)["settings"]!["number_of_replicas"]!.GetValue<int>());
        var props = SearchQueryBuilder.IndexDefinition()["mappings"]!["properties"]!;
        Assert.Equal("search_as_you_type", props["name"]!["type"]!.GetValue<string>());
        Assert.Equal("geo_point", props["location"]!["type"]!.GetValue<string>());
        Assert.Equal("keyword", props["category"]!["type"]!.GetValue<string>());
    }

    [Fact]
    public void Parses_search_hits()
    {
        const string json = """
            {"hits":{"total":{"value":1},"hits":[{"_id":"n1","_score":3.2,"_source":
              {"id":"n1","name":"Petronas Damansara","category":"fuel","address":"Jalan SS 2","location":{"lat":3.11,"lon":101.62},"brand":"Petronas","country":"MY"}}]}}
            """;
        var hit = Assert.Single(PlaceSearchIndex.ParseHits(json));
        Assert.Equal(PlaceCategory.Fuel, hit.Category);
        Assert.Equal(101.62, hit.Lng);
        Assert.Equal("Petronas", hit.Brand);
    }
}

public class ContractTests
{
    [Theory]
    [InlineData(PlaceCategory.EvCharging, "ev_charging")]
    [InlineData(PlaceCategory.FastFood, "fast_food")]
    [InlineData(PlaceCategory.TransitStation, "transit_station")]
    public void Enums_serialise_as_snake_case(PlaceCategory c, string wire)
    {
        Assert.Equal($"\"{wire}\"", JsonSerializer.Serialize(c, GimanoJson.Options));
        Assert.Equal(c, JsonSerializer.Deserialize<PlaceCategory>($"\"{wire}\"", GimanoJson.Options));
        Assert.True(GimanoJson.TryParseWire<PlaceCategory>(wire, out var parsed) && parsed == c);
    }

    [Fact]
    public void Report_request_deserialises_from_contract_example()
    {
        const string json = """{ "type":"blocked_lane", "subtype":"left", "lat":3.1, "lng":101.6, "heading":270, "comment":null }""";
        var r = JsonSerializer.Deserialize<CreateReportRequest>(json, GimanoJson.Options)!;
        Assert.Equal(ReportType.BlockedLane, r.Type);
        Assert.Equal(270, r.Heading);
    }

    [Fact]
    public void Route_request_deserialises_from_contract_example()
    {
        const string json = """
            {"origin":{"lat":3.13,"lng":101.62},"destination":{"lat":3.12,"lng":101.61,"placeId":"n1"},"waypoints":[],
             "vehicle":"motorcycle","avoid":{"tolls":false,"highways":true,"ferries":false},"departAt":null,"alternatives":2}
            """;
        var r = JsonSerializer.Deserialize<RouteRequest>(json, GimanoJson.Options)!;
        Assert.Equal(Vehicle.Motorcycle, r.Vehicle);
        Assert.True(r.Avoid!.Highways);
        Assert.Equal("n1", r.Destination.PlaceId);
    }

    [Fact]
    public void Unknown_enum_values_are_rejected()
    {
        Assert.False(GimanoJson.TryParseWire<ReportType>("ufo", out _));
        Assert.Throws<JsonException>(() => JsonSerializer.Deserialize<ReportType>("\"ufo\"", GimanoJson.Options));
    }

    [Theory]
    [InlineData("101.6,3.1,101.7,3.2", true)]
    [InlineData("101.7,3.1,101.6,3.2", false)]
    [InlineData("a,b,c,d", false)]
    [InlineData("1,2,3", false)]
    public void Parses_bbox(string s, bool ok) => Assert.Equal(ok, BBox.TryParse(s, out _));

    [Fact]
    public void Drivers_are_anonymised_and_need_three_per_cell()
    {
        var drivers = new List<(double, double, double, string)>
        {
            (3.101234, 101.601234, 92, "happy"), (3.102, 101.602, 10, "chill"), (3.103, 101.603, 200, "happy"), // same cell
            (3.201, 101.701, 0, "happy"), (3.202, 101.702, 0, "happy"),                                        // only 2
        };
        var result = Gimano.Core.Traffic.DriverCells.Anonymise(drivers);
        Assert.Equal(3, result.Count);
        Assert.Equal(3.101, result[0].Lat);
        Assert.Equal(101.601, result[0].Lng);
        Assert.Equal(90, result[0].Heading);
    }

    [Theory]
    [InlineData("12, Jalan SS 2/24, 47300 Petaling Jaya, Selangor", "47300 Petaling Jaya, Selangor")]
    [InlineData("Jalan A, Taman B, Kuala Lumpur", "Taman B, Kuala Lumpur")]
    [InlineData(null, null)]
    public void Reverse_geocode_area_tail(string? address, string? expected) => Assert.Equal(expected, GeoLookup.AreaTail(address));
}
