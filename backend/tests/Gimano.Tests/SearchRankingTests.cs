using System.Text.Json.Nodes;
using Gimano.Core.Contracts;
using Gimano.Core.Domain;
using Gimano.Core.Geo;
using Gimano.Core.Places;
using Gimano.Core.Search;
using Gimano.Importer;
using OsmSharp;
using OsmSharp.Tags;

namespace Gimano.Tests;

/// <summary>Search quality: areas, whole-name matches, aliases and near-duplicate collapsing (unit level).</summary>
public class SearchRankingUnitTests
{
    private static Dictionary<string, string> T(params string[] kv) =>
        Enumerable.Range(0, kv.Length / 2).ToDictionary(i => kv[i * 2], i => kv[i * 2 + 1]);

    [Theory]
    [InlineData("city"), InlineData("town"), InlineData("suburb"), InlineData("village"), InlineData("neighbourhood")]
    public void Osm_place_nodes_map_to_area(string place) =>
        Assert.Equal(PlaceCategory.Area, OsmCategoryMapper.Map(T("place", place, "name", "Putrajaya")));

    [Fact]
    public void Unnamed_places_and_islands_are_not_areas()
    {
        Assert.Null(OsmCategoryMapper.Map(T("place", "city")));
        Assert.NotEqual(PlaceCategory.Area, OsmCategoryMapper.Map(T("place", "island", "name", "Pulau Ketam")));
    }

    [Fact]
    public void Area_popularity_ranks_cities_above_neighbourhoods_and_address_names_the_kind()
    {
        var city = T("place", "city", "name", "Putrajaya", "population", "109202", "wikidata", "Q192189");
        var hood = T("place", "neighbourhood", "name", "Presint 9");
        Assert.True(OsmCategoryMapper.Popularity(PlaceCategory.Area, city) > OsmCategoryMapper.Popularity(PlaceCategory.Area, hood) + 5);
        var p = OsmImporter.ToPlace("n", 1, PlaceCategory.Area, T("place", "town", "name", "Semenyih", "is_in:state", "Selangor"), 2.95, 101.84);
        Assert.Equal("Bandar, Selangor", p.Address);
    }

    [Fact]
    public void Admin_boundary_relations_become_areas_at_their_label_node()
    {
        var r = new Relation
        {
            Id = 4443571,
            Tags = new TagsCollection(new Tag("boundary", "administrative"), new Tag("admin_level", "4"), new Tag("name", "Wilayah Persekutuan Putrajaya")),
            Members = [new RelationMember(11, "outer", OsmGeoType.Way), new RelationMember(42, "admin_centre", OsmGeoType.Node), new RelationMember(43, "label", OsmGeoType.Node)],
        };
        var a = OsmImporter.AdminArea(r);
        Assert.NotNull(a);
        Assert.Equal(43, a.Value.LabelNode);
        var p = OsmImporter.ToPlace("r", a.Value.Id, PlaceCategory.Area, a.Value.Tags, 2.93, 101.69);
        Assert.Equal("r4443571", p.Id);
        Assert.Equal("relation", p.OsmType);
        Assert.StartsWith("Negeri / Wilayah", p.Address);

        r.Tags = new TagsCollection(new Tag("boundary", "administrative"), new Tag("admin_level", "8"), new Tag("name", "Mukim X"));
        Assert.Null(OsmImporter.AdminArea(r));   // too fine-grained
        r.Tags = new TagsCollection(new Tag("boundary", "administrative"), new Tag("admin_level", "6"), new Tag("name", "Petaling"));
        r.Members = [new RelationMember(11, "outer", OsmGeoType.Way)];
        Assert.Null(OsmImporter.AdminArea(r));   // no point to place it
    }

    [Fact]
    public void Admin_area_merges_into_the_same_named_place_node_nearby()
    {
        var city = OsmImporter.ToPlace("n", 1, PlaceCategory.Area, T("place", "city", "name", "Putrajaya"), 2.918, 101.684);
        var admin = OsmImporter.ToPlace("r", 2, PlaceCategory.Area, T("place", "admin", "gimano:admin_level", "4", "name", "Putrajaya"), 2.938, 101.692);
        Assert.Same(city, OsmImporter.MergeAdminInto([city], admin));
        var other = OsmImporter.ToPlace("r", 3, PlaceCategory.Area, T("place", "admin", "gimano:admin_level", "6", "name", "Putrajaya"), 4.5, 103.4);
        Assert.Null(OsmImporter.MergeAdminInto([city], other));
        Assert.Equal("Kejiranan, Selangor", OsmCategoryMapper.AreaAddress(T("place", "neighbourhood", "is_in", "Semenyih;Selangor")));
    }

