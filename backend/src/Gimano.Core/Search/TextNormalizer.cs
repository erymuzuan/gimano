using System.Globalization;
using System.Text;

namespace Gimano.Core.Search;

/// <summary>
/// Step 1 of search (docs/api.md "Finding places"): one normal form for queries and indexed names.
/// Lower case, accents folded, apostrophes dropped ("McDonald's" → "mcdonalds"), "&amp;" → "dan", every other punctuation
/// mark (including hyphens and slashes) becomes a space, runs of spaces collapse. <see cref="Compact"/> also removes spaces,
/// so "Family Mart" = "FamilyMart" and "7 eleven" = "7-Eleven" = "7eleven".
/// </summary>
public static class TextNormalizer
{
    public static string Normalize(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return "";
        // InvariantGlobalization is on (containers), so String.Normalize can't decompose: fold Latin accents by table.
        var sb = new StringBuilder(text.Length);
        foreach (var raw in text)
        {
            if (CharUnicodeInfo.GetUnicodeCategory(raw) == UnicodeCategory.NonSpacingMark) continue;
            var ch = Fold(raw);
            if (ch is '\'' or '’' or '‘' or '`' or '´') continue;
            if (ch == '&') { sb.Append(" dan "); continue; }
            if (char.IsLetterOrDigit(ch)) sb.Append(char.ToLowerInvariant(ch));
            else sb.Append(' ');
        }
        return string.Join(' ', sb.ToString().Split(' ', StringSplitOptions.RemoveEmptyEntries));
    }

    private const string Accented = "àáâãäåāăąçćčďèéêëēĕėęěìíîïīĭįıñńňòóôõöøōŏőùúûüūŭůűųýÿźżžÀÁÂÃÄÅĀĂĄÇĆČĎÈÉÊËĒĔĖĘĚÌÍÎÏĪĬĮİÑŃŇÒÓÔÕÖØŌŎŐÙÚÛÜŪŬŮŰŲÝŸŹŻŽ";
    private const string Plain    = "aaaaaaaaaccc" + "deeeeeeeee" + "iiiiiiii" + "nnn" + "ooooooooo" + "uuuuuuuuu" + "yyzzz" + "AAAAAAAAACCCD" + "EEEEEEEEE" + "IIIIIIII" + "NNN" + "OOOOOOOOO" + "UUUUUUUUU" + "YYZZZ";

    private static char Fold(char c)
    {
        if (c < 128) return c;
        var i = Accented.IndexOf(c);
        return i >= 0 && i < Plain.Length ? Plain[i] : c;
    }

    public static string Compact(string? text) => Normalize(text).Replace(" ", "");

    public static string[] Tokens(string? text) => Normalize(text).Split(' ', StringSplitOptions.RemoveEmptyEntries);

    /// <summary>
    /// Compact keys of the leading 1..4 words plus the whole name ("Family Mart SS15" → family, familymart, familymartss15),
    /// so a chain name typed with or without spaces matches the start of an outlet's name.
    /// </summary>
    public static IReadOnlyList<string> NameKeys(string? name)
    {
        var t = Tokens(name);
        var keys = new List<string>();
        for (var i = 1; i <= Math.Min(4, t.Length); i++) keys.Add(string.Concat(t.Take(i)));
        if (t.Length > 4) keys.Add(string.Concat(t));
        return keys.Distinct().ToList();
    }
}
