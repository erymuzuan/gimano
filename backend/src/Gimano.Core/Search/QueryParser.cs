using System.Globalization;
using System.Text.RegularExpressions;
using Gimano.Core.Geo;

namespace Gimano.Core.Search;

/// <summary>What a query looks like before any index lookup (coordinates, Plus Code, postcode, street address).</summary>
public abstract record ParsedQuery;
public sealed record CoordinateQuery(double Lat, double Lng) : ParsedQuery;
public sealed record PlusCodeQuery(string Code, double Lat, double Lng) : ParsedQuery;
public sealed record PostcodeQuery(string Postcode) : ParsedQuery;
/// <summary>"12 Jalan SS2/24", "No. 12, Jalan SS 2/24", "Jalan SS2/24".</summary>
public sealed record StreetQuery(string? HouseNumber, string Street) : ParsedQuery;

public static partial class QueryParser
{
    /// <summary>Street-type words that start a Malaysian street name (after alias expansion of jln/lrg/psrn…).</summary>
    public static readonly string[] StreetWords =
        ["jalan", "jln", "lorong", "lrg", "persiaran", "psrn", "lebuh", "lebuhraya", "lingkaran", "lintang", "lengkok", "medan",
         "pesiaran", "changkat", "tingkat", "solok", "road", "street", "lane", "avenue", "drive"];

    [GeneratedRegex(@"^\s*(-?\d{1,2}(?:\.\d+)?)\s*[,;\s]\s*(-?\d{1,3}(?:\.\d+)?)\s*$")]
    private static partial Regex Coordinates();

    [GeneratedRegex(@"^\s*([23456789CFGHJMPQRVWX]{2,8}\+[23456789CFGHJMPQRVWX]{0,7})\s*(?:,?\s*(.*))?$", RegexOptions.IgnoreCase)]
    private static partial Regex PlusCode();

    [GeneratedRegex(@"^\s*(\d{5})\s*$")]
    private static partial Regex Postcode();

    [GeneratedRegex(@"^\s*(?:(?:no\.?|lot|nombor)\s*)?(\d{1,5}[a-z]?(?:-\d{1,4}[a-z]?)?)\s*,?\s+(.+)$", RegexOptions.IgnoreCase)]
    private static partial Regex HouseThenStreet();

    /// <param name="refLat">Reference for short Plus Codes (user or map centre).</param>
    /// <param name="locality">Resolves a place name after a short Plus Code ("7VP3+PR Petaling Jaya") to a reference point.</param>
    public static ParsedQuery? Parse(string raw, double? refLat = null, double? refLng = null, Func<string, (double Lat, double Lng)?>? locality = null)
    {
        if (Coordinates().Match(raw) is { Success: true } c
            && double.TryParse(c.Groups[1].Value, NumberStyles.Float, CultureInfo.InvariantCulture, out var la)
            && double.TryParse(c.Groups[2].Value, NumberStyles.Float, CultureInfo.InvariantCulture, out var lo)
            && la is >= -90 and <= 90 && lo is >= -180 and <= 180 && (c.Groups[1].Value.Contains('.') || c.Groups[2].Value.Contains('.')))
            return new CoordinateQuery(la, lo);

        if (PlusCode().Match(raw) is { Success: true } p && OpenLocationCode.IsValid(p.Groups[1].Value))
        {
            var code = p.Groups[1].Value.ToUpperInvariant();
            if (OpenLocationCode.IsFull(code)) { var (a, b) = OpenLocationCode.Decode(code); return new PlusCodeQuery(code, a, b); }
            // Short code: needs a reference (the user, the map centre; a trailing locality is resolved by the caller).
            var (rl, rg) = (refLat ?? 3.139, refLng ?? 101.6869);
            if (p.Groups[2].Value.Trim() is { Length: > 0 } place && locality?.Invoke(place) is { } at) (rl, rg) = at;
            var (sa, sb) = OpenLocationCode.RecoverNearest(code, rl, rg);
            return new PlusCodeQuery(code, sa, sb);
        }

        if (Postcode().Match(raw) is { Success: true } pc) return new PostcodeQuery(pc.Groups[1].Value);

        var norm = TextNormalizer.Normalize(raw);
        var m = HouseThenStreet().Match(raw);
        if (m.Success && StartsWithStreetWord(TextNormalizer.Normalize(m.Groups[2].Value)))
            return new StreetQuery(m.Groups[1].Value.ToUpperInvariant(), CleanStreet(m.Groups[2].Value));
        if (StartsWithStreetWord(norm) && norm.Split(' ').Length >= 2) return new StreetQuery(null, CleanStreet(raw));
        return null;
    }

    public static bool StartsWithStreetWord(string normalized) =>
        StreetWords.Any(w => normalized == w || normalized.StartsWith(w + " ", StringComparison.Ordinal));

    /// <summary>Drops a trailing ", postcode city" part: "Jalan SS2/24, 47300 Petaling Jaya" → "Jalan SS2/24".</summary>
    private static string CleanStreet(string s) => s.Split(',')[0].Trim();

    /// <summary>Street key used both for indexing and querying: normalised, "jln" → "jalan", digits glued to letters split ("ss2" = "ss 2").</summary>
    public static string StreetKey(string street)
    {
        var t = TextNormalizer.Tokens(street).Select(w => w switch
        {
            "jln" or "jl" => "jalan", "lrg" => "lorong", "psrn" => "persiaran", "pesiaran" => "persiaran", _ => w,
        });
        var s = string.Join(' ', t);
        s = SplitAlnum().Replace(s, "$1 $2");
        return string.Join(' ', s.Split(' ', StringSplitOptions.RemoveEmptyEntries));
    }

    [GeneratedRegex(@"([a-z])(\d)")]
    private static partial Regex SplitAlnum();
}

/// <summary>An OSM house address (addr:housenumber + addr:street).</summary>
public sealed record AddressPoint(string Id, string HouseNumber, string Street, string? Postcode, string? City, double Lat, double Lng, string Country);
