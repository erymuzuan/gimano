namespace Gimano.Core.Geo;

/// <summary>Standard geohash (base32 "0123456789bcdefghjkmnpqrstuvwxyz"). Precision 6 ≈ 1.2 × 0.6 km.</summary>
public static class Geohash
{
    private const string Base32 = "0123456789bcdefghjkmnpqrstuvwxyz";

    public static string Encode(double lat, double lng, int precision = 6)
    {
        double latMin = -90, latMax = 90, lngMin = -180, lngMax = 180;
        var chars = new char[precision];
        var even = true;
        for (var i = 0; i < precision; i++)
        {
            var idx = 0;
            for (var b = 0; b < 5; b++)
            {
                if (even)
                {
                    var mid = (lngMin + lngMax) / 2;
                    if (lng >= mid) { idx = idx * 2 + 1; lngMin = mid; } else { idx *= 2; lngMax = mid; }
                }
                else
                {
                    var mid = (latMin + latMax) / 2;
                    if (lat >= mid) { idx = idx * 2 + 1; latMin = mid; } else { idx *= 2; latMax = mid; }
                }
                even = !even;
            }
            chars[i] = Base32[idx];
        }
        return new string(chars);
    }

    public static bool IsValid(string? hash) => !string.IsNullOrEmpty(hash) && hash.Length <= 12 && hash.All(c => Base32.Contains(c));

    /// <summary>Cell bounds as a BBox.</summary>
    public static BBox Bounds(string hash)
    {
        double latMin = -90, latMax = 90, lngMin = -180, lngMax = 180;
        var even = true;
        foreach (var c in hash)
        {
            var idx = Base32.IndexOf(c);
            if (idx < 0) throw new ArgumentException($"Invalid geohash '{hash}'.");
            for (var b = 4; b >= 0; b--)
            {
                var bit = (idx >> b) & 1;
                if (even) { var mid = (lngMin + lngMax) / 2; if (bit == 1) lngMin = mid; else lngMax = mid; }
                else { var mid = (latMin + latMax) / 2; if (bit == 1) latMin = mid; else latMax = mid; }
                even = !even;
            }
        }
        return new BBox(lngMin, latMin, lngMax, latMax);
    }

    /// <summary>The cell and its 8 neighbours (same precision).</summary>
    public static IReadOnlyList<string> WithNeighbours(string hash)
    {
        var b = Bounds(hash);
        var dLat = b.MaxLat - b.MinLat;
        var dLng = b.MaxLng - b.MinLng;
        var cLat = (b.MinLat + b.MaxLat) / 2;
        var cLng = (b.MinLng + b.MaxLng) / 2;
        var list = new List<string>(9);
        for (var y = -1; y <= 1; y++)
            for (var x = -1; x <= 1; x++)
            {
                var lat = Math.Clamp(cLat + y * dLat, -89.999999, 89.999999);
                var lng = cLng + x * dLng;
                if (lng > 180) lng -= 360;
                if (lng < -180) lng += 360;
                var h = Encode(lat, lng, hash.Length);
                if (!list.Contains(h)) list.Add(h);
            }
        return list;
    }

    /// <summary>Union bbox of the cell and its neighbours.</summary>
    public static BBox NeighbourhoodBounds(string hash)
    {
        var cells = WithNeighbours(hash).Select(Bounds).ToList();
        return new BBox(cells.Min(c => c.MinLng), cells.Min(c => c.MinLat), cells.Max(c => c.MaxLng), cells.Max(c => c.MaxLat));
    }
}
