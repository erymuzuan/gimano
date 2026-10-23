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

    [Fact]
    public void Area_name_variants_are_indexed()
    {
        var a = SearchAliases.AreaVariants("Putrajaya", T("official_name", "Wilayah Persekutuan Putrajaya", "name:en", "Putrajaya", "alt_name", "PJY;Pusat Pentadbiran"))!;
        Assert.Contains("Wilayah Persekutuan Putrajaya", a);
        Assert.Contains("Pusat Pentadbiran", a);
        Assert.DoesNotContain("Putrajaya Putrajaya", a);
        Assert.Null(SearchAliases.AreaVariants("Kedai Runcit Ah Seng", T("name", "Kedai Runcit Ah Seng")));
    }

    [Fact]
    public void Query_boosts_whole_name_keys_brand_aliases_and_areas_and_keeps_streets_down()
    {
        var body = SearchQueryBuilder.Build(new SearchQuery("Putrajaya", 3.1478, 101.6153, Limit: 5, Variants: ["putra jaya"]));
        var fs = body["query"]!["function_score"]!;
        var boosting = fs["query"]!["boosting"]!;
        Assert.Equal("street", boosting["negative"]!["term"]!["category"]!.GetValue<string>());
        var b = boosting["positive"]!["bool"]!;
        var should = b["should"]!.AsArray();
        Assert.Contains(should, s => s!["term"]?["nameKey"]?["value"]?.GetValue<string>() == "putrajaya");
        Assert.Contains(should, s => s!["term"]?["nameKeys"]?["value"]?.GetValue<string>() == "putrajaya");
        Assert.Contains(should, s => s!["term"]?["brandKey"]?["value"]?.GetValue<string>() == "putrajaya");
        Assert.Contains(should, s => s!["prefix"]?["nameKey"]?["value"]?.GetValue<string>() == "putrajaya");
        Assert.Contains(should, s => s!["match"]?["aliases"] is not null);
        Assert.Contains(should, s => s!["term"]?["category"]?["value"]?.GetValue<string>() == "area");
        // the variant is searched too, a little weaker
        Assert.Contains(should, s => s!["term"]?["nameKey"]?["value"]?.GetValue<string>() == "putrajaya" && s["term"]!["nameKey"]!["boost"]!.GetValue<double>() < SearchQueryBuilder.ExactNameBoost);
        Assert.Contains("address", b["must_not"]!.ToJsonString());
        Assert.Equal(15, body["size"]!.GetValue<int>());
        Assert.Equal(2, fs["functions"]!.AsArray().Count(f => f!["gauss"] is not null));
        Assert.Null(body["sort"]);
        Assert.NotNull(SearchQueryBuilder.Build(new SearchQuery("x", 3.1, 101.6, SortNearest: true))["sort"]);
    }

    [Fact]
    public void Category_street_postcode_and_chain_count_bodies()
    {
        var cat = SearchQueryBuilder.BuildCategory(new SearchQuery("minyak", 3.1, 101.6, Categories: [PlaceCategory.Fuel], RadiusM: 5000));
        Assert.Contains("\"fuel\"", cat.ToJsonString());
        Assert.Contains("geo_distance", cat.ToJsonString());
        Assert.NotNull(cat["sort"]![0]!["_geo_distance"]);

        var st = SearchQueryBuilder.BuildStreet(new SearchQuery("12 jalan ss2/24", 3.1, 101.6, Scope: SearchScope.Addresses, HouseNumber: "12"), "jalan ss 2 24");
        Assert.Contains("\"houseNumber\":\"12\"", st.ToJsonString());
        Assert.Contains("\"address\"", st.ToJsonString());
        Assert.Contains("47300", SearchQueryBuilder.BuildPostcode("47300").ToJsonString());
        Assert.Contains("familymart", SearchQueryBuilder.BuildChainCount("familymart", 3.1, 101.6, 10_000, null).ToJsonString());
    }

    [Fact]
    public void Documents_carry_spacing_insensitive_keys_and_the_index_maps_them()
    {
        var doc = SearchQueryBuilder.ToDocument(new Place
        {
            Id = "n1", Name = "Family Mart SS15", Brand = "FamilyMart", Category = PlaceCategory.Convenience,
            Location = new GeoPoint(3.13, 101.62).ToPoint(), OsmType = "node",
        });
        Assert.Equal("familymartss15", doc["nameKey"]!.GetValue<string>());
        Assert.Contains("familymart", doc["nameKeys"]!.AsArray().Select(k => k!.GetValue<string>()));
        Assert.Equal("familymart", doc["brandKey"]!.GetValue<string>());
        var props = SearchQueryBuilder.IndexDefinition(0)["mappings"]!["properties"]!;
        foreach (var f in new[] { "nameKey", "nameKeys", "brandKey", "streetKey", "streetText", "houseNumber", "postcode", "aliases" }) Assert.NotNull(props[f]);

        var street = SearchQueryBuilder.StreetDocument("st-1", "Jalan SS 2/24", 3.119, 101.62, "SS2, Petaling Jaya", "MY");
        Assert.Equal("jalan ss 2 24", street["streetKey"]!.GetValue<string>());
        Assert.Equal("street", street["category"]!.GetValue<string>());
        var addr = SearchQueryBuilder.AddressDocument(new AddressPoint("n9", "12", "Jalan SS 2/24", "47300", "Petaling Jaya", 3.119, 101.62, "MY"), "SS2");
        Assert.Equal("12, Jalan SS 2/24", addr["name"]!.GetValue<string>());
        Assert.Equal("47300 Petaling Jaya", addr["address"]!.GetValue<string>());
        Assert.Equal("pc-47300", SearchQueryBuilder.PostcodeDocument("47300", 3.1, 101.6, "Petaling Jaya", "MY")["id"]!.GetValue<string>());
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
