using System.Globalization;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;

namespace Gimano.Core.Community;

public enum TextVerdict { Ok, Profanity, Link }

/// <summary>
/// Profanity/spam filter for community text (EN + MS word lists in data/moderation/, embedded at build time).
/// The normaliser is documented in data/moderation/README.md.
/// </summary>
public sealed partial class TextFilter
{
    private readonly HashSet<string> _single = new(StringComparer.Ordinal);
    private readonly List<string[]> _multi = [];

    public TextFilter(IEnumerable<string> entries)
    {
        foreach (var raw in entries)
        {
            var line = raw.Split('#', 2)[0].Trim();
            if (line.Length == 0) continue;
            var tokens = Tokens(line);
            if (tokens.Length == 1) _single.Add(tokens[0]);
            else if (tokens.Length > 1) _multi.Add(tokens);
        }
    }

    public int Count => _single.Count + _multi.Count;

    /// <summary>The built-in EN + MS lists (embedded resources from data/moderation/*.txt).</summary>
    public static TextFilter Default { get; } = new(LoadEmbedded());

    private static IEnumerable<string> LoadEmbedded()
    {
        var asm = typeof(TextFilter).Assembly;
        foreach (var name in asm.GetManifestResourceNames().Where(n => n.Contains("moderation", StringComparison.OrdinalIgnoreCase) && n.EndsWith(".txt")))
        {
            using var s = asm.GetManifestResourceStream(name)!;
            using var r = new StreamReader(s);
            while (r.ReadLine() is { } line) yield return line;
        }
    }

    public TextVerdict Check(string? text, bool rejectLinks = true)
    {
        if (string.IsNullOrWhiteSpace(text)) return TextVerdict.Ok;
        if (rejectLinks && HasLink(text)) return TextVerdict.Link;
        var tokens = Tokens(text);
        if (tokens.Any(_single.Contains)) return TextVerdict.Profanity;

        // Spaced-out spelling: join runs of single letters ("f u c k").
        var run = new StringBuilder();
        foreach (var t in tokens.Append(""))
        {
            if (t.Length == 1) { run.Append(t); continue; }
            if (run.Length > 1 && _single.Contains(Collapse(run.ToString()))) return TextVerdict.Profanity;
            run.Clear();
        }

        foreach (var m in _multi)
            for (var i = 0; i + m.Length <= tokens.Length; i++)
                if (m.AsSpan().SequenceEqual(tokens.AsSpan(i, m.Length))) return TextVerdict.Profanity;
        return TextVerdict.Ok;
    }

    public static bool HasLink(string text) => LinkRegex().IsMatch(text);

    /// <summary>Removes URLs from free text (reviews keep their words, lose their links).</summary>
    public static string StripLinks(string text) => MultiSpace().Replace(LinkRegex().Replace(text, " "), " ").Trim();

    /// <summary>Normalised tokens: NFKD + no diacritics, lower-case, leetspeak folded, non-letters → space, runs collapsed.</summary>
    public static string[] Tokens(string text)
    {
        var sb = new StringBuilder(text.Length);
        foreach (var ch in text.Normalize(NormalizationForm.FormKD))
        {
            if (CharUnicodeInfo.GetUnicodeCategory(ch) == UnicodeCategory.NonSpacingMark) continue;
            var c = FoldLatin(char.ToLowerInvariant(ch)) switch
            {
                '0' => 'o', '1' => 'i', '3' => 'e', '4' => 'a', '5' => 's', '7' => 't', '8' => 'b',
                '@' => 'a', '$' => 's', '!' => 'i', var x => x,
            };
            sb.Append(c is >= 'a' and <= 'z' ? c : ' ');
        }
        return sb.ToString().Split(' ', StringSplitOptions.RemoveEmptyEntries).Select(Collapse).ToArray();
    }

    /// <summary>Diacritic folding that also works with InvariantGlobalization (where Normalize may be a no-op).</summary>
    internal static char FoldLatin(char c) => c switch
    {
        'à' or 'á' or 'â' or 'ã' or 'ä' or 'å' or 'ā' => 'a',
        'ç' or 'ć' or 'č' => 'c',
        'è' or 'é' or 'ê' or 'ë' or 'ē' => 'e',
        'ì' or 'í' or 'î' or 'ï' or 'ī' => 'i',
        'ñ' or 'ń' => 'n',
        'ò' or 'ó' or 'ô' or 'õ' or 'ö' or 'ō' => 'o',
        'ù' or 'ú' or 'û' or 'ü' or 'ū' => 'u',
        'ý' or 'ÿ' => 'y',
        _ => c,
    };

    private static string Collapse(string w)
    {
        var sb = new StringBuilder(w.Length);
        foreach (var c in w) if (sb.Length == 0 || sb[^1] != c) sb.Append(c);
        return sb.ToString();
    }

    [GeneratedRegex(@"(https?://|www\.)\S+|\b[\w-]+\.(com|net|org|my|sg|bn|io|co|info|biz|xyz|link|ly)(/\S*)?\b", RegexOptions.IgnoreCase)]
    private static partial Regex LinkRegex();

    [GeneratedRegex(@"\s{2,}")]
    private static partial Regex MultiSpace();
}
