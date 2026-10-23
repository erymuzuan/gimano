using Gimano.Core.Contracts;
using Gimano.Core.Routing;

namespace Gimano.Core.Community;

/// <summary>Inbox texts (BM/EN by the user's locale), daily aggregation keys, retention and timeline paging.</summary>
public static class InboxRules
{
    public static readonly TimeSpan Retention = TimeSpan.FromDays(90);
    public const int PageSize = 30;

    public static bool IsMalay(string? locale) => locale is null || locale.StartsWith("ms", StringComparison.OrdinalIgnoreCase);

    /// <summary>"report_thanked:2026-10-23" (Malaysia date) for kinds aggregated per day; null for one-off items.</summary>
    public static string? AggregateKey(InboxKind kind, DateTimeOffset at) => kind switch
    {
        InboxKind.ReportThanked or InboxKind.ReviewHelpful =>
            $"{GimanoJson.ToWire(kind)}:{TimeZoneInfo.ConvertTime(at, ValhallaMapper.MalaysiaTime):yyyy-MM-dd}",
        _ => null,
    };

    public static (string Title, string Body) Text(InboxKind kind, int count, string? locale, string? subject = null)
    {
        var ms = IsMalay(locale);
        var s = subject ?? "";
        return kind switch
        {
            InboxKind.ReportThanked => ms
                ? (count == 1 ? "Seorang pemandu berterima kasih kepada anda" : $"{count} pemandu berterima kasih kepada anda", "Laporan anda membantu pemandu lain hari ini.")
                : (count == 1 ? "A driver thanked you" : $"{count} drivers thanked you", "Your reports helped other drivers today."),
            InboxKind.ReviewHelpful => ms
                ? (count == 1 ? "Seseorang mendapati ulasan anda membantu" : $"{count} orang mendapati ulasan anda membantu", s)
                : (count == 1 ? "Someone found your review helpful" : $"{count} people found your review helpful", s),
            InboxKind.PlacePublished => ms
                ? ($"{s} kini disahkan", "Tempat yang anda tambah telah disahkan oleh pengguna lain dan kini boleh dicari.")
                : ($"{s} is now verified", "Other users confirmed the place you added; it now appears in search."),
            InboxKind.PlaceRejected => ms
                ? ($"{s} disembunyikan untuk semakan", "Beberapa pengguna melaporkan tempat ini tidak wujud atau di lokasi salah.")
                : ($"{s} is hidden for review", "Several users reported that this place doesn't exist or is in the wrong location."),
            InboxKind.PlaceExpired => ms
                ? ($"{s} tamat tempoh pengesahan", "Tiada cukup pengesahan dalam 30 hari. Anda boleh menambahnya semula.")
                : ($"{s} expired unverified", "Not enough people confirmed it within 30 days. You can add it again."),
            InboxKind.EditAccepted => ms
                ? ("Cadangan suntingan anda diterima", s)
                : ("Your suggested edit was accepted", s),
            InboxKind.FeedbackStatus => ms
                ? ("Maklum balas anda telah dikemas kini", $"Status: {s}")
                : ("Your feedback was updated", $"Status: {s}"),
            _ => (s, ""),
        };
    }

    /// <summary>Merges the user's items and announcements, newest first, keyset-paged by (createdAt, id).</summary>
    public static (List<InboxItemDto> Page, string? Next) Merge(IEnumerable<InboxItemDto> items, IEnumerable<InboxItemDto> announcements, int pageSize = PageSize)
    {
        var all = items.Concat(announcements).OrderByDescending(i => i.CreatedAt).ThenByDescending(i => i.Id).ToList();
        var page = all.Take(pageSize).ToList();
        var next = all.Count > pageSize ? CommunityRules.EncodeCursor(page[^1].CreatedAt.UtcTicks, page[^1].Id) : null;
        return (page, next);
    }

    public static (DateTimeOffset At, Guid Id)? DecodeCursor(string? cursor) =>
        CommunityRules.DecodeCursor(cursor) is [var t, var id] && long.TryParse(t, out var ticks) && Guid.TryParse(id, out var g)
            ? (new DateTimeOffset(ticks, TimeSpan.Zero), g) : null;
}
