using System.Globalization;
using System.Text;
using Gimano.Core.Contracts;

namespace Gimano.Core.Support;

/// <summary>
/// Builds the public GitHub issue for a feedback item. Privacy rules (docs/api.md#feedback):
/// never the device id or user id, never contact details, device model or location, no screenshot bytes.
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
        // Never contact details, device model or location (docs/api.md "Feedback").
        if (f.Device is { } d)
        {
            void Row(string k, string? v) { if (!string.IsNullOrWhiteSpace(v)) sb.AppendLine($"| {k} | {Escape(v)} |"); }
            Row("App", d.AppVersion is null ? null : $"{d.AppVersion}{(d.AppBuild is { } b ? $" ({b})" : "")}");
            Row("Platform", d.Platform is null ? null : $"{d.Platform} {d.OsVersion}".Trim());
            Row("Locale", d.Locale);
            Row("Theme", d.Theme);
        }
        sb.AppendLine($"| Screenshot | {(f.Screenshot is { Length: > 0 } ? "attached in Gimano (not public)" : "none")} |");
        sb.AppendLine().AppendLine($"Gimano feedback `{f.Id}` · {f.CreatedAt:yyyy-MM-dd HH:mm} UTC");
        return (title, sb.ToString(), ["feedback", category]);
    }

    private static string Escape(string v) => v.Replace("|", "\\|").Replace("\n", " ");
}
