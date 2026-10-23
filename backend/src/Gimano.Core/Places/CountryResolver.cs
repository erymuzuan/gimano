using Gimano.Core.Geo;

namespace Gimano.Core.Places;

/// <summary>
/// Resolves MY / SG / BN for points inside the Geofabrik extract.
/// Uses simplified land-border polygons (accurate to a few hundred metres at the borders), which is
/// enough to scope search; an explicit <c>addr:country</c> tag always wins.
/// </summary>
public static class CountryResolver
{
    // Singapore main island + Jurong/Tuas + Pulau Ubin; the Johor Strait is the boundary.
    private static readonly GeoPoint[] Singapore =
    [
        new(1.3300, 103.6150), new(1.3700, 103.6400), new(1.4000, 103.6750), new(1.4350, 103.7000),
        new(1.4500, 103.7400), new(1.4520, 103.7700), new(1.4650, 103.8100), new(1.4400, 103.8600),
        new(1.4250, 103.9200), new(1.4300, 103.9700), new(1.4250, 104.0600), new(1.3900, 104.1000),
        new(1.3000, 104.1000), new(1.2500, 103.9000), new(1.1500, 103.7400), new(1.1900, 103.6000),
        new(1.2800, 103.6000),
    ];

    // Brunei: two parts (west incl. Bandar Seri Begawan, and Temburong).
    private static readonly GeoPoint[] BruneiWest =
    [
        new(5.0500, 115.0300), new(4.9700, 115.0600), new(4.8900, 115.0400), new(4.8000, 114.9950),
        new(4.7000, 114.9800), new(4.5500, 114.9000), new(4.3500, 114.8600), new(4.0200, 114.6500), new(4.1000, 114.5200), new(4.3500, 114.3300),
        new(4.5900, 114.1000), new(4.6500, 114.2000), new(4.8500, 114.5000), new(5.0000, 114.8000),
        new(5.0800, 114.9800),
    ];

    private static readonly GeoPoint[] BruneiEast =
    [
        new(4.9500, 115.1500), new(4.8800, 115.3600), new(4.7000, 115.3600), new(4.4500, 115.3000),
        new(4.3200, 115.2900), new(4.4000, 115.1500), new(4.6000, 115.0800), new(4.8500, 115.1300),
    ];

    public static string Resolve(double lat, double lng, string? addrCountry = null)
    {
        if (addrCountry is { Length: 2 } c && c.ToUpperInvariant() is "MY" or "SG" or "BN") return c.ToUpperInvariant();
        if (lat < 1.5 && lng > 103.55 && lng < 104.15 && Inside(Singapore, lat, lng)) return "SG";
        if (lat is > 3.9 and < 5.2 && lng is > 113.9 and < 115.5 &&
            (Inside(BruneiWest, lat, lng) || Inside(BruneiEast, lat, lng))) return "BN";
        return "MY";
    }

    internal static bool Inside(GeoPoint[] poly, double lat, double lng)
    {
        var inside = false;
        for (int i = 0, j = poly.Length - 1; i < poly.Length; j = i++)
        {
            var (yi, xi, yj, xj) = (poly[i].Lat, poly[i].Lng, poly[j].Lat, poly[j].Lng);
            if ((yi > lat) != (yj > lat) && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside;
        }
        return inside;
    }
}