    [Theory]
    [InlineData("McDonald's Damansara Uptown", "McDonald's", "mcd")]
    [InlineData("Mid Valley Megamall", null, "midvalley")]
    [InlineData("Suria KLCC", null, "klcc")]
    [InlineData("Petronas Twin Towers", null, "klcc")]
    [InlineData("7-Eleven SS2", "7-Eleven", "7eleven")]
    public void Aliases_cover_common_short_names(string name, string? brand, string alias) =>
        Assert.Contains(alias, SearchAliases.For(name, brand)!.Split(' '));

    [Fact]
    public void No_alias_for_ordinary_names() => Assert.Null(SearchAliases.For("Kedai Runcit Ah Seng", null));

    [Fact]
    public void Area_name_variants_are_aliases()
    {
        var a = SearchAliases.For("Putrajaya", null, T("official_name", "Wilayah Persekutuan Putrajaya", "name:en", "Putrajaya", "alt_name", "PJY;Pusat Pentadbiran"))!;
        Assert.Contains("Wilayah Persekutuan Putrajaya", a);
        Assert.Contains("Pusat Pentadbiran", a);
        Assert.DoesNotContain("Putrajaya Putrajaya", a);
    }

    [Fact]
    public void Query_boosts_whole_name_exact_and_prefix_aliases_and_areas()
    {
        var body = SearchQueryBuilder.Build(new SearchQuery("Putrajaya", 3.1478, 101.6153, Limit: 5));
        var should = body["query"]!["function_score"]!["query"]!["bool"]!["should"]!.AsArray();
        Assert.Contains(should, s => s!["term"]?["name.raw"]?["value"]?.GetValue<string>() == "putrajaya");
        Assert.Contains(should, s => s!["prefix"]?["name.raw"]?["value"]?.GetValue<string>() == "putrajaya");
        Assert.Contains(should, s => s!["match"]?["aliases"] is not null);
        Assert.Contains(should, s => s!["term"]?["category"]?["value"]?.GetValue<string>() == "area");
        Assert.Equal(15, body["size"]!.GetValue<int>());
        Assert.Equal(2, body["query"]!["function_score"]!["functions"]!.AsArray().Count(f => f!["gauss"] is not null));
    }

    [Fact]
    public void Documents_carry_aliases_and_the_index_maps_them()
    {
        var doc = SearchQueryBuilder.ToDocument(new Place
        {
            Id = "n1", Name = "McDonald's Uptown", Brand = "McDonald's", Category = PlaceCategory.FastFood,
            Location = new GeoPoint(3.13, 101.62).ToPoint(), OsmType = "node",
        });
        Assert.Contains("mcd", doc["aliases"]!.GetValue<string>());
        Assert.NotNull(SearchQueryBuilder.IndexDefinition(0)["mappings"]!["properties"]!["aliases"]);
    }

    private sealed record H(string Id, string Name, double Lat, double Lng);

    [Fact]
    public void Near_duplicates_with_the_same_name_within_50m_collapse_keeping_the_better_ranked()
    {
        var hits = new[]
        {
            new H("n1", "Petronas, Persiaran Zaaba", 3.14000, 101.62000),
            new H("w2", "Petronas,  persiaran zaaba", 3.14020, 101.62010),   // ~25 m, same name modulo case/spaces
            new H("n3", "Petronas, Persiaran Zaaba", 3.15000, 101.62000),    // ~1.1 km: a different station
            new H("n4", "Petronas SS2", 3.14001, 101.62001),                 // same spot, different name
        };
        var d = SearchResults.Dedupe(hits, h => h.Name, h => (h.Lat, h.Lng), 10);
        Assert.Equal(["n1", "n3", "n4"], d.Select(h => h.Id).ToArray());
        Assert.Equal(["n1", "n3"], SearchResults.Dedupe(hits, h => h.Name, h => (h.Lat, h.Lng), 2).Select(h => h.Id).ToArray());
    }
}

/// <summary>
/// Ranking against a real OpenSearch (same gating as <see cref="OpenSearchIntegrationTests"/>: GIMANO_OS_URL).
/// The fixture reproduces the reported bug: from Petaling Jaya, "Putrajaya" returned a long government name containing
/// the word (8 km away) and POIs, never Putrajaya itself.
/// </summary>
public class OpenSearchIntegrationRankingTests
{
    private const double PjLat = 3.1478, PjLng = 101.6153;

    private static Place P(string id, string name, PlaceCategory cat, double lat, double lng, Dictionary<string, string>? tags = null, string? brand = null)
    {
        tags ??= new() { ["name"] = name };
        return new Place
        {
            Id = id, Name = name, Category = cat, Brand = brand, Location = new GeoPoint(lat, lng).ToPoint(), OsmType = "node",
            Address = cat == PlaceCategory.Area ? OsmCategoryMapper.AreaAddress(tags) : "",
            Popularity = OsmCategoryMapper.Popularity(cat, tags),
        };
    }

