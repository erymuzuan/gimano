using Gimano.Api.Services;
using Gimano.Core.Community;
using Gimano.Core.Contracts;
using Gimano.Core.Domain;
using Gimano.Core.Geo;
using Gimano.Core.Search;
using Gimano.Core.Support;

namespace Gimano.Tests;

/// <summary>"Finding places" building blocks (docs/api.md): normalising, aliases, query shapes, misses, chains, language.</summary>
public class FindingPlacesUnitTests
{
    [Theory]
    [InlineData("Family Mart", "FamilyMart")]
    [InlineData("7 eleven", "7-Eleven")]
    [InlineData("7eleven", "7-Eleven")]
    [InlineData("McDonalds", "McDonald's")]
    [InlineData("Lotus's", "LOTUS'S")]
    [InlineData("Kedai Mamak & Roti", "kedai mamak dan roti")]
    [InlineData("Café Résumé", "cafe resume")]
    public void Normalised_compact_forms_match(string a, string b) => Assert.Equal(TextNormalizer.Compact(a), TextNormalizer.Compact(b));

    [Fact]
    public void Normalise_folds_punctuation_and_spaces()
    {
        Assert.Equal("jalan ss2 24", TextNormalizer.Normalize("  Jalan  SS2/24 "));
        Assert.Equal("a dan w", TextNormalizer.Normalize("A&W"));
        Assert.Equal(["family", "familymart", "familymartss15"], TextNormalizer.NameKeys("Family Mart SS15"));
    }

    [Fact]
    public void Embedded_alias_file_loads_with_brands_renames_dialects_and_categories()
    {
        var t = AliasTable.Embedded();
        Assert.True(t.Entries.Count > 50);
        Assert.Contains("lotuss", t.Rewrites("tesco"));
        Assert.Contains("lotuss ss2", t.Rewrites("tesco ss2"));
        Assert.Contains("mcdonalds", t.Rewrites("mcd"));
        Assert.Contains("7 eleven", t.Rewrites("7e"));
        Assert.Contains("jalan ss2", t.Rewrites("jln ss2"));
        Assert.Equal(2, t.Rewrites("kk").Count);   // klinik kesihatan AND Kota Kinabalu
        Assert.Empty(t.Rewrites("kedai ali"));
        Assert.Equal([PlaceCategory.Fuel], t.Category("minyak"));
        Assert.Equal([PlaceCategory.Fuel], t.Category("stesen minyak"));
        Assert.Equal([PlaceCategory.Toilets], t.Category("tandas"));
        Assert.Equal([PlaceCategory.Surau, PlaceCategory.Mosque], t.Category("surau"));
        Assert.Equal([PlaceCategory.Atm], t.Category("atm"));
        Assert.Equal([PlaceCategory.Parking], t.Category("parkir"));
        Assert.Equal([PlaceCategory.Clinic], t.Category("klinik"));
        Assert.Equal([PlaceCategory.Pharmacy], t.Category("farmasi"));
        Assert.Equal([PlaceCategory.Convenience], t.Category("kedai runcit"));
        Assert.Null(t.Category("petronas"));
    }

    [Fact]
    public void Alias_csv_parsing_handles_comments_quotes_and_bad_rows()
    {
        var t = AliasTable.Parse(new StringReader("""
            alias,canonical,kind,locale,note
            # comment
            "a, b",Alpha,brand,en,"quoted, note"
            x,y,nonsense,en,
            tandas,toilets|nope,category,ms,
            """));
        Assert.Equal(2, t.Entries.Count);
        Assert.Equal("quoted, note", t.Entries[0].Note);
        Assert.Contains("alpha", t.Rewrites("a b"));
        Assert.Equal([PlaceCategory.Toilets], t.Category("tandas"));
    }

    [Theory]
    [InlineData("3.1478, 101.6153", 3.1478, 101.6153)]
    [InlineData("3.1478 101.6153", 3.1478, 101.6153)]
    [InlineData("3.1478,101.6153", 3.1478, 101.6153)]
    [InlineData("-6.2,106.8", -6.2, 106.8)]
    public void Coordinates_parse(string q, double lat, double lng)
    {
        var c = Assert.IsType<CoordinateQuery>(QueryParser.Parse(q));
        Assert.Equal(lat, c.Lat, 6);
        Assert.Equal(lng, c.Lng, 6);
    }

