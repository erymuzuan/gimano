using Gimano.Core.Contracts;
using Gimano.Core.Places;
using Gimano.Importer;

namespace Gimano.Tests;

public class OsmCategoryMapperTests
{
    private static Dictionary<string, string> T(params string[] kv)
    {
        var d = new Dictionary<string, string>();
        for (var i = 0; i < kv.Length; i += 2) d[kv[i]] = kv[i + 1];
        return d;
    }

    [Theory]
    [InlineData(PlaceCategory.Fuel, "amenity", "fuel")]
    [InlineData(PlaceCategory.EvCharging, "amenity", "charging_station")]
    [InlineData(PlaceCategory.RestArea, "highway", "rest_area")]
    [InlineData(PlaceCategory.RestArea, "highway", "services")]
    [InlineData(PlaceCategory.TollBooth, "barrier", "toll_booth")]
    [InlineData(PlaceCategory.Parking, "amenity", "parking")]
    [InlineData(PlaceCategory.Restaurant, "amenity", "restaurant")]
    [InlineData(PlaceCategory.Cafe, "amenity", "cafe")]
    [InlineData(PlaceCategory.FastFood, "amenity", "fast_food")]
    [InlineData(PlaceCategory.Mall, "shop", "mall")]
    [InlineData(PlaceCategory.Supermarket, "shop", "supermarket")]
    [InlineData(PlaceCategory.Convenience, "shop", "convenience")]
    [InlineData(PlaceCategory.Hospital, "amenity", "hospital")]
    [InlineData(PlaceCategory.Clinic, "amenity", "clinic")]
    [InlineData(PlaceCategory.Pharmacy, "amenity", "pharmacy")]
    [InlineData(PlaceCategory.Police, "amenity", "police")]
    [InlineData(PlaceCategory.School, "amenity", "school")]
    [InlineData(PlaceCategory.University, "amenity", "university")]
    [InlineData(PlaceCategory.Hotel, "tourism", "hotel")]
    [InlineData(PlaceCategory.Atm, "amenity", "atm")]
    [InlineData(PlaceCategory.Bank, "amenity", "bank")]
    [InlineData(PlaceCategory.CarRepair, "shop", "car_repair")]
    [InlineData(PlaceCategory.CarWash, "amenity", "car_wash")]
    [InlineData(PlaceCategory.Tourism, "tourism", "attraction")]
    [InlineData(PlaceCategory.TransitStation, "railway", "station")]
    [InlineData(PlaceCategory.Airport, "aeroway", "aerodrome")]
    [InlineData(PlaceCategory.Government, "office", "government")]
    public void Maps_single_tag(PlaceCategory expected, string key, string value) =>
        Assert.Equal(expected, OsmCategoryMapper.Map(T(key, value, "name", "X")));

    [Fact]
    public void Muslim_places_of_worship_split_into_mosque_and_surau()
    {
        Assert.Equal(PlaceCategory.Mosque, OsmCategoryMapper.Map(T("amenity", "place_of_worship", "religion", "muslim", "name", "Masjid Negara")));
        Assert.Equal(PlaceCategory.Surau, OsmCategoryMapper.Map(T("amenity", "place_of_worship", "religion", "muslim", "name", "Surau Al-Hidayah")));
        Assert.Equal(PlaceCategory.Surau, OsmCategoryMapper.Map(T("amenity", "place_of_worship", "religion", "muslim", "name", "Musolla R&R Ayer Keroh")));
    }

    [Theory]
    [InlineData("buddhist", PlaceCategory.Temple)]
    [InlineData("hindu", PlaceCategory.Temple)]
    [InlineData("taoist", PlaceCategory.Temple)]
    [InlineData("christian", PlaceCategory.Church)]
    [InlineData("bahai", PlaceCategory.Other)]
    public void Other_religions(string religion, PlaceCategory expected) =>
        Assert.Equal(expected, OsmCategoryMapper.Map(T("amenity", "place_of_worship", "religion", religion)));

