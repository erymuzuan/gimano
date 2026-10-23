using System.Globalization;
using Gimano.Core.Contracts;

namespace Gimano.BackOffice.Components.Shared;

/// <summary>Display helpers. Times are shown in Malaysia time (UTC+8), where the maintainers are.</summary>
public static class Fmt
{
    private static readonly TimeSpan Myt = TimeSpan.FromHours(8);

    public static string When(DateTimeOffset t) => t.ToOffset(Myt).ToString("d MMM yyyy, HH:mm", CultureInfo.InvariantCulture);

    public static string Short(DateTimeOffset t) => t.ToOffset(Myt).ToString("d MMM, HH:mm", CultureInfo.InvariantCulture);

    public static string Ago(DateTimeOffset t, DateTimeOffset? now = null)
    {
        var d = (now ?? DateTimeOffset.UtcNow) - t;
        return d.TotalMinutes switch
        {
            < 1 => "just now",
            < 60 => $"{(int)d.TotalMinutes} min ago",
            < 60 * 24 => $"{(int)d.TotalHours} h ago",
            < 60 * 24 * 2 => "yesterday",
            < 60 * 24 * 30 => $"{(int)d.TotalDays} days ago",
            _ => Short(t),
        };
    }

    public static string Wire<T>(T value) where T : struct, Enum => GimanoJson.ToWire(value);

    public static string Wire<T>(T? value) where T : struct, Enum => value is { } v ? GimanoJson.ToWire(v) : "—";

    public static string Label(string wire) => wire.Replace('_', ' ');

    public static string Sig(string signature) => signature.Length > 12 ? signature[..6] + "…" + signature[^4..] : signature;

    public static string Device(DeviceInfo? d) => d is null ? "not shared" :
        string.Join(" · ", new[]
        {
            d.AppVersion is null ? null : $"{d.AppVersion}{(d.AppBuild is { } b ? $" ({b})" : "")}",
            $"{d.Platform} {d.OsVersion}".Trim(),
            string.Join(' ', new[] { d.Manufacturer, d.DeviceModel }.Where(s => !string.IsNullOrWhiteSpace(s))),
        }.Where(s => !string.IsNullOrWhiteSpace(s)));
}
