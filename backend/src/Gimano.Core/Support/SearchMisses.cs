using System.Globalization;
using System.Text.RegularExpressions;
using Gimano.Core.Search;

namespace Gimano.Core.Support;

/// <summary>
/// Anonymous zero-result counting rules (docs/api.md "Search misses"). Only (scrubbed query, ~11 km cell, ISO week, count) is
/// stored: no user, token, IP or exact location. Digit-heavy tokens (house numbers, phone fragments), emails and phone numbers
/// are removed before counting; rows with count &lt; 5 are dropped once their week is over; everything goes after 26 weeks.
/// </summary>
public static partial class SearchMisses
{
    public const int MinCountToKeep = 5;
    public const int KeepWeeks = 26;

    [GeneratedRegex(@"[^\s@]+@[^\s@]+")]
    private static partial Regex Email();

    [GeneratedRegex(@"\+?\d[\d\s\-()]{6,}\d")]
    private static partial Regex Phone();

    /// <summary>The text that may be counted, or null when nothing safe is left.</summary>
    public static string? Scrub(string raw)
    {
        var s = Phone().Replace(Email().Replace(raw, " "), " ");
        var tokens = TextNormalizer.Tokens(s).Where(t =>
        {
            var digits = t.Count(char.IsDigit);
            return digits < 3 && digits * 2 < t.Length;   // drops "12", "12a", "47300", "a1b2c3"; keeps "ss" "7e"? (7e: 1 of 2 → dropped)
        }).ToList();
        if (tokens.Count == 0) return null;
        var q = string.Join(' ', tokens);
        if (q.Length < 2) return null;
        return q.Length > 100 ? q[..100] : q;
    }

    /// <summary>"3.1,101.6": latitude/longitude floored to 0.1° (~11 km); "" without a location.</summary>
    public static string Cell(double? lat, double? lng) => lat is { } a && lng is { } b
        ? string.Create(CultureInfo.InvariantCulture, $"{Math.Floor(a * 10) / 10:0.0},{Math.Floor(b * 10) / 10:0.0}")
        : "";

    public static string Week(DateTimeOffset at) =>
        string.Create(CultureInfo.InvariantCulture, $"{ISOWeek.GetYear(at.UtcDateTime):0000}-W{ISOWeek.GetWeekOfYear(at.UtcDateTime):00}");

    public static string WeekAgo(DateTimeOffset now, int weeks) => Week(now.AddDays(-7 * weeks));

    /// <summary>Drops finished weeks' rows with count &lt; 5 and everything older than 26 weeks. ISO week strings sort correctly.</summary>
    public static async Task<int> RetentionAsync(Data.GimanoDbContext db, DateTimeOffset now, CancellationToken ct)
    {
        var current = Week(now);
        var oldest = WeekAgo(now, KeepWeeks);
        return await Microsoft.EntityFrameworkCore.EntityFrameworkQueryableExtensions.ExecuteDeleteAsync(
            System.Linq.Queryable.Where(db.SearchMisses, m => (string.Compare(m.Week, current) < 0 && m.Count < MinCountToKeep) || string.Compare(m.Week, oldest) < 0), ct);
    }
}