    [Theory]
    [InlineData("12 34")]          // no decimals: house number + something
    [InlineData("95.1, 101.6")]    // latitude out of range
    public void Not_coordinates(string q) => Assert.IsNotType<CoordinateQuery>(QueryParser.Parse(q));

    [Fact]
    public void Plus_codes_decode_full_and_short()
    {
        // Open Location Code README example: Zurich.
        var (zl, zg) = OpenLocationCode.Decode("8FVC9G8F+6X");
        Assert.Equal(47.3656, zl, 3);
        Assert.Equal(8.5249, zg, 3);

        var code = OpenLocationCode.Encode(3.1478, 101.6153);
        var full = Assert.IsType<PlusCodeQuery>(QueryParser.Parse(code));
        Assert.True(GeoMath.HaversineM(full.Lat, full.Lng, 3.1478, 101.6153) < 15);

        var shortCode = code[4..];   // e.g. "4QPH+4C": recovered against a nearby reference
        var s = Assert.IsType<PlusCodeQuery>(QueryParser.Parse(shortCode, 3.10, 101.60));
        Assert.True(GeoMath.HaversineM(s.Lat, s.Lng, 3.1478, 101.6153) < 15);
        var withPlace = Assert.IsType<PlusCodeQuery>(QueryParser.Parse(shortCode + " Petaling Jaya", 1.3, 103.8, _ => (3.107, 101.606)));
        Assert.True(GeoMath.HaversineM(withPlace.Lat, withPlace.Lng, 3.1478, 101.6153) < 15);
        Assert.False(OpenLocationCode.IsValid("HELLO+WORLD"));

        // KLCC (Suria KLCC / Petronas Twin Towers): full code and short code with "Kuala Lumpur".
        var klcc = Assert.IsType<PlusCodeQuery>(QueryParser.Parse("6PM35P56+5W"));
        Assert.True(GeoMath.HaversineM(klcc.Lat, klcc.Lng, 3.1579, 101.7123) < 15, $"{klcc.Lat},{klcc.Lng}");
        var klccShort = Assert.IsType<PlusCodeQuery>(QueryParser.Parse("5P56+5W Kuala Lumpur", 1.35, 103.82, _ => (3.139, 101.6869)));
        Assert.True(GeoMath.HaversineM(klccShort.Lat, klccShort.Lng, 3.1579, 101.7123) < 15);
    }

    [Fact]
    public void Postcodes_and_street_addresses_parse()
    {
        Assert.Equal("47300", Assert.IsType<PostcodeQuery>(QueryParser.Parse("47300")).Postcode);
        var a = Assert.IsType<StreetQuery>(QueryParser.Parse("12 Jalan SS2/24"));
        Assert.Equal(("12", "Jalan SS2/24"), (a.HouseNumber, a.Street));
        var b = Assert.IsType<StreetQuery>(QueryParser.Parse("No. 12A, Jln SS 2/24, 47300 Petaling Jaya"));
        Assert.Equal(("12A", "Jln SS 2/24"), (b.HouseNumber, b.Street));
        var c = Assert.IsType<StreetQuery>(QueryParser.Parse("Jalan SS2/24"));
        Assert.Null(c.HouseNumber);
        Assert.Null(QueryParser.Parse("jalan"));
        Assert.Null(QueryParser.Parse("kfc ss2"));
        Assert.Equal(QueryParser.StreetKey("Jalan SS 2/24"), QueryParser.StreetKey("jln ss2/24"));
        Assert.Equal("jalan ss 2 24", QueryParser.StreetKey("Jalan SS2/24"));
    }

    [Theory]
    [InlineData("kedai ali 012-345 6789", "kedai ali")]
    [InlineData("ali@example.com kedai", "kedai")]
    [InlineData("12 Jalan SS2/24", "jalan ss2")]
    [InlineData("47300", null)]
    [InlineData("Famly Mart", "famly mart")]
    public void Miss_text_is_scrubbed(string raw, string? expected) => Assert.Equal(expected, SearchMisses.Scrub(raw));

