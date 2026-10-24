using Microsoft.Extensions.Caching.Memory;

namespace Gimano.Api.Endpoints;

/// <summary>
/// Feedback and crash reports that carry a volunteered contact are stored without the user id, so the per-user rate limits
/// can't count them in the database. They are counted here instead: in process memory only, per user, for the length of the
/// rate-limit window, then forgotten. Nothing links the stored report to the user.
/// </summary>
internal static class UnlinkedReports
{
    private static readonly Lock Gate = new();

    private static string Key(string kind, Guid userId) => $"unlinked:{kind}:{userId:N}";

    public static int Count(IMemoryCache cache, string kind, Guid userId, DateTimeOffset now, TimeSpan window)
    {
        lock (Gate)
            return cache.TryGetValue(Key(kind, userId), out List<DateTimeOffset>? times) && times is not null ? times.Count(t => t > now - window) : 0;
    }

    public static void Add(IMemoryCache cache, string kind, Guid userId, DateTimeOffset now, TimeSpan window)
    {
        lock (Gate)
        {
            var times = cache.TryGetValue(Key(kind, userId), out List<DateTimeOffset>? t) && t is not null ? t : [];
            times.RemoveAll(x => x <= now - window);
            times.Add(now);
            cache.Set(Key(kind, userId), times, now + window);
        }
    }
}