    [Fact]
    public void Unmapped_tags_return_null()
    {
        Assert.Null(OsmCategoryMapper.Map(T("highway", "residential")));
        Assert.Null(OsmCategoryMapper.Map(T("building", "yes")));
        Assert.Null(OsmCategoryMapper.Map(T()));
    }

    [Fact]
    public void Unnamed_features_import_only_for_driving_categories()
    {
        Assert.True(OsmCategoryMapper.ShouldImport(PlaceCategory.Fuel, T("amenity", "fuel")));
        Assert.False(OsmCategoryMapper.ShouldImport(PlaceCategory.Parking, T("amenity", "parking")));
        Assert.True(OsmCategoryMapper.ShouldImport(PlaceCategory.Cafe, T("amenity", "cafe", "brand", "ZUS Coffee")));
    }

    [Fact]
    public void Display_name_falls_back_to_brand_then_category()
    {
        Assert.Equal("Petronas", OsmCategoryMapper.DisplayName(PlaceCategory.Fuel, T("amenity", "fuel", "brand", "Petronas")));
        Assert.Equal("Petrol station", OsmCategoryMapper.DisplayName(PlaceCategory.Fuel, T("amenity", "fuel")));
        Assert.Equal("Petronas Jalan Templer", OsmCategoryMapper.DisplayName(PlaceCategory.Fuel, T("name", "Petronas Jalan Templer", "brand", "Petronas")));
    }

    [Fact]
    public void Builds_address_from_addr_tags()
    {
        var tags = T("addr:housenumber", "12", "addr:street", "Jalan SS 2/24", "addr:postcode", "47300", "addr:city", "Petaling Jaya", "addr:state", "Selangor");
        Assert.Equal("12, Jalan SS 2/24, 47300 Petaling Jaya, Selangor", OsmCategoryMapper.BuildAddress(tags));
        Assert.Equal("", OsmCategoryMapper.BuildAddress(T("name", "x")));
        Assert.Equal("Full", OsmCategoryMapper.BuildAddress(T("addr:full", "Full", "addr:street", "S")));
    }

    [Fact]
    public void Extracts_amenities()
    {
        var a = OsmCategoryMapper.Amenities(T("toilets", "yes", "shop", "convenience", "fuel:diesel", "yes", "fuel:octane_95", "yes", "payment:touchngo", "yes"));
        Assert.Equal(["toilets", "touch_n_go", "shop", "diesel", "ron95"], a);
    }

    [Fact]
    public void Importer_builds_place_with_country_and_ids()
    {
        var p = OsmImporter.ToPlace("n", 123, PlaceCategory.Fuel, T("amenity", "fuel", "brand", "Shell", "opening_hours", "24/7"), 1.3521, 103.8198);
        Assert.Equal("n123", p.Id);
        Assert.Equal("node", p.OsmType);
        Assert.Equal("SG", p.Country);
        Assert.Equal("Shell", p.Brand);
        Assert.Equal("Shell", p.Name);
        Assert.Equal(103.8198, p.Location.X, 6);
    }
}

public class CountryResolverTests
{
    [Theory]
    [InlineData(3.1390, 101.6869, "MY")]  // Kuala Lumpur
    [InlineData(1.4927, 103.7414, "MY")]  // Johor Bahru
    [InlineData(1.4655, 103.7578, "MY")]  // JB CIQ, north of the strait
    [InlineData(1.3521, 103.8198, "SG")]  // Singapore centre
    [InlineData(1.3644, 103.9915, "SG")]  // Changi Airport
    [InlineData(1.3329, 103.6430, "SG")]  // Tuas
    [InlineData(4.9031, 114.9398, "BN")]  // Bandar Seri Begawan
    [InlineData(4.5833, 114.2000, "BN")]  // Kuala Belait
    [InlineData(4.3995, 113.9914, "MY")]  // Miri
    [InlineData(5.9804, 116.0735, "MY")]  // Kota Kinabalu
    [InlineData(4.7499, 115.0000, "MY")]  // Limbang (between Brunei's two parts)
    public void Resolves_country(double lat, double lng, string expected) =>
        Assert.Equal(expected, CountryResolver.Resolve(lat, lng));

