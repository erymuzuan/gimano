using System.Globalization;

namespace Gimano.Core.Community;

/// <summary>
/// Server-originated text follows Accept-Language: English by default, Malay when the header prefers ms (ms, ms-MY, zsm, may).
/// Templates for both languages live next to the text (InboxRules, ProblemTitles, AreaKinds). Tier names stay Malay.
/// </summary>
public static class L10n
{
    public const string En = "en";
    public const string Ms = "ms";

    /// <summary>"ms" when Malay has the highest q among the languages we have; otherwise "en".</summary>
    public static string FromAcceptLanguage(string? header)
    {
        if (string.IsNullOrWhiteSpace(header)) return En;
        var best = (Lang: En, Q: -1.0, Order: int.MaxValue);
        var order = 0;
        foreach (var part in header.Split(','))
        {
            var bits = part.Split(';', StringSplitOptions.TrimEntries);
            var tag = bits[0].ToLowerInvariant();
            var q = 1.0;
            foreach (var b in bits.Skip(1))
                if (b.StartsWith("q=", StringComparison.Ordinal) && double.TryParse(b[2..], NumberStyles.Float, CultureInfo.InvariantCulture, out var v)) q = v;
            string? lang = tag.StartsWith("ms", StringComparison.Ordinal) || tag.StartsWith("zsm", StringComparison.Ordinal) || tag.StartsWith("may", StringComparison.Ordinal)
                ? Ms : tag.StartsWith("en", StringComparison.Ordinal) ? En : null;
            if (lang is not null && q > 0 && (q > best.Q || (q == best.Q && order < best.Order))) best = (lang, q, order);
            order++;
        }
        return best.Lang;
    }

    public static bool IsMs(string lang) => lang == Ms;

    private static readonly Dictionary<string, string> ProblemTitlesMs = new(StringComparer.Ordinal)
    {
        ["One or more validation errors occurred."] = "Terdapat ralat pada permintaan.",
        ["Similar place nearby"] = "Tempat serupa berdekatan",
        ["Too many requests"] = "Terlalu banyak permintaan",
        ["Forbidden"] = "Tidak dibenarkan",
        ["Unsupported image format"] = "Format gambar tidak disokong",
        ["Too large"] = "Terlalu besar",
        ["Routing unavailable"] = "Laluan tidak tersedia buat masa ini",
        ["No route found"] = "Tiada laluan ditemui",
        ["Not found"] = "Tidak ditemui",
        ["Conflict"] = "Konflik",
        ["Unauthorized"] = "Tidak sah",
        ["Bad request"] = "Permintaan tidak sah",
        ["Unexpected error"] = "Ralat tidak dijangka",
        ["Request signature required"] = "Tandatangan permintaan diperlukan",
        ["Too many crash reports"] = "Terlalu banyak laporan ranap",
        ["Signature login failed"] = "Log masuk tandatangan gagal",
        ["Too many sign-in attempts"] = "Terlalu banyak cubaan log masuk",
        ["Key rotation needs sigByOldKey"] = "Penukaran kunci memerlukan sigByOldKey",
        ["Attestation rejected"] = "Pengesahan peranti ditolak",
        ["Screenshot too large"] = "Tangkapan skrin terlalu besar",
        ["Too much feedback"] = "Terlalu banyak maklum balas",
        ["Moved to your device"] = "Dipindahkan ke peranti anda",
        ["Too many active shares"] = "Terlalu banyak perkongsian aktif",
        ["Unknown share"] = "Perkongsian tidak diketahui",
        ["This share has ended"] = "Perkongsian ini telah tamat",
        ["Invalid share token"] = "Token perkongsian tidak sah",
        ["Blob too large"] = "Data terlalu besar",
        ["Invalid blob"] = "Data tidak sah",
        ["Bad Request"] = "Permintaan tidak sah",
        ["Not Found"] = "Tidak ditemui",
        ["Internal Server Error"] = "Ralat pelayan",
        ["Service Unavailable"] = "Perkhidmatan tidak tersedia",
    };

