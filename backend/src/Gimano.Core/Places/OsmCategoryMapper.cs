using Gimano.Core.Contracts;

namespace Gimano.Core.Places;

/// <summary>Maps OSM tags to Gimano place categories (docs/api.md#place-categories).</summary>
public static class OsmCategoryMapper
{
    private static readonly Dictionary<string, PlaceCategory> Amenity = new()
    {
        ["fuel"] = PlaceCategory.Fuel,
        ["charging_station"] = PlaceCategory.EvCharging,
        ["parking"] = PlaceCategory.Parking,
        ["restaurant"] = PlaceCategory.Restaurant,
        ["food_court"] = PlaceCategory.Restaurant,
        ["cafe"] = PlaceCategory.Cafe,
        ["fast_food"] = PlaceCategory.FastFood,
        ["hospital"] = PlaceCategory.Hospital,
        ["clinic"] = PlaceCategory.Clinic,
        ["doctors"] = PlaceCategory.Clinic,
        ["dentist"] = PlaceCategory.Clinic,
        ["pharmacy"] = PlaceCategory.Pharmacy,
        ["police"] = PlaceCategory.Police,
        ["school"] = PlaceCategory.School,
        ["kindergarten"] = PlaceCategory.School,
        ["college"] = PlaceCategory.University,
        ["university"] = PlaceCategory.University,
        ["atm"] = PlaceCategory.Atm,
        ["bank"] = PlaceCategory.Bank,
        ["car_wash"] = PlaceCategory.CarWash,
        ["townhall"] = PlaceCategory.Government,
        ["courthouse"] = PlaceCategory.Government,
        ["bus_station"] = PlaceCategory.TransitStation,
        ["ferry_terminal"] = PlaceCategory.TransitStation,
    };

    private static readonly Dictionary<string, PlaceCategory> Shop = new()
    {
        ["mall"] = PlaceCategory.Mall,
        ["department_store"] = PlaceCategory.Mall,
        ["supermarket"] = PlaceCategory.Supermarket,
        ["wholesale"] = PlaceCategory.Supermarket,
        ["convenience"] = PlaceCategory.Convenience,
        ["car_repair"] = PlaceCategory.CarRepair,
        ["tyres"] = PlaceCategory.CarRepair,
        ["motorcycle_repair"] = PlaceCategory.CarRepair,
        ["chemist"] = PlaceCategory.Pharmacy,
    };

    private static readonly Dictionary<string, PlaceCategory> Tourism = new()
    {
        ["hotel"] = PlaceCategory.Hotel,
        ["motel"] = PlaceCategory.Hotel,
        ["guest_house"] = PlaceCategory.Hotel,
        ["hostel"] = PlaceCategory.Hotel,
        ["resort"] = PlaceCategory.Hotel,
        ["apartment"] = PlaceCategory.Hotel,
        ["attraction"] = PlaceCategory.Tourism,
        ["museum"] = PlaceCategory.Tourism,
        ["viewpoint"] = PlaceCategory.Tourism,
        ["theme_park"] = PlaceCategory.Tourism,
        ["zoo"] = PlaceCategory.Tourism,
        ["aquarium"] = PlaceCategory.Tourism,
        ["gallery"] = PlaceCategory.Tourism,
    };

    public static PlaceCategory? Map(IReadOnlyDictionary<string, string> tags)
    {
        string? Tag(string k) => tags.TryGetValue(k, out var v) ? v : null;

        var amenity = Tag("amenity");
        var religion = Tag("religion");
        var name = Tag("name") ?? "";

        if (amenity == "place_of_worship")
        {
            if (religion == "muslim")
            {
                // Musolla/surau are small prayer rooms; mosques are "masjid".
                var building = Tag("building");
                var isSurau = name.Contains("surau", StringComparison.OrdinalIgnoreCase)
                              || name.Contains("musolla", StringComparison.OrdinalIgnoreCase)
                              || name.Contains("musalla", StringComparison.OrdinalIgnoreCase)
                              || name.Contains("musholla", StringComparison.OrdinalIgnoreCase)
                              || Tag("place_of_worship") is "musalla" or "surau"
                              || building == "surau";
                return isSurau ? PlaceCategory.Surau : PlaceCategory.Mosque;
            }
            if (religion == "christian") return PlaceCategory.Church;
            if (religion is "buddhist" or "hindu" or "taoist" or "sikh" or "chinese_folk" or "confucian" or "jain")
                return PlaceCategory.Temple;
            return religion is null ? null : PlaceCategory.Other;
        }

        if (Tag("highway") == "rest_area" || Tag("highway") == "services") return PlaceCategory.RestArea;
        if (Tag("barrier") == "toll_booth" || Tag("highway") == "toll_gantry") return PlaceCategory.TollBooth;
        if (Tag("aeroway") is "aerodrome" or "terminal") return PlaceCategory.Airport;
        if (Tag("railway") is "station" or "halt" || Tag("public_transport") == "station") return PlaceCategory.TransitStation;
        if (Tag("office") == "government") return PlaceCategory.Government;

        if (amenity is not null && Amenity.TryGetValue(amenity, out var a))
        {
            // Unnamed parking and ATMs are noise for search.
            return a;
        }
        if (Tag("shop") is { } shop && Shop.TryGetValue(shop, out var s)) return s;
        if (Tag("tourism") is { } tourism && Tourism.TryGetValue(tourism, out var t)) return t;
        if (Tag("leisure") is "park" && name.Length > 0) return PlaceCategory.Tourism;

        return null;
    }