    [Fact]
    public void Miss_cell_and_week_are_coarse()
    {
        Assert.Equal("3.1,101.6", SearchMisses.Cell(3.1478, 101.6153));
        Assert.Equal("", SearchMisses.Cell(null, null));
        Assert.Equal("2026-W43", SearchMisses.Week(new DateTimeOffset(2026, 10, 23, 6, 0, 0, TimeSpan.Zero)));
        Assert.Equal("2026-W01", SearchMisses.Week(new DateTimeOffset(2025, 12, 29, 0, 0, 0, TimeSpan.Zero)));
        Assert.True(string.CompareOrdinal("2026-W09", "2026-W10") < 0);
    }

    private sealed record R(string Name, double Dist);

    [Fact]
    public void Chains_are_nearest_first_within_their_slots()
    {
        var ranked = new[] { new R("7 eleven", 13_700), new R("Petronas", 900), new R("7-Eleven", 623), new R("Lotus's", 2000), new R("7-ELEVEN", 1500) };
        var o = SearchResults.ChainsNearestFirst(ranked, r => SearchResults.ChainKey(r.Name, null), r => r.Dist);
        Assert.Equal("familymart", SearchResults.ChainKey("Family Mart Damansara", "FamilyMart"));
        Assert.Equal(["7-Eleven", "Petronas", "7-ELEVEN", "Lotus's", "7 eleven"], o.Select(r => r.Name).ToArray());
    }

    [Theory]
    [InlineData(null, "en")]
    [InlineData("ms-MY,ms;q=0.9,en;q=0.8", "ms")]
    [InlineData("en-GB,en;q=0.9,ms;q=0.8", "en")]
    [InlineData("zh-CN,ms;q=0.5", "ms")]
    [InlineData("fr-FR", "en")]
    [InlineData("en;q=0.4, ms;q=0.6", "ms")]
    public void Language_follows_accept_language(string? header, string lang) => Assert.Equal(lang, L10n.FromAcceptLanguage(header));

    [Fact]
    public void Server_text_in_both_languages()
    {
        Assert.Equal("Tidak ditemui", L10n.ProblemTitle("Not found", "ms"));
        Assert.Equal("Not found", L10n.ProblemTitle("Not found", "en"));
        Assert.Equal("City, Selangor", L10n.AreaAddress("Bandar raya, Selangor", "en"));
        Assert.Equal("Bandar raya, Selangor", L10n.AreaAddress("Bandar raya, Selangor", "ms"));
        Assert.Equal("State / Territory", L10n.AreaAddress("Negeri / Wilayah", "en"));

        var item = new InboxItem { Kind = InboxKind.PlacePublished, Title = "stored", Subject = "Kedai Ali" };
        Assert.Equal("Kedai Ali is now verified", InboxRules.Render(item, "en").Title);
        Assert.Equal("Kedai Ali kini disahkan", InboxRules.Render(item, "ms").Title);
        var legacy = new InboxItem { Kind = InboxKind.PlacePublished, Title = "stored", Body = "b" };
        Assert.Equal("stored", InboxRules.Render(legacy, "en").Title);   // pre-subject rows keep their text
        var thanks = new InboxItem { Kind = InboxKind.ReportThanked, Title = "x", Count = 3 };
        Assert.Equal("3 pemandu berterima kasih kepada anda", InboxRules.Render(thanks, "ms").Title);
    }

    [Fact]
    public void Reverse_label()
    {
        Assert.Equal("Dekat 18, Jalan SS 2/24, SS2, Petaling Jaya", SearchService.ReverseLabel("18", "Jalan SS 2/24", "SS2", "Petaling Jaya", "ms"));
        Assert.Equal("Near Jalan SS 2/24, Petaling Jaya", SearchService.ReverseLabel(null, "Jalan SS 2/24", null, "Petaling Jaya", "en"));
        Assert.Equal("Pin di peta", SearchService.ReverseLabel(null, null, null, null, "ms"));
    }