    private static Dictionary<string, string> Area(string place, string name, string? population = null)
    {
        var t = new Dictionary<string, string> { ["place"] = place, ["name"] = name, ["wikidata"] = "Q1" };
        if (population is not null) t["population"] = population;
        return t;
    }

    private static readonly Place[] Fixture =
    [
        // Putrajaya
        P("n1", "Putrajaya", PlaceCategory.Area, 2.9264, 101.6964, Area("city", "Putrajaya", "109202")),
        P("r2", "Wilayah Persekutuan Putrajaya", PlaceCategory.Area, 2.9300, 101.6900,
            new() { ["place"] = "admin", ["gimano:admin_level"] = "4", ["name"] = "Wilayah Persekutuan Putrajaya" }),
        P("n3", "Jabatan Kehakiman Syariah Wilayah Persekutuan Kuala Lumpur dan Putrajaya", PlaceCategory.Government, 3.0940, 101.6700),
        P("n4", "Hospital Putrajaya", PlaceCategory.Hospital, 2.9295, 101.6740),
        P("n5", "Taman Wetland Putrajaya", PlaceCategory.Tourism, 2.9150, 101.6870),
        P("n6", "Putrajaya Sentral", PlaceCategory.TransitStation, 2.9318, 101.6710),
        P("n7", "Taman Botani Putrajaya", PlaceCategory.Tourism, 2.8960, 101.6830),
        // Cyberjaya
        P("n10", "Cyberjaya", PlaceCategory.Area, 2.9213, 101.6559, Area("town", "Cyberjaya", "60000")),
        P("n11", "Cyberjaya Hospital", PlaceCategory.Hospital, 2.9250, 101.6440),
        P("n12", "Kolej Universiti Cyberjaya Pusat Pengajian", PlaceCategory.University, 3.0500, 101.6400),
        // Shah Alam
        P("n20", "Shah Alam", PlaceCategory.Area, 3.0733, 101.5185, Area("city", "Shah Alam", "740750")),
        P("n21", "Stadium Shah Alam", PlaceCategory.Tourism, 3.0815, 101.5450),
        P("n22", "Masjid Sultan Salahuddin Abdul Aziz Shah", PlaceCategory.Mosque, 3.0786, 101.5208),
        P("n23", "Klinik Kesihatan Seksyen 7 Shah Alam", PlaceCategory.Clinic, 3.0730, 101.4890),
        // KLCC
        P("n30", "Suria KLCC", PlaceCategory.Mall, 3.1579, 101.7123),
        P("n31", "Petronas Twin Towers", PlaceCategory.Tourism, 3.1578, 101.7117),
        P("n32", "KLCC", PlaceCategory.TransitStation, 3.1590, 101.7135),
        P("n33", "Klinik Kesihatan KLCC Wellness Centre", PlaceCategory.Clinic, 3.1560, 101.7100),
        // Mid Valley
        P("n40", "Mid Valley Megamall", PlaceCategory.Mall, 3.1176, 101.6773),
        P("n41", "Mid Valley City", PlaceCategory.Area, 3.1180, 101.6760, Area("suburb", "Mid Valley City")),
        P("n42", "Restoran Mid Valley Corner Seksyen 14", PlaceCategory.Restaurant, 3.1100, 101.6300),
        // Semenyih
        P("n50", "Semenyih", PlaceCategory.Area, 2.9516, 101.8430, Area("town", "Semenyih", "50000")),
        P("n51", "Petronas Jalan Semenyih", PlaceCategory.Fuel, 2.9900, 101.7900, brand: "Petronas"),
        P("n52", "Tasik Semenyih", PlaceCategory.Tourism, 2.9800, 101.8800),
        // Petronas around PJ (+ the reported duplicate: node + building way of the same station)
        P("n60", "Petronas, Persiaran Zaaba", PlaceCategory.Fuel, 3.1400, 101.6250, brand: "Petronas"),
        P("w61", "Petronas, Persiaran Zaaba", PlaceCategory.Fuel, 3.1402, 101.6251, brand: "Petronas"),
        P("n62", "Petronas SS2", PlaceCategory.Fuel, 3.1185, 101.6230, brand: "Petronas"),
        P("n63", "Petronas Kepong", PlaceCategory.Fuel, 3.2100, 101.6400, brand: "Petronas"),
        P("n64", "Petronas Dagangan Berhad Ibu Pejabat Wilayah Tengah", PlaceCategory.Government, 3.1570, 101.7110),
        // McDonald's
        P("n70", "McDonald's Damansara Uptown", PlaceCategory.FastFood, 3.1360, 101.6230, brand: "McDonald's"),
        P("n71", "McDonald's Kota Damansara", PlaceCategory.FastFood, 3.1500, 101.5800, brand: "McDonald's"),
        P("n72", "Kedai McDermott Hardware", PlaceCategory.Other, 3.1479, 101.6154),
    ];

