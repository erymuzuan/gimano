using System.Text.RegularExpressions;

namespace Gimano.Core.Search;

/// <summary>
/// Common Malaysian short names and nicknames, indexed as an extra "aliases" field so e.g. "mcd" finds McDonald's.
/// Matching is on the place name or brand (case-insensitive).
/// </summary>
public static partial class SearchAliases
{
    private static readonly (Regex Match, string Aliases)[] Rules =
    [
        (R(@"mc ?donald"), "mcd mcdonalds mekdi mcdonald"),
        (R(@"^kfc\b|kentucky"), "kfc kentucky"),
        (R(@"starbucks"), "sbux starbucks"),
        (R(@"7[- ]?eleven"), "7e seven eleven 7eleven"),
        (R(@"petronas"), "petronas"),
        (R(@"\bshell\b"), "shell"),
        (R(@"mid ?valley"), "mid valley midvalley mvm"),
        (R(@"suria klcc|kuala lumpur city cent(re|er)|petronas twin towers|menara berkembar petronas"), "klcc"),
        (R(@"kuala lumpur international airport|lapangan terbang antarabangsa kuala lumpur"), "klia"),
        (R(@"kuala lumpur sentral|stesen sentral kuala lumpur"), "kl sentral klsentral"),
        (R(@"^aeon\b"), "aeon jusco"),
        (R(@"^mydin\b"), "mydin"),
        (R(@"lotus'?s|tesco"), "lotus lotuss tesco"),
        (R(@"^99 speedmart"), "99 speedmart speedmart"),
        (R(@"^family ?mart"), "familymart family mart"),
        (R(@"hospital kuala lumpur"), "hkl"),
        (R(@"universiti malaya"), "um"),
        (R(@"universiti kebangsaan malaysia"), "ukm"),
        (R(@"universiti putra malaysia"), "upm"),
        (R(@"universiti teknologi mara"), "uitm"),
    ];

    private static Regex R(string pattern) => new(pattern, RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    /// <summary>OSM name variants indexed for areas ("Wilayah Persekutuan Putrajaya", "KL", "Kuala Lumpur" for name:en …).</summary>
    public static readonly string[] AreaNameTags = ["official_name", "alt_name", "short_name", "old_name", "name:en", "name:ms"];

    public static string? For(string name, string? brand, IReadOnlyDictionary<string, string>? areaTags = null)
    {
        var text = brand is null ? name : name + " " + brand;
        var hits = Rules.Where(r => r.Match.IsMatch(text)).Select(r => r.Aliases).ToList();
        if (areaTags is not null)
            hits.AddRange(AreaNameTags.Select(t => areaTags.GetValueOrDefault(t)).OfType<string>()
                .SelectMany(v => v.Split(';', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
                .Where(v => !string.Equals(v, name, StringComparison.OrdinalIgnoreCase)));
        hits = hits.Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        return hits.Count == 0 ? null : string.Join(' ', hits);
    }
}
