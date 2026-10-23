using System.Globalization;
using System.Text;
using Gimano.Core.Contracts;

namespace Gimano.Core.Support;

/// <summary>
/// Builds the public GitHub issue for a feedback item. Privacy rules (docs/api.md#feedback): never the user id,
/// contact details, device model or location, and no screenshot bytes. Maintainers see those in the back office.
/// </summary>
public static class FeedbackIssueFormatter
{
    public const int TitleMaxLength = 80;

    public static (string Title, string Body, string[] Labels) Format(Domain.Feedback f)
    {
        var category = GimanoJson.ToWire(f.Category);
        var firstLine = f.Message.Split('\n', 2)[0].Trim();
        var title = $"[{category}] {(firstLine.Length > TitleMaxLength ? firstLine[..(TitleMaxLength - 1)] + "…" : firstLine)}";

        var sb = new StringBuilder();
        sb.AppendLine(f.Message.Trim()).AppendLine();
        sb.AppendLine("| | |").AppendLine("|---|---|");
        sb.AppendLine($"| Category | {category} |");
        if (f.Rating is { } r) sb.AppendLine($"| Rating | {r}/5 |");
        if (f.Screen is { } s) sb.AppendLine($"| Screen | {GimanoJson.ToWire(s)} |");
        if (f.Context is { } c)
        {
            void Row(string k, string? v) { if (!string.IsNullOrWhiteSpace(v)) sb.AppendLine($"| {k} | {Escape(v)} |"); }
            Row("App", c.AppVersion);
            Row("Platform", c.Platform is null ? null : $"{c.Platform} {c.OsVersion}".Trim());
            Row("Locale", c.Locale);
            Row("Route", c.RouteId);
            Row("Place", c.PlaceId);
        }
        sb.AppendLine($"| Screenshot | {(f.Screenshot is { Length: > 0 } ? "attached in Gimano (not public)" : "none")} |");
        sb.AppendLine().AppendLine($"Gimano feedback `{f.Id}` · {f.CreatedAt:yyyy-MM-dd HH:mm} UTC");
        return (title, sb.ToString(), ["feedback", category]);
    }

    public static string RoundedLocation(double lat, double lng) =>
        string.Create(CultureInfo.InvariantCulture, $"{Math.Round(lat, 2):0.00}, {Math.Round(lng, 2):0.00}");

    private static string Escape(string v) => v.Replace("|", "\\|").Replace("\n", " ");
}