    /// <summary>problem+json titles are written in English and translated here for ms.</summary>
    public static string? ProblemTitle(string? title, string lang) =>
        title is not null && IsMs(lang) && ProblemTitlesMs.TryGetValue(title, out var ms) ? ms : title;

    public static IReadOnlyCollection<string> KnownProblemTitles => ProblemTitlesMs.Keys;

    /// <summary>Area kind labels (stored in Malay at import) → the reader's language: "Bandar raya, Selangor" → "City, Selangor".</summary>
    private static readonly (string Ms, string En)[] AreaKinds =
    [
        ("Bandar raya", "City"), ("Bandar", "Town"), ("Kawasan", "Area"), ("Kampung", "Village"), ("Kejiranan", "Neighbourhood"),
        ("Negeri / Wilayah", "State / Territory"), ("Daerah", "District"),
    ];

    public static string AreaAddress(string address, string lang)
    {
        if (IsMs(lang) || string.IsNullOrEmpty(address)) return address;
        var head = address.Split(',')[0].Trim();
        foreach (var (ms, en) in AreaKinds)
            if (head == ms) return en + address[head.Length..];
        return address;
    }

    public static string AreaKindMs(string? place, string? adminLevel) => place switch
    {
        "city" => "Bandar raya", "town" => "Bandar", "suburb" => "Kawasan", "village" => "Kampung",
        "neighbourhood" or "quarter" => "Kejiranan",
        "admin" => adminLevel == "4" ? "Negeri / Wilayah" : "Daerah",
        _ => "Kawasan",
    };

    public static string Near(string lang) => IsMs(lang) ? "Dekat" : "Near";
    public static string DroppedPin(string lang) => IsMs(lang) ? "Pin di peta" : "Dropped pin";
    public static string Postcode(string lang) => IsMs(lang) ? "Poskod" : "Postcode";
    public static string Coordinates(string lang) => IsMs(lang) ? "Koordinat" : "Coordinates";

    /// <summary>Display label of a category for search suggestions.</summary>
    public static string Category(Contracts.PlaceCategory c, string lang) => (c, IsMs(lang)) switch
    {
        (Contracts.PlaceCategory.Fuel, true) => "Stesen minyak", (Contracts.PlaceCategory.Fuel, false) => "Petrol stations",
        (Contracts.PlaceCategory.Toilets, true) => "Tandas", (Contracts.PlaceCategory.Toilets, false) => "Toilets",
        (Contracts.PlaceCategory.Surau, true) => "Surau", (Contracts.PlaceCategory.Surau, false) => "Surau (prayer rooms)",
        (Contracts.PlaceCategory.Mosque, true) => "Masjid", (Contracts.PlaceCategory.Mosque, false) => "Mosques",
        (Contracts.PlaceCategory.Atm, _) => "ATM",
        (Contracts.PlaceCategory.Parking, true) => "Tempat letak kereta", (Contracts.PlaceCategory.Parking, false) => "Parking",
        (Contracts.PlaceCategory.Clinic, true) => "Klinik", (Contracts.PlaceCategory.Clinic, false) => "Clinics",
        (Contracts.PlaceCategory.Pharmacy, true) => "Farmasi", (Contracts.PlaceCategory.Pharmacy, false) => "Pharmacies",
        (Contracts.PlaceCategory.Convenience, true) => "Kedai runcit", (Contracts.PlaceCategory.Convenience, false) => "Convenience stores",
        (Contracts.PlaceCategory.Restaurant, true) => "Kedai makan", (Contracts.PlaceCategory.Restaurant, false) => "Restaurants",
        (Contracts.PlaceCategory.Hospital, _) => "Hospital",
        _ => Contracts.GimanoJson.ToWire(c).Replace('_', ' '),
    };
}