    /// <summary>Whether a mapped feature is worth importing (needs a name, except a few categories).</summary>
    public static bool ShouldImport(PlaceCategory category, IReadOnlyDictionary<string, string> tags) =>
        tags.ContainsKey("name") || tags.ContainsKey("brand") ||
        category is PlaceCategory.Fuel or PlaceCategory.EvCharging or PlaceCategory.TollBooth or PlaceCategory.RestArea;

    public static string DisplayName(PlaceCategory category, IReadOnlyDictionary<string, string> tags)
    {
        if (tags.TryGetValue("name", out var n) && n.Length > 0) return n;
        if (tags.TryGetValue("name:en", out var en) && en.Length > 0) return en;
        if (tags.TryGetValue("brand", out var b) && b.Length > 0) return b;
        return category switch
        {
            PlaceCategory.Fuel => "Petrol station",
            PlaceCategory.EvCharging => "EV charger",
            PlaceCategory.TollBooth => "Toll plaza",
            PlaceCategory.RestArea => "Rest area",
            _ => GimanoJson.ToWire(category).Replace('_', ' '),
        };
    }

    public static string? Brand(IReadOnlyDictionary<string, string> tags) =>
        tags.TryGetValue("brand", out var b) ? b : tags.TryGetValue("operator", out var o) && tags.ContainsKey("amenity") && tags["amenity"] == "fuel" ? o : null;

    public static string BuildAddress(IReadOnlyDictionary<string, string> tags)
    {
        if (tags.TryGetValue("addr:full", out var full) && full.Length > 0) return full;
        string? T(string k) => tags.TryGetValue(k, out var v) && v.Length > 0 ? v : null;
        var street = T("addr:street");
        var number = T("addr:housenumber");
        var line1 = number is not null && street is not null ? $"{number}, {street}" : street;
        var parts = new[] { line1, T("addr:suburb"), JoinPostcode(T("addr:postcode"), T("addr:city")), T("addr:state") }
            .Where(p => p is not null).Distinct();
        return string.Join(", ", parts);
    }

    private static string? JoinPostcode(string? postcode, string? city) =>
        (postcode, city) switch
        {
            (null, null) => null,
            (null, _) => city,
            (_, null) => postcode,
            _ => $"{postcode} {city}",
        };

    public static IReadOnlyList<string> Amenities(IReadOnlyDictionary<string, string> tags)
    {
        var list = new List<string>();
        void Add(string tag, string label, params string[] yes)
        {
            if (tags.TryGetValue(tag, out var v) && (yes.Length == 0 ? v is "yes" or "designated" : yes.Contains(v))) list.Add(label);
        }
        Add("toilets", "toilets");
        Add("toilets:wheelchair", "accessible_toilets");
        Add("wheelchair", "wheelchair", "yes", "limited");
        Add("payment:cards", "cards");
        Add("payment:credit_cards", "cards");
        Add("payment:ewallet", "ewallet");
        Add("payment:touchngo", "touch_n_go");
        Add("internet_access", "wifi", "wlan", "yes");
        Add("drive_through", "drive_through");
        Add("compressed_air", "air");
        Add("car_wash", "car_wash");
        Add("shop", "shop", "convenience");
        Add("atm", "atm");
        Add("fuel:diesel", "diesel");
        Add("fuel:octane_95", "ron95");
        Add("fuel:octane_97", "ron97");
        Add("fuel:octane_100", "ron100");
        Add("diet:halal", "halal", "yes", "only");
        Add("prayer_room", "prayer_room");
        return list.Distinct().ToList();
    }

    /// <summary>Tags kept on the place record (the full tag set is noisy).</summary>
    public static Dictionary<string, string> KeepTags(IReadOnlyDictionary<string, string> tags) =>
        tags.Where(kv => !kv.Key.StartsWith("source", StringComparison.Ordinal) && !kv.Key.StartsWith("note", StringComparison.Ordinal)
                         && !kv.Key.StartsWith("fixme", StringComparison.OrdinalIgnoreCase) && kv.Key != "created_by")
            .Take(60).ToDictionary(kv => kv.Key, kv => kv.Value);

    /// <summary>Static popularity prior used as a search boost.</summary>
    public static double Popularity(PlaceCategory category, IReadOnlyDictionary<string, string> tags)
    {
        double p = category switch
        {
            PlaceCategory.Mall or PlaceCategory.Airport or PlaceCategory.Hospital or PlaceCategory.University => 3,
            PlaceCategory.Fuel or PlaceCategory.RestArea or PlaceCategory.TransitStation or PlaceCategory.Tourism => 2,
            PlaceCategory.Supermarket or PlaceCategory.Mosque or PlaceCategory.Hotel or PlaceCategory.Government => 1.5,
            _ => 1,
        };
        if (tags.ContainsKey("wikidata") || tags.ContainsKey("wikipedia")) p += 2;
        if (tags.ContainsKey("brand")) p += 0.5;
        return p;
    }
}