    [Fact]
    public void Brand_plus_area_split_and_typo_distance()
    {
        var areas = new AreaLocator([
            new AreaRef("n1", "SS2", "suburb", 3.1185, 101.6230, 4),
            new AreaRef("n2", "Shah Alam", "city", 3.0733, 101.5185, 12),
            new AreaRef("n3", "Petaling Jaya", "city", 3.1073, 101.6067, 12),
        ]);
        Assert.Equal(("SS2", "kfc"), Split("kfc ss2"));
        Assert.Equal(("Shah Alam", "mydin"), Split("mydin shah alam"));
        Assert.Equal(("Shah Alam", "minyak"), Split("shah alam minyak"));
        Assert.Null(SearchPipeline.SplitArea("shah alam", areas, null, null) is { } x && x.Remainder == "" ? (object)1 : null);
        Assert.Null(SearchPipeline.SplitArea("petronas", areas, null, null));
        Assert.Equal("SS2, Petaling Jaya", areas.Label(3.1190, 101.6220));
        Assert.Equal(1, SearchPipeline.Levenshtein("tandass", "tandas"));
        Assert.True(SearchPipeline.IsCloseSpelling("lotosmutiara", "Lotus's Mutiara Damansara", 2, "lotos mutiara"));
        Assert.True(SearchPipeline.IsCloseSpelling("famlymart", "FamilyMart", 2, "famly mart"));
        Assert.False(SearchPipeline.IsCloseSpelling("kedairuncitpakali", "Kedai Runcit Pak Abu", 4, "kedai runcit pak ali"));
        Assert.Equal("FamilyMart", SearchPipeline.CorrectedFor(["famly mart"], [S("FamilyMart", "FamilyMart")]));
        Assert.Null(SearchPipeline.CorrectedFor(["family mart"], [S("Family Mart Damansara", "FamilyMart")]));
        Assert.Null(SearchPipeline.CorrectedFor(["petronas zaaba"], [S("Petronas", "Petronas", "Persiaran Zaaba")]));
        Assert.Null(SearchPipeline.CorrectedFor(null, [S("Toilet", null)]));
        static PlaceSummary S(string name, string? brand, string address = "") => new("n1", name, PlaceCategory.Other, address, 3, 101, null, brand);
        Assert.False(SearchPipeline.IsCloseSpelling("lotosmutiara", "Motor Leton", 2));
        Assert.Equal("", SearchResults.ChainKey("Toilet", null));

        (string, string)? Split(string q) => SearchPipeline.SplitArea(q, areas, 3.1478, 101.6153) is { } s ? (s.Area.Name, s.Remainder) : null;
    }
}

/// <summary>
/// The red list against a real OpenSearch (GIMANO_OS_URL, as <see cref="OpenSearchIntegrationTests"/>), through the same
/// <see cref="SearchPipeline"/> the API uses, from Petaling Jaya (3.1478, 101.6153).
/// </summary>
public class OpenSearchIntegrationFindingPlacesTests
{
    private const double PjLat = 3.1478, PjLng = 101.6153;

    private static Place P(string id, string name, PlaceCategory cat, double lat, double lng, string? brand = null, string place = "", double pop = 0) => new()
    {
        Id = id, Name = name, Category = cat, Brand = brand, Location = new GeoPoint(lat, lng).ToPoint(), OsmType = "node",
        Address = cat == PlaceCategory.Area ? Core.Places.OsmCategoryMapper.AreaAddress(new Dictionary<string, string> { ["place"] = place }) : "",
        Popularity = pop > 0 ? pop : Core.Places.OsmCategoryMapper.Popularity(cat, new Dictionary<string, string> { ["name"] = name, ["place"] = place }),
        Tags = place.Length > 0 ? new() { ["place"] = place } : [],
    };

