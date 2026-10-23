using Gimano.Core.Community;
using Gimano.Core.Contracts;
using Gimano.Core.Domain;

namespace Gimano.Core.Issues;

/// <summary>Issue labels and one-line summaries in English and Malay ("anda").</summary>
public static class IssueText
{
    public static string Kind(IssueKind k, string lang) => (k, L10n.IsMs(lang)) switch
    {
        (IssueKind.RoadClosedTemp, true) => "ditutup", (IssueKind.RoadClosedTemp, false) => "closed",
        (IssueKind.RoadNotExist, true) => "tiada jalan", (IssueKind.RoadNotExist, false) => "no road here",
        (IssueKind.OnewayWrongClosedDir, true) => "sehala", (IssueKind.OnewayWrongClosedDir, false) => "one-way",
        (IssueKind.OnewayWrongOpenDir, true) => "sebenarnya dua hala", (IssueKind.OnewayWrongOpenDir, false) => "actually two-way",
        (IssueKind.TurnNotAllowed, true) => "tak boleh belok", (IssueKind.TurnNotAllowed, false) => "no turn",
        (IssueKind.OddRoute, true) => "laluan pelik", (IssueKind.OddRoute, false) => "odd route",
        (IssueKind.NewRoad, true) => "jalan baharu", (IssueKind.NewRoad, false) => "new road",
        (IssueKind.PlaceClosedPerm, true) => "tutup kekal", (IssueKind.PlaceClosedPerm, false) => "permanently closed",
        (IssueKind.PlaceClosedTemp, true) => "tutup sementara", (IssueKind.PlaceClosedTemp, false) => "temporarily closed",
        (IssueKind.PlaceHoursWrong, true) => "waktu buka mungkin berubah", (IssueKind.PlaceHoursWrong, false) => "opening hours may have changed",
        (IssueKind.PlaceEntranceWrong, true) => "pintu masuk dibetulkan", (IssueKind.PlaceEntranceWrong, false) => "entrance corrected",
        (IssueKind.PlaceInfoWrong, true) => "maklumat salah", (IssueKind.PlaceInfoWrong, false) => "wrong details",
        (IssueKind.PlaceDuplicate, true) => "pendua", (IssueKind.PlaceDuplicate, false) => "duplicate",
        _ => GimanoJson.ToWire(k).Replace('_', ' '),
    };

    public static string Subtype(string? subtype, string lang) => (subtype, L10n.IsMs(lang)) switch
    {
        ("accident", true) => "kemalangan", ("flood", true) => "banjir", ("event", true) => "acara", ("roadworks", true) => "kerja jalan",
        ("gate", true) => "pagar", ("private", true) => "persendirian", ("track", true) => "denai",
        (null, _) => "",
        _ => subtype!,
    };

    /// <summary>"Jalan SS 2/24: ditutup (banjir)" · "Mydin Subang Jaya: tutup kekal".</summary>
    public static string Summary(Issue i, string lang, string? placeName = null)
    {
        var what = Kind(i.Kind, lang);
        var sub = Subtype(i.Subtype, lang);
        if (sub.Length > 0) what += $" ({sub})";
        var subject = placeName ?? i.RoadName;
        return string.IsNullOrWhiteSpace(subject) ? Capitalise(what) : $"{subject}: {what}";
    }

    private static string Capitalise(string s) => s.Length == 0 ? s : char.ToUpperInvariant(s[0]) + s[1..];
}
