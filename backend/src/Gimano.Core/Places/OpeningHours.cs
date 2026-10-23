using System.Globalization;
using System.Text.RegularExpressions;

namespace Gimano.Core.Places;

/// <summary>
/// Minimal evaluator for the common subset of OSM <c>opening_hours</c>:
/// <c>24/7</c>, and rules like <c>Mo-Fr 08:00-17:00; Sa 08:00-12:00,14:00-18:00; Su off</c>.
/// Returns null for anything it doesn't understand (public holidays, months, comments…).
/// </summary>
public static partial class OpeningHours
{
    private static readonly string[] Days = ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"];

    public static bool? IsOpen(string? spec, DateTime localTime)
    {
        if (string.IsNullOrWhiteSpace(spec)) return null;
        var s = spec.Trim();
        if (s == "24/7") return true;

        var day = ((int)localTime.DayOfWeek + 6) % 7; // Monday = 0
        var minutes = localTime.Hour * 60 + localTime.Minute;
        bool? result = null;

        foreach (var raw in s.Split(';', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
        {
            var m = RuleRegex().Match(raw);
            if (!m.Success) return null;
            var dayPart = m.Groups["days"].Value;
            var timePart = m.Groups["times"].Value.Trim();

            var days = dayPart.Length == 0 ? Enumerable.Range(0, 7).ToHashSet() : ParseDays(dayPart);
            if (days is null) return null;

            // Previous-day spans past midnight (e.g. "Fr 18:00-02:00") also apply early today.
            var prev = (day + 6) % 7;

            if (timePart is "off" or "closed")
            {
                if (days.Contains(day)) result = false;
                continue;
            }

            var ranges = ParseRanges(timePart);
            if (ranges is null) return null;
            if (days.Contains(day))
            {
                // A later rule for the same day overrides earlier ones (OSM semantics).
                result = ranges.Any(r => r.End > r.Start ? minutes >= r.Start && minutes < r.End : minutes >= r.Start);
            }
            if (days.Contains(prev) && ranges.Any(r => r.End <= r.Start && minutes < r.End)) result = true;
        }
        return result ?? false;
    }

    private static HashSet<int>? ParseDays(string part)
    {
        var set = new HashSet<int>();
        foreach (var token in part.Split(',', StringSplitOptions.TrimEntries))
        {
            var range = token.Split('-');
            var a = Array.IndexOf(Days, range[0]);
            if (a < 0) return null;
            if (range.Length == 1) { set.Add(a); continue; }
            var b = Array.IndexOf(Days, range[1]);
            if (b < 0) return null;
            for (var d = a; ; d = (d + 1) % 7) { set.Add(d); if (d == b) break; }
        }
        return set;
    }

    private static List<(int Start, int End)>? ParseRanges(string part)
    {
        var list = new List<(int, int)>();
        foreach (var token in part.Split(',', StringSplitOptions.TrimEntries))
        {
            var se = token.Split('-');
            if (se.Length != 2 || !TryMinutes(se[0], out var a) || !TryMinutes(se[1], out var b)) return null;
            list.Add((a, b == 24 * 60 ? 24 * 60 : b));
        }
        return list;
    }

    private static bool TryMinutes(string hhmm, out int minutes)
    {
        minutes = 0;
        var p = hhmm.Trim().Split(':');
        if (p.Length != 2 || !int.TryParse(p[0], NumberStyles.None, CultureInfo.InvariantCulture, out var h)
            || !int.TryParse(p[1], NumberStyles.None, CultureInfo.InvariantCulture, out var m) || h > 24 || m > 59) return false;
        minutes = h * 60 + m;
        return true;
    }

    [GeneratedRegex(@"^(?<days>(?:Mo|Tu|We|Th|Fr|Sa|Su)(?:-(?:Mo|Tu|We|Th|Fr|Sa|Su))?(?:\s*,\s*(?:Mo|Tu|We|Th|Fr|Sa|Su)(?:-(?:Mo|Tu|We|Th|Fr|Sa|Su))?)*)?\s*(?<times>.+)$")]
    private static partial Regex RuleRegex();
}