    private static readonly Place[] Places =
    [
        // areas
        P("a1", "Putrajaya", PlaceCategory.Area, 2.9264, 101.6964, place: "city", pop: 12),
        P("a2", "Cyberjaya", PlaceCategory.Area, 2.9213, 101.6559, place: "town", pop: 9),
        P("a3", "Shah Alam", PlaceCategory.Area, 3.0733, 101.5185, place: "city", pop: 12),
        P("a4", "SS2", PlaceCategory.Area, 3.1185, 101.6230, place: "suburb"),
        P("a5", "Petaling Jaya", PlaceCategory.Area, 3.1073, 101.6067, place: "city", pop: 12),
        P("a6", "Semenyih", PlaceCategory.Area, 2.9516, 101.8430, place: "town", pop: 8),
        P("g1", "Jabatan Kehakiman Syariah Wilayah Persekutuan Kuala Lumpur dan Putrajaya", PlaceCategory.Government, 3.0940, 101.6700),
        P("g2", "Hospital Putrajaya", PlaceCategory.Hospital, 2.9295, 101.6740),
        P("g3", "Taman Wetland Putrajaya", PlaceCategory.Tourism, 2.9150, 101.6870),
        P("g4", "Putrajaya Sentral", PlaceCategory.TransitStation, 2.9318, 101.6710),
        P("g5", "Taman Botani Putrajaya", PlaceCategory.Tourism, 2.8960, 101.6830),
        // KLCC
        P("k1", "Suria KLCC", PlaceCategory.Mall, 3.1579, 101.7123),
        P("k2", "Klinik Kesihatan KLCC Wellness Centre", PlaceCategory.Clinic, 3.1560, 101.7100),
        // chains
        P("f1", "FamilyMart", PlaceCategory.Convenience, 3.1365, 101.6225, "FamilyMart"),       // ~1.4 km
        P("f2", "Family Mart Damansara", PlaceCategory.Convenience, 3.1530, 101.6000, "FamilyMart"), // ~1.8 km
        P("f3", "FamilyMart SS2", PlaceCategory.Convenience, 3.1190, 101.6235, "FamilyMart"),   // ~3.3 km
        P("m1", "Mydin Subang Jaya", PlaceCategory.Supermarket, 3.0600, 101.5800, "Mydin"),
        P("r1", "Kedai Runcit Pak Abu", PlaceCategory.Convenience, 3.1100, 101.5900),
        P("c1", "KFC Damansara Uptown", PlaceCategory.FastFood, 3.1362, 101.6228, "KFC"),
        P("c2", "KFC Jalan SS2/55", PlaceCategory.FastFood, 3.1180, 101.6240, "KFC"),
        P("s1", "7-Eleven", PlaceCategory.Convenience, 3.1530, 101.6153, "7-Eleven"),          // ~580 m
        P("s2", "7 eleven", PlaceCategory.Convenience, 3.2710, 101.6153),                      // ~13.7 km
        P("s3", "7-Eleven Kelana Jaya", PlaceCategory.Convenience, 3.1050, 101.5950, "7-Eleven"),
        P("l1", "Lotus's Mutiara Damansara", PlaceCategory.Supermarket, 3.1570, 101.6090, "Lotus's"),
        P("d1", "McDonald's Damansara Uptown", PlaceCategory.FastFood, 3.1360, 101.6230, "McDonald's"),
        // categories
        P("p1", "Petronas Persiaran Zaaba", PlaceCategory.Fuel, 3.1400, 101.6250, "Petronas"),
        P("p2", "Shell SS2", PlaceCategory.Fuel, 3.1185, 101.6230, "Shell"),
        P("p3", "Petron Kepong", PlaceCategory.Fuel, 3.2100, 101.6400, "Petron"),
        P("t1", "Toilet", PlaceCategory.Toilets, 3.1490, 101.6160),
        P("t2", "Toilet", PlaceCategory.Toilets, 3.1600, 101.6300),
        P("q1", "Surau Al-Ikhlas", PlaceCategory.Surau, 3.1460, 101.6150),
        P("q2", "Masjid Damansara Utama", PlaceCategory.Mosque, 3.1350, 101.6200),
        P("b1", "ATM", PlaceCategory.Atm, 3.1485, 101.6158, "Maybank"),
        P("b2", "ATM", PlaceCategory.Atm, 3.1300, 101.6300, "CIMB"),
    ];

