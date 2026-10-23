using System.Globalization;
using NetTopologySuite.Geometries;

namespace Gimano.Core.Geo;

public readonly record struct GeoPoint(double Lat, double Lng)
{
    public Point ToPoint() => GeoMath.Factory.CreatePoint(new Coordinate(Lng, Lat));
    public static GeoPoint From(Point p) => new(p.Y, p.X);
    public override string ToString() => string.Create(CultureInfo.InvariantCulture, $"{Lat:F6},{Lng:F6}");
}

/// <summary>[minLng, minLat, maxLng, maxLat]</summary>
public readonly record struct BBox(double MinLng, double MinLat, double MaxLng, double MaxLat)
{
    public bool Contains(double lat, double lng) =>
        lat >= MinLat && lat <= MaxLat && lng >= MinLng && lng <= MaxLng;

    public bool Intersects(BBox o) =>
        o.MinLng <= MaxLng && o.MaxLng >= MinLng && o.MinLat <= MaxLat && o.MaxLat >= MinLat;

    public double[] ToArray() => [MinLng, MinLat, MaxLng, MaxLat];

    public BBox Expand(double metres)
    {
        var dLat = metres / 111_320d;
        var dLng = metres / (111_320d * Math.Cos((MinLat + MaxLat) / 2 * Math.PI / 180));
        return new(MinLng - dLng, MinLat - dLat, MaxLng + dLng, MaxLat + dLat);
    }

    public Polygon ToPolygon() => GeoMath.Factory.CreatePolygon(
    [
        new Coordinate(MinLng, MinLat), new Coordinate(MaxLng, MinLat),
        new Coordinate(MaxLng, MaxLat), new Coordinate(MinLng, MaxLat),
        new Coordinate(MinLng, MinLat)
    ]);

    public static BBox Of(IEnumerable<GeoPoint> points)
    {
        double minLat = double.MaxValue, minLng = double.MaxValue, maxLat = double.MinValue, maxLng = double.MinValue;
        var any = false;
        foreach (var p in points)
        {
            any = true;
            minLat = Math.Min(minLat, p.Lat); maxLat = Math.Max(maxLat, p.Lat);
            minLng = Math.Min(minLng, p.Lng); maxLng = Math.Max(maxLng, p.Lng);
        }
        return any ? new(minLng, minLat, maxLng, maxLat) : default;
    }

    public static bool TryParse(string? s, out BBox bbox)
    {
        bbox = default;
        if (string.IsNullOrWhiteSpace(s)) return false;
        var parts = s.Split(',', StringSplitOptions.TrimEntries);
        if (parts.Length != 4) return false;
        var v = new double[4];
        for (var i = 0; i < 4; i++)
            if (!double.TryParse(parts[i], NumberStyles.Float, CultureInfo.InvariantCulture, out v[i])) return false;
        return TryCreate(v, out bbox);
    }

    public static bool TryCreate(IReadOnlyList<double>? v, out BBox bbox)
    {
        bbox = default;
        if (v is not { Count: 4 }) return false;
        if (v[0] < -180 || v[2] > 180 || v[1] < -90 || v[3] > 90) return false;
        if (v[0] > v[2] || v[1] > v[3]) return false;
        bbox = new BBox(v[0], v[1], v[2], v[3]);
        return true;
    }
}
