using System.Reflection;
using Gimano.Core.Contracts;

namespace Gimano.Core.Search;

public enum AliasKind { Brand, Rename, Dialect, Category }

public sealed record AliasEntry(string Alias, string Canonical, AliasKind Kind, string Locale, string Note);

/// <summary>
/// Step 2 of search: aliases, brand renames, dialect words and category words from <c>data/search/aliases.csv</c>
/// (format: data/search/README.md). Brand/rename/dialect rows rewrite words in the query (tesco → Lotus's, mcd → McDonald's);
/// category rows turn the whole query into a category search (minyak → fuel). Immutable; swap the instance to reload.
/// </summary>
public sealed class AliasTable
{
    public IReadOnlyList<AliasEntry> Entries { get; }
    private readonly Dictionary<string, List<AliasEntry>> _rewrites;   // normalised alias phrase → canonical rows
    private readonly Dictionary<string, PlaceCategory[]> _categories;  // normalised phrase → categories
    private readonly int _maxWords;

    public AliasTable(IEnumerable<AliasEntry> entries)
    {
        Entries = entries.ToList();
        _rewrites = Entries.Where(e => e.Kind != AliasKind.Category)
            .GroupBy(e => TextNormalizer.Normalize(e.Alias)).Where(g => g.Key.Length > 0)
            .ToDictionary(g => g.Key, g => g.ToList());
        _categories = Entries.Where(e => e.Kind == AliasKind.Category)
            .GroupBy(e => TextNormalizer.Normalize(e.Alias)).Where(g => g.Key.Length > 0)
            .ToDictionary(g => g.Key, g => g.SelectMany(e => ParseCategories(e.Canonical)).Distinct().ToArray());
        _maxWords = Entries.Select(e => TextNormalizer.Tokens(e.Alias).Length).DefaultIfEmpty(1).Max();
    }

    public static PlaceCategory[] ParseCategories(string canonical) =>
        canonical.Split('|', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
            .Select(c => GimanoJson.TryParseWire<PlaceCategory>(c, out var pc) ? pc : (PlaceCategory?)null)
            .OfType<PlaceCategory>().ToArray();

    /// <summary>Categories when the whole (normalised) phrase is a category word, e.g. "stesen minyak" → [fuel].</summary>
    public PlaceCategory[]? Category(string normalized) => _categories.GetValueOrDefault(normalized);

    public IEnumerable<string> CategoryWords => _categories.Keys;

    /// <summary>
    /// Query variants with alias phrases replaced by their canonical names (longest phrase first, whole words only).
    /// The original is not included. "tesco ss2" → ["lotuss ss2"].
    /// </summary>
    public IReadOnlyList<string> Rewrites(string normalized)
    {
        var tokens = normalized.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        var variants = new List<string>();
        for (var start = 0; start < tokens.Length; start++)
            for (var len = Math.Min(_maxWords, tokens.Length - start); len >= 1; len--)
            {
                var phrase = string.Join(' ', tokens.Skip(start).Take(len));
                if (!_rewrites.TryGetValue(phrase, out var rows)) continue;
                foreach (var r in rows)
                {
                    var replaced = tokens.Take(start).Append(TextNormalizer.Normalize(r.Canonical)).Concat(tokens.Skip(start + len));
                    variants.Add(string.Join(' ', replaced));
                }
                break;
            }
        return variants.Distinct().Where(v => v != normalized).Take(4).ToList();
    }

    public static AliasTable Parse(TextReader reader)
    {
        var rows = new List<AliasEntry>();
        string? line;
        var first = true;
        while ((line = reader.ReadLine()) is not null)
        {
            if (string.IsNullOrWhiteSpace(line) || line.TrimStart().StartsWith('#')) continue;
            var f = SplitCsv(line);
            if (first) { first = false; if (f[0].Trim().Equals("alias", StringComparison.OrdinalIgnoreCase)) continue; }
            if (f.Count < 3 || f[0].Trim().Length == 0 || f[1].Trim().Length == 0) continue;
            if (!Enum.TryParse<AliasKind>(f[2].Trim(), ignoreCase: true, out var kind)) continue;
            rows.Add(new AliasEntry(f[0].Trim(), f[1].Trim(), kind, f.ElementAtOrDefault(3)?.Trim() ?? "", f.ElementAtOrDefault(4)?.Trim() ?? ""));
        }
        return new AliasTable(rows);
    }

    public static AliasTable Load(string path)
    {
        using var r = File.OpenText(path);
        return Parse(r);
    }

    /// <summary>The copy of data/search/aliases.csv embedded at build time (used when no file is configured).</summary>
    public static AliasTable Embedded()
    {
        using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("search.aliases.csv")!;
        using var r = new StreamReader(s);
        return Parse(r);
    }

    private static List<string> SplitCsv(string line)
    {
        var fields = new List<string>();
        var sb = new System.Text.StringBuilder();
        var quoted = false;
        for (var i = 0; i < line.Length; i++)
        {
            var c = line[i];
            if (quoted)
            {
                if (c == '"' && i + 1 < line.Length && line[i + 1] == '"') { sb.Append('"'); i++; }
                else if (c == '"') quoted = false;
                else sb.Append(c);
            }
            else if (c == '"') quoted = true;
            else if (c == ',') { fields.Add(sb.ToString()); sb.Clear(); }
            else sb.Append(c);
        }
        fields.Add(sb.ToString());
        return fields;
    }
}