    [Fact]
    public async Task Red_list_from_petaling_jaya()
    {
        var prefix = $"gimano-fp{Guid.NewGuid():N}"[..20] + "-";
        var index = OpenSearchIntegrationTests.Create(prefix, out var client);
        if (index is null) { Assert.Skip("GIMANO_OS_URL not set"); return; }
        try
        {
            await index.EnsureIndexAsync();
            var docs = Places.Select(p => SearchQueryBuilder.ToDocument(p)).ToList();
            docs.Add(SearchQueryBuilder.StreetDocument("st-1", "Jalan SS 2/24", 3.1195, 101.6215, "SS2, Petaling Jaya", "MY"));
            docs.Add(SearchQueryBuilder.StreetDocument("st-2", "Jalan SS 2/25", 3.1199, 101.6219, "SS2, Petaling Jaya", "MY"));
            docs.Add(SearchQueryBuilder.AddressDocument(new AddressPoint("n900", "12", "Jalan SS 2/24", "47300", "Petaling Jaya", 3.1196, 101.6214, "MY"), "SS2"));
            docs.Add(SearchQueryBuilder.AddressDocument(new AddressPoint("n901", "14", "Jalan SS 2/24", "47300", "Petaling Jaya", 3.1197, 101.6216, "MY"), "SS2"));
            docs.Add(SearchQueryBuilder.PostcodeDocument("47300", 3.12, 101.62, "Petaling Jaya", "MY"));
            await index.BulkIndexAsync(docs);
            await index.RefreshAsync();

            var areas = new AreaLocator(Places.Where(p => p.Category == PlaceCategory.Area)
                .Select(p => new AreaRef(p.Id, p.Name, p.Tags["place"], p.Location.Y, p.Location.X, p.Popularity)));
            var table = AliasTable.Embedded();
            var pipeline = new SearchPipeline(index, () => table);
            var failures = new List<string>();

            var corrected = new Dictionary<string, string?>();
            async Task Expect(string q, Func<List<PlaceSummary>, SearchInterpretation, bool> ok, string what, int limit = 5)
            {
                var parsed = QueryParser.Parse(q, PjLat, PjLng);
                var (r, i, c) = await pipeline.RunAsync(new SearchRequest(q, PjLat, PjLng, Limit: limit), parsed, areas, ct: default);
                corrected[q] = c;
                if (!ok(r, i)) failures.Add($"Assert-rank '{q}' [{i.Kind}]: expected {what}; got {string.Join(" | ", r.Select(h => $"{h.Id} {h.Name} {h.DistanceM}m"))}");
            }
            void ExpectCorrected(string q, string? expected)
            {
                if (corrected.GetValueOrDefault(q) != expected) failures.Add($"Assert-rank '{q}': correctedQuery expected '{expected}', got '{corrected.GetValueOrDefault(q)}'");
            }
            static bool Ascending(List<PlaceSummary> r) => r.Zip(r.Skip(1)).All(p => p.First.DistanceM <= p.Second.DistanceM);

            await Expect("Family Mart", (r, _) => r.Count >= 3 && r.Take(3).All(h => h.Brand == "FamilyMart") && r[0].Id == "f1"
                && r[0].ChainGroup is { CountNearby: >= 3 }, "FamilyMarts nearest first, chain group");
            await Expect("famly mart", (r, _) => r.Count > 0 && r[0].Brand == "FamilyMart", "a FamilyMart");
            await Expect("myddin", (r, _) => r.Count > 0 && r[0].Id == "m1", "Mydin");
            await Expect("kfc ss2", (r, _) => r.Count > 0 && r[0].Id == "c2", "the KFC in SS2 (not the nearer Uptown one)");
            await Expect("7 eleven", (r, _) => r.Count >= 2 && r[0].Id == "s1" && r.FindIndex(h => h.Id == "s2") > r.FindIndex(h => h.Id == "s3"),
                "the 580 m 7-Eleven first, the 13.7 km '7 eleven' after the closer ones");
            await Expect("tesco", (r, _) => r.Count > 0 && r[0].Id == "l1", "Lotus's");
            await Expect("mcd", (r, _) => r.Count > 0 && r[0].Id == "d1", "McDonald's");
            await Expect("minyak", (r, i) => i.Kind == "category" && r.Count == 3 && r.All(h => h.Category == PlaceCategory.Fuel) && Ascending(r), "fuel, nearest first");
            await Expect("stesen minyak", (r, i) => i.Kind == "category" && r[0].Id == "p1", "fuel, nearest first");
            await Expect("tandas", (r, i) => i.Kind == "category" && r[0].Id == "t1" && r.All(h => h.Category == PlaceCategory.Toilets && h.ChainGroup is null),
                "toilets, nearest first, not a 'Toilet' chain");
            await Expect("surau", (r, _) => r[0].Id == "q1" && r.Any(h => h.Id == "q2") && Ascending(r), "surau and masjid, nearest first");
            await Expect("atm", (r, _) => r[0].Id == "b1" && r.Count == 2, "ATMs, nearest first");
            await Expect("minyak ss2", (r, i) => i.Kind == "category_area" && r[0].Id == "p2", "fuel nearest to SS2");
            await Expect("12 Jalan SS2/24", (r, i) => i.Kind == "address" && r[0].Id == "ad-n900", "the house address");
            await Expect("Jalan SS2/24", (r, i) => i.Kind == "street" && r[0].Id == "st-1", "the street, not 2/25");
            await Expect("47300", (r, i) => i.Kind == "postcode" && r[0].Id == "pc-47300", "the postcode area");
            await Expect("Putrajaya", (r, _) => r[0].Id == "a1" && r.FindIndex(h => h.Id == "g4") < r.FindIndex(h => h.Id == "g1"),
                "Putrajaya first; a name starting with Putrajaya above the long government name 8 km away");
            await Expect("KLCC", (r, _) => r[0].Id == "k1", "Suria KLCC first");
            await Expect("Cyberjaya", (r, _) => r[0].Id == "a2", "Cyberjaya");
            await Expect("Shah Alam", (r, _) => r[0].Id == "a3", "Shah Alam");
            await Expect("Semenyih", (r, _) => r[0].Id == "a6", "Semenyih");
            await Expect("petronas", (r, _) => r[0].Id == "p1", "the Petronas station");
            await Expect("Hospital Putrajaya", (r, _) => r[0].Id == "g2", "an exact name is not split into category + area");
            await Expect("kedai runcit pak ali", (r, _) => r.Count == 0, "nothing: 'ali' is not 'Abu' (no silent substitution)");

            ExpectCorrected("famly mart", "FamilyMart");
            ExpectCorrected("myddin", "Mydin");
            ExpectCorrected("Family Mart", null);
            ExpectCorrected("7 eleven", null);
            ExpectCorrected("tesco", null);      // an alias, not a typo
            ExpectCorrected("kfc ss2", null);
            ExpectCorrected("Putrajaya", null);
            var pakAli = await pipeline.SuggestAsync(new SearchRequest("kedai runcit pak ali", PjLat, PjLng), default);
            if (pakAli.Any(s => s.PlaceId is not null) || !pakAli.Any(s => s.Category == PlaceCategory.Convenience))
                failures.Add($"Assert-rank suggestions 'kedai runcit pak ali': expected only 'kedai runcit' nearby; got {string.Join(" | ", pakAli)}");

            var none = await pipeline.RunAsync(new SearchRequest("qzxjv", PjLat, PjLng), null, areas, default);
            if (none.Results.Count != 0) failures.Add("Assert-rank 'qzxjv': expected nothing");
            var sugg = await pipeline.SuggestAsync(new SearchRequest("tandass", PjLat, PjLng), default);
            if (!sugg.Any(s => s.Category == PlaceCategory.Toilets && s.Count == 2)) failures.Add($"Assert-rank suggestions 'tandass': {string.Join(" | ", sugg)}");
            var dym = await pipeline.SuggestAsync(new SearchRequest("Lotos Mutiara", PjLat, PjLng), default);
            if (!dym.Any(s => s.PlaceId == "l1")) failures.Add($"Assert-rank did-you-mean 'Lotos Mutiara': {string.Join(" | ", dym)}");
            var far = await pipeline.SuggestAsync(new SearchRequest("Motorr Letonx Qq", PjLat, PjLng), default);
            if (far.Any(s => s.PlaceId is not null && s.Label != "Motor Leton")) failures.Add($"Assert-rank did-you-mean must not invent: {string.Join(" | ", far)}");

            Assert.True(failures.Count == 0, string.Join("\n", failures));
        }
        finally
        {
            var all = await client.Indices.GetAsync<OpenSearch.Net.StringResponse>(prefix + "*");
            if (all.Success && System.Text.Json.Nodes.JsonNode.Parse(all.Body) is System.Text.Json.Nodes.JsonObject o)
                foreach (var (name, _) in o) await client.Indices.DeleteAsync<OpenSearch.Net.StringResponse>(name);
        }
    }
}
