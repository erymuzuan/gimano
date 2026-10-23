using NetTopologySuite;
using NetTopologySuite.Geometries;

namespace Gimano.Core.Geo;

public static class GeoMath
{
    public const double EarthRadiusM = 6_371_008.8;

    public static readonly GeometryFactory Factory =
        NtsGeometryServices.Instance.CreateGeometryFactory(srid: 4326);

    public static double HaversineM(GeoPoint a, GeoPoint b) => HaversineM(a.Lat, a.Lng, b.Lat, b.Lng);

    public static double HaversineM(double lat1, double lng1, double lat2, double lng2)
    {
        var dLat = ToRad(lat2 - lat1);
        var dLng = ToRad(lng2 - lng1);
        var h = Math.Sin(dLat / 2) * Math.Sin(dLat / 2) +
                Math.Cos(ToRad(lat1)) * Math.Cos(ToRad(lat2)) * Math.Sin(dLng / 2) * Math.Sin(dLng / 2);
        return 2 * EarthRadiusM * Math.Asin(Math.Min(1, Math.Sqrt(h)));
    }

    /// <summary>Initial bearing from a to b in degrees [0, 360).</summary>
    public static double Bearing(GeoPoint a, GeoPoint b)
    {
        var φ1 = ToRad(a.Lat); var φ2 = ToRad(b.Lat);
        var Δλ = ToRad(b.Lng - a.Lng);
        var y = Math.Sin(Δλ) * Math.Cos(φ2);
        var x = Math.Cos(φ1) * Math.Sin(φ2) - Math.Sin(φ1) * Math.Cos(φ2) * Math.Cos(Δλ);
        return (ToDeg(Math.Atan2(y, x)) + 360) % 360;
    }

    /// <summary>Smallest absolute difference between two headings, in [0, 180].</summary>
    public static double HeadingDelta(double h1, double h2)
    {
        var d = Math.Abs(((h1 - h2) % 360 + 360) % 360);
        return d > 180 ? 360 - d : d;
    }

    public static double LengthM(IReadOnlyList<GeoPoint> line)
    {
        double sum = 0;
        for (var i = 1; i < line.Count; i++) sum += HaversineM(line[i - 1], line[i]);
        return sum;
    }

    /// <summary>
    /// Projects <paramref name="p"/> onto the polyline. Returns the closest distance (m),
    /// the index of the segment start vertex and the distance along the line (m) to the projection.
    /// Uses a local equirectangular projection — accurate to well under 1% at city scale.
    /// </summary>
    public static LineProjection ProjectOnLine(IReadOnlyList<GeoPoint> line, GeoPoint p)
    {
        if (line.Count == 0) return new(double.MaxValue, -1, 0, 0);
        if (line.Count == 1) return new(HaversineM(line[0], p), 0, 0, 0);

        var cosLat = Math.Cos(ToRad(p.Lat));
        const double mPerDeg = Math.PI / 180 * EarthRadiusM;
        var best = new LineProjection(double.MaxValue, -1, 0, 0);
        double along = 0;
        for (var i = 1; i < line.Count; i++)
        {
            var a = line[i - 1]; var b = line[i];
            var ax = (a.Lng - p.Lng) * cosLat * mPerDeg; var ay = (a.Lat - p.Lat) * mPerDeg;
            var bx = (b.Lng - p.Lng) * cosLat * mPerDeg; var by = (b.Lat - p.Lat) * mPerDeg;
            var dx = bx - ax; var dy = by - ay;
            var segLen2 = dx * dx + dy * dy;
            var t = segLen2 == 0 ? 0 : Math.Clamp(-(ax * dx + ay * dy) / segLen2, 0, 1);
            var cx = ax + t * dx; var cy = ay + t * dy;
            var dist = Math.Sqrt(cx * cx + cy * cy);
            var segLen = Math.Sqrt(segLen2);
            if (dist < best.DistanceM)
                best = new LineProjection(dist, i - 1, along + t * segLen, Bearing(a, b));
            along += segLen;
        }
        return best;
    }

    public static double ToRad(double deg) => deg * Math.PI / 180;
    public static double ToDeg(double rad) => rad * 180 / Math.PI;

    public static LineString ToLineString(IReadOnlyList<GeoPoint> pts) =>
        Factory.CreateLineString(pts.Select(p => new Coordinate(p.Lng, p.Lat)).ToArray());

    public static IReadOnlyList<GeoPoint> FromLineString(LineString ls) =>
        ls.Coordinates.Select(c => new GeoPoint(c.Y, c.X)).ToList();
}

public readonly record struct LineProjection(double DistanceM, int SegmentIndex, double AlongM, double SegmentBearing);
