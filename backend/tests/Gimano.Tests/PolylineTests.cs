using Gimano.Core.Geo;

namespace Gimano.Tests;

public class PolylineTests
{
    [Fact]
    public void Encodes_google_reference_example_at_precision_5()
    {
        // https://developers.google.com/maps/documentation/utilities/polylinealgorithm
        var pts = new[] { new GeoPoint(38.5, -120.2), new GeoPoint(40.7, -120.95), new GeoPoint(43.252, -126.453) };
        Assert.Equal("_p~iF~ps|U_ulLnnqC_mqNvxq`@", Polyline.Encode(pts, precision: 5));
    }

    [Fact]
    public void Decodes_google_reference_example_at_precision_5()
    {
        var pts = Polyline.Decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision: 5);
        Assert.Equal(3, pts.Count);
        Assert.Equal(38.5, pts[0].Lat, 5);
        Assert.Equal(-120.2, pts[0].Lng, 5);
        Assert.Equal(43.252, pts[2].Lat, 5);
        Assert.Equal(-126.453, pts[2].Lng, 5);
    }

    [Fact]
    public void Round_trips_malaysian_coordinates_at_precision_6()
    {
        var pts = new[]
        {
            new GeoPoint(3.139003, 101.686855), // KL
            new GeoPoint(3.073838, 101.518347), // Shah Alam
            new GeoPoint(1.352083, 103.819836), // Singapore
            new GeoPoint(4.903052, 114.939821), // Bandar Seri Begawan
            new GeoPoint(-0.000001, -0.000001),
        };
        var decoded = Polyline.Decode(Polyline.Encode(pts));
        Assert.Equal(pts.Length, decoded.Count);
        for (var i = 0; i < pts.Length; i++)
        {
            Assert.Equal(pts[i].Lat, decoded[i].Lat, 6);
            Assert.Equal(pts[i].Lng, decoded[i].Lng, 6);
        }
    }

    [Fact]
    public void Default_precision_is_6_like_valhalla()
    {
        var p6 = Polyline.Encode([new GeoPoint(3.1, 101.6)]);
        var p5 = Polyline.Encode([new GeoPoint(3.1, 101.6)], 5);
        Assert.NotEqual(p5, p6);
        Assert.Equal(3.1, Polyline.Decode(p6)[0].Lat, 6);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    public void Empty_input_decodes_to_empty(string? s) => Assert.Empty(Polyline.Decode(s));

    [Fact]
    public void Truncated_polyline_throws() =>
        Assert.Throws<FormatException>(() => Polyline.Decode("_p~iF"));

    [Fact]
    public void Encode_empty_is_empty_string() => Assert.Equal("", Polyline.Encode([]));
}