    private static async Task<List<PlaceHit>> Search(PlaceSearchIndex index, string q, int limit = 5)
    {
        var hits = await index.SearchAsync(new SearchQuery(q, PjLat, PjLng, Limit: limit));
        return SearchResults.Dedupe(hits, h => h.Name, h => (h.Lat, h.Lng), limit);
    }

    [Fact]
    public async Task Ranking_from_petaling_jaya()
    {
        var prefix = $"gimano-rk{Guid.NewGuid():N}"[..20] + "-";
        var index = OpenSearchIntegrationTests.Create(prefix, out var client);
        if (index is null) { Assert.Skip("GIMANO_OS_URL not set"); return; }
        try
        {
            await index.EnsureIndexAsync();
            await index.BulkIndexAsync(Fixture.Select(p => SearchQueryBuilder.ToDocument(p)).ToList());
            await index.RefreshAsync();

            var failures = new List<string>();
            async Task Expect(string q, Func<List<PlaceHit>, bool> ok, string what, int limit = 5)
            {
                var r = await Search(index, q, limit);
                if (!ok(r)) failures.Add($"Assert-rank '{q}': expected {what}; got {string.Join(" | ", r.Select(h => $"{h.Id} {h.Name}"))}");
            }
            int At(List<PlaceHit> r, string id) => r.FindIndex(h => h.Id == id);

            await Expect("Putrajaya", r => r[0].Id == "n1" && At(r, "n3") is -1 or > 2, "n1 first, long government name not in top 3");
            await Expect("putraj", r => r[0].Id == "n1", "n1 first (as-you-type)");
            await Expect("Cyberjaya", r => r[0].Id == "n10", "n10 first");
            await Expect("Shah Alam", r => r[0].Id == "n20", "n20 first");
            await Expect("shah al", r => r[0].Id == "n20", "n20 first (as-you-type)");
            await Expect("KLCC", r => r[0].Id is "n32" or "n30" or "n31" && At(r, "n30") is >= 0 and < 3, "KLCC station/Suria/towers first, Suria in top 3");
            await Expect("Mid Valley", r => r[0].Id is "n40" or "n41" && At(r, "n40") is >= 0 and < 2, "Megamall or the area first");
            await Expect("Semenyih", r => r[0].Id == "n50", "n50 first");
            await Expect("petronas", r => r[0].Id is "n60" or "w61" && r.Count(h => h.Name == "Petronas, Persiaran Zaaba") == 1
                && At(r, "n62") < At(r, "n63") && At(r, "n64") > At(r, "n63"), "nearest first, duplicate collapsed, SS2 < Kepong < office", 10);
            await Expect("mcd", r => r.Count >= 2 && r[0].Id == "n70" && r[1].Id == "n71", "n70, n71");
            Assert.True(failures.Count == 0, string.Join("\n", failures));
        }
        finally
        {
            var all = await client.Indices.GetAsync<OpenSearch.Net.StringResponse>(prefix + "*");
            if (all.Success && JsonNode.Parse(all.Body) is JsonObject o)
                foreach (var (name, _) in o) await client.Indices.DeleteAsync<OpenSearch.Net.StringResponse>(name);
        }
    }
}

public class PendingProgressTests
{
    [Fact]
    public void Pending_summary_carries_weighted_progress_and_requirement_on_the_wire()
    {
        var s = new PlaceSummary("c1", "Kedai Baru", PlaceCategory.Other, "", 3.1, 101.6, null, null, PlaceSource.Community,
            PlaceStatus.Pending, ConfirmationsWeighted: 1, Required: 3);
        var json = System.Text.Json.JsonSerializer.Serialize(s, GimanoJson.Options);
        Assert.Contains("\"status\":\"pending\"", json);
        Assert.Contains("\"confirmationsWeighted\":1", json);
        Assert.Contains("\"required\":3", json);
    }

    [Fact]
    public void Required_is_the_creation_tier_requirement_or_baharus_during_an_audit()
    {
        Assert.Equal(2, Gimano.Api.Services.CommunityService.RequiredFor(new CommunityPlace { Name = "X", Location = new GeoPoint(3.1, 101.6).ToPoint(), RequiredConfirmations = 2 }));
        Assert.Equal(3, Gimano.Api.Services.CommunityService.RequiredFor(new CommunityPlace { Name = "X", Location = new GeoPoint(3.1, 101.6).ToPoint(), RequiredConfirmations = 0, AuditOpen = true }));
    }
}
