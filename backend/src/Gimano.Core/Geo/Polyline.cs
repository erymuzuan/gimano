using System.Text;

namespace Gimano.Core.Geo;

/// <summary>
/// Google encoded polyline algorithm. Gimano (and Valhalla) use precision 6.
/// </summary>
public static class Polyline
{
    public const int DefaultPrecision = 6;

    public static string Encode(IEnumerable<GeoPoint> points, int precision = DefaultPrecision)
    {
        var factor = Math.Pow(10, precision);
        var sb = new StringBuilder();
        long prevLat = 0, prevLng = 0;
        foreach (var p in points)
        {
            var lat = (long)Math.Round(p.Lat * factor, MidpointRounding.AwayFromZero);
            var lng = (long)Math.Round(p.Lng * factor, MidpointRounding.AwayFromZero);
            EncodeValue(sb, lat - prevLat);
            EncodeValue(sb, lng - prevLng);
            prevLat = lat;
            prevLng = lng;
        }
        return sb.ToString();
    }

    public static IReadOnlyList<GeoPoint> Decode(string? encoded, int precision = DefaultPrecision)
    {
        var result = new List<GeoPoint>();
        if (string.IsNullOrEmpty(encoded)) return result;

        var factor = Math.Pow(10, precision);
        int index = 0;
        long lat = 0, lng = 0;
        while (index < encoded.Length)
        {
            lat += DecodeValue(encoded, ref index);
            if (index >= encoded.Length) throw new FormatException("Truncated polyline (missing longitude).");
            lng += DecodeValue(encoded, ref index);
            result.Add(new GeoPoint(lat / factor, lng / factor));
        }
        return result;
    }

    private static void EncodeValue(StringBuilder sb, long value)
    {
        var v = value < 0 ? ~(value << 1) : value << 1;
        while (v >= 0x20)
        {
            sb.Append((char)((0x20 | (int)(v & 0x1f)) + 63));
            v >>= 5;
        }
        sb.Append((char)(v + 63));
    }

    private static long DecodeValue(string encoded, ref int index)
    {
        long result = 0;
        int shift = 0;
        int b;
        do
        {
            if (index >= encoded.Length) throw new FormatException("Truncated polyline.");
            b = encoded[index++] - 63;
            if (b < 0 || b > 63) throw new FormatException($"Invalid polyline character at {index - 1}.");
            result |= (long)(b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20);
        return (result & 1) != 0 ? ~(result >> 1) : result >> 1;
    }
}
