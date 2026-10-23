using System.Security.Cryptography;

namespace Gimano.Api.Live;

/// <summary>
/// Pseudonymous, rotating session ids for location pings (privacy rule: the traffic pipeline never sees user ids).
/// id = HMAC-SHA256(per-hour random key, userId)[..16]. Keys live only in this process's memory and are discarded
/// after the hour, so ids can't be linked across hours or back to a user — even by us after the fact.
/// Stable within the hour so the worker can count distinct drivers per road segment.
/// </summary>
public sealed class PingSessions(TimeProvider clock)
{
    private readonly Lock _lock = new();
    private long _hour = -1;
    private byte[] _key = [];

    public Guid For(Guid userId)
    {
        var hour = clock.GetUtcNow().ToUnixTimeSeconds() / 3600;
        byte[] key;
        lock (_lock)
        {
            if (hour != _hour)
            {
                _hour = hour;
                _key = RandomNumberGenerator.GetBytes(32);
            }
            key = _key;
        }
        Span<byte> mac = stackalloc byte[32];
        Span<byte> data = stackalloc byte[16];
        userId.TryWriteBytes(data);
        HMACSHA256.HashData(key, data, mac);
        return new Guid(mac[..16]);
    }
}
