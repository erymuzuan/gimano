namespace Gimano.Core.Geo;

/// <summary>
/// Plus Codes (Open Location Code, Apache-2.0 spec): decode full codes ("6PH57VP3+PR") and recover short codes
/// ("7VP3+PR") against a reference point. Returns the centre of the code area.
/// </summary>
public static class OpenLocationCode
{
    private const string Alphabet = "23456789CFGHJMPQRVWX";
    private const int PairCodeLength = 10;
    private static readonly double[] PairResolutions = [20.0, 1.0, 0.05, 0.0025, 0.000125];

    /// <summary>Unpadded codes only (what people share): separator after an even position ≤ 8, ≥ 2 digits after it.</summary>
    public static bool IsValid(string code)
    {
        code = code.Trim().ToUpperInvariant();
        var sep = code.IndexOf('+');
        if (sep < 2 || sep != code.LastIndexOf('+') || sep > 8 || sep % 2 == 1) return false;
        if (code.Length - sep - 1 is 1 or > 7) return false;
        if (sep < 8 && code.Length - sep - 1 < 2) return false;
        return code.Replace("+", "").All(c => Alphabet.IndexOf(c) >= 0);
    }

    public static bool IsFull(string code) => IsValid(code) && code.Trim().IndexOf('+') == 8;
    public static bool IsShort(string code) => IsValid(code) && code.Trim().IndexOf('+') is >= 0 and < 8;

    /// <summary>Centre of a full code.</summary>
    public static (double Lat, double Lng) Decode(string code)
    {
        if (!IsFull(code)) throw new ArgumentException("Not a full Plus Code.", nameof(code));
        var digits = code.Trim().ToUpperInvariant().Replace("+", "");
        double lat = -90, lng = -180;
        var latRes = 0.0; var lngRes = 0.0;
        var pairs = Math.Min(digits.Length, PairCodeLength);
        for (var i = 0; i < pairs; i += 2)
        {
            var res = PairResolutions[i / 2];
            lat += Alphabet.IndexOf(digits[i]) * res;
            if (i + 1 < pairs) lng += Alphabet.IndexOf(digits[i + 1]) * res;
            latRes = lngRes = res;
        }
        // Grid refinement (digits beyond 10): 5 rows x 4 columns.
        for (var i = PairCodeLength; i < digits.Length; i++)
        {
            latRes /= 5; lngRes /= 4;
            var v = Alphabet.IndexOf(digits[i]);
            lat += (v / 4) * latRes;
            lng += (v % 4) * lngRes;
        }
        return (Math.Clamp(lat + latRes / 2, -90, 90), lng + lngRes / 2);
    }

    /// <summary>Recovers a short code using the nearest matching full code to the reference point.</summary>
    public static (double Lat, double Lng) RecoverNearest(string shortCode, double refLat, double refLng)
    {
        var code = shortCode.Trim().ToUpperInvariant();
        if (IsFull(code)) return Decode(code);
        if (!IsShort(code)) throw new ArgumentException("Not a Plus Code.", nameof(shortCode));
        var padding = 8 - code.IndexOf('+');
        var resolution = Math.Pow(20, 2 - padding / 2);
        var half = resolution / 2;
        var refCode = Encode(refLat, refLng);
        var full = refCode[..padding] + code;
        var (lat, lng) = Decode(full);
        if (refLat + half < lat && lat - resolution >= -90) lat -= resolution;
        else if (refLat - half > lat && lat + resolution <= 90) lat += resolution;
        if (refLng + half < lng) lng -= resolution;
        else if (refLng - half > lng) lng += resolution;
        return (lat, lng);
    }

    /// <summary>10-digit code for a point (enough for recovery).</summary>
    public static string Encode(double lat, double lng)
    {
        lat = Math.Clamp(lat, -90, 89.9999999) + 90;
        lng = ((lng + 180) % 360 + 360) % 360;
        var sb = new System.Text.StringBuilder();
        for (var i = 0; i < 5; i++)
        {
            var res = PairResolutions[i];
            var la = (int)Math.Floor(lat / res); var lo = (int)Math.Floor(lng / res);
            sb.Append(Alphabet[Math.Min(la, 19)]).Append(Alphabet[Math.Min(lo, 19)]);
            lat -= la * res; lng -= lo * res;
            if (i == 3) sb.Append('+');
        }
        return sb.ToString();
    }
}