    [Fact]
    public void Addr_country_tag_wins() => Assert.Equal("SG", CountryResolver.Resolve(3.1, 101.6, "sg"));
}

public class OpeningHoursTests
{
    private static DateTime At(DayOfWeek d, int h, int m = 0)
    {
        var monday = new DateTime(2026, 10, 19); // a Monday
        return monday.AddDays(((int)d + 6) % 7).AddHours(h).AddMinutes(m);
    }

    [Fact]
    public void Always_open() => Assert.True(OpeningHours.IsOpen("24/7", At(DayOfWeek.Sunday, 3)));

    [Fact]
    public void Weekday_ranges()
    {
        const string s = "Mo-Fr 08:00-17:00; Sa 08:00-12:00; Su off";
        Assert.True(OpeningHours.IsOpen(s, At(DayOfWeek.Wednesday, 9)));
        Assert.False(OpeningHours.IsOpen(s, At(DayOfWeek.Wednesday, 17, 30)));
        Assert.True(OpeningHours.IsOpen(s, At(DayOfWeek.Saturday, 11, 59)));
        Assert.False(OpeningHours.IsOpen(s, At(DayOfWeek.Saturday, 12)));
        Assert.False(OpeningHours.IsOpen(s, At(DayOfWeek.Sunday, 10)));
    }

    [Fact]
    public void Split_ranges_and_overnight()
    {
        Assert.False(OpeningHours.IsOpen("Mo-Su 10:00-14:00,17:00-22:00", At(DayOfWeek.Monday, 15)));
        Assert.True(OpeningHours.IsOpen("Mo-Su 10:00-14:00,17:00-22:00", At(DayOfWeek.Monday, 18)));
        Assert.True(OpeningHours.IsOpen("Fr 18:00-02:00", At(DayOfWeek.Saturday, 1)));
        Assert.False(OpeningHours.IsOpen("Fr 18:00-02:00", At(DayOfWeek.Saturday, 3)));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("PH off")]
    [InlineData("sunrise-sunset")]
    public void Unknown_specs_return_null(string? spec) => Assert.Null(OpeningHours.IsOpen(spec, At(DayOfWeek.Monday, 9)));
}

public class RoadRulesTests
{
    [Theory]
    [InlineData("motorway", true)]
    [InlineData("residential", true)]
    [InlineData("footway", false)]
    [InlineData("service", false)]
    [InlineData(null, false)]
    public void Road_classes(string? highway, bool expected) => Assert.Equal(expected, RoadRules.IsRoad(highway));

    [Theory]
    [InlineData("110", 110.0)]
    [InlineData("90 km/h", 90.0)]
    [InlineData("30 mph", 48.0)]
    [InlineData("signals", null)]
    public void Parses_maxspeed(string s, double? expected) => Assert.Equal(expected, RoadRules.ParseMaxSpeed(s));

    [Fact]
    public void Free_flow_defaults_and_limits()
    {
        Assert.Equal(110, RoadRules.FreeFlowKmh("motorway", null));
        Assert.Equal(30, RoadRules.FreeFlowKmh("residential", null));
        Assert.Equal(54, RoadRules.FreeFlowKmh("primary", "60"));
    }

    [Fact]
    public void Oneway_rules()
    {
        Assert.Equal((true, false), RoadRules.Oneway("motorway", null, null));
        Assert.Equal((true, false), RoadRules.Oneway("tertiary", null, "roundabout"));
        Assert.Equal((true, true), RoadRules.Oneway("primary", "-1", null));
        Assert.Equal((false, false), RoadRules.Oneway("primary", null, null));
        Assert.Equal((false, false), RoadRules.Oneway("motorway", "no", null));
    }
}
