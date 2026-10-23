using System.Text.Json.Nodes;
using Gimano.Core.Geo;

namespace Gimano.Core.Issues;

/// <summary>Valhalla exclusions for confirmed road issues (v1: closed / no road), and matching issues to routes.</summary>
public static class IssueGeometry
{
    /// <summary>Half-length of the blocked stretch along the road (m) and half-width across it (m).</summary>
    public const double HalfLengthM = 25;
    public const double HalfWidthM = 5;
    /// <summary>Valhalla's default max_exclude_polygons_length is 10 km of total perimeter; each box is ≈ 120 m.</summary>
    public const int MaxPolygons = 60;
    public const double AvoidedWithinM = 1_500;
    public const double OnRouteWithinM = 25;

    /// <summary>
    /// A small box across the middle of the segment (never its ends, so crossing roads at the junctions stay usable):
    /// ≤ 50 m along the road, 10 m wide. Returns a closed ring of [lng, lat] pairs.
    /// </summary>
    public static IReadOnlyList<(double Lng, double Lat)> ExclusionRing(IReadOnlyList<GeoPoint> line)
    {
        if (line.Count == 0) return [];
        var length = GeoMath.LengthM(line);
        var mid = PointAt(line, length / 2);
        var half = Math.Min(HalfLengthM, Math.Max(2, length * 0.2));
        var a = PointAt(line, Math.Max(0, length / 2 - half));
        var b = PointAt(line, Math.Min(length, length / 2 + half));
        if (GeoMath.HaversineM(a, b) < 1) { a = Offset(mid, 0, -half); b = Offset(mid, 0, half); }
        var bearing = GeoMath.Bearing(a, b);
        var left = (bearing + 270) % 360;
        var right = (bearing + 90) % 360;
        var ring = new[]
        {
            Offset(a, left, HalfWidthM), Offset(b, left, HalfWidthM), Offset(b, right, HalfWidthM), Offset(a, right, HalfWidthM),
        };
        return ring.Append(ring[0]).Select(p => (Math.Round(p.Lng, 7), Math.Round(p.Lat, 7))).ToList();
    }

    public static JsonArray ToValhalla(IEnumerable<IReadOnlyList<(double Lng, double Lat)>> rings) =>
        new(rings.Select(r => (JsonNode)new JsonArray(r.Select(p => (JsonNode)new JsonArray(p.Lng, p.Lat)).ToArray())).ToArray());

    /// <summary>The point <paramref name="m"/> metres along the polyline.</summary>
    public static GeoPoint PointAt(IReadOnlyList<GeoPoint> line, double m)
    {
        if (line.Count == 1 || m <= 0) return line[0];
        double along = 0;
        for (var i = 1; i < line.Count; i++)
        {
            var seg = GeoMath.HaversineM(line[i - 1], line[i]);
            if (along + seg >= m && seg > 0)
            {
                var t = (m - along) / seg;
                return new GeoPoint(line[i - 1].Lat + t * (line[i].Lat - line[i - 1].Lat), line[i - 1].Lng + t * (line[i].Lng - line[i - 1].Lng));
            }
            along += seg;
        }
        return line[^1];
    }

    /// <summary>Moves a point by <paramref name="meters"/> along a bearing (local flat approximation, fine for metres).</summary>
    public static GeoPoint Offset(GeoPoint p, double bearingDeg, double meters)
    {
        const double mPerDeg = Math.PI / 180 * GeoMath.EarthRadiusM;
        var rad = GeoMath.ToRad(bearingDeg);
        var dLat = meters * Math.Cos(rad) / mPerDeg;
        var dLng = meters * Math.Sin(rad) / (mPerDeg * Math.Cos(GeoMath.ToRad(p.Lat)));
        return new GeoPoint(p.Lat + dLat, p.Lng + dLng);
    }

    /// <summary>Distance from the route and how far along it (m), or null if farther than <paramref name="withinM"/>.</summary>
    public static (double DistanceM, double AlongM)? Near(IReadOnlyList<GeoPoint> route, GeoPoint p, double withinM)
    {
        var proj = GeoMath.ProjectOnLine(route, p);
        return proj.DistanceM <= withinM ? (proj.DistanceM, proj.AlongM) : null;
    }
}
