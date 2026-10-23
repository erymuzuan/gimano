using System.Collections.Concurrent;
using System.Security.Cryptography;

namespace Gimano.Api.Live;

/// <summary>
/// "Share my drive" relay. Holds only the latest end-to-end encrypted blob per share, in memory, with a TTL.
/// Never persisted, never logged; the server can't read the blobs (the key stays in the link's #fragment).
/// Single-instance by design: if the API scales out, move this to a shared in-memory store (e.g. Redis with TTL).
/// </summary>
public sealed class ShareStore(TimeProvider clock)
{
    public const int MinMinutes = 15;
    public const int MaxMinutes = 720;
    public const int MaxActivePerUser = 3;
    public const int NonceBytes = 24;
    public const int MaxCiphertextBytes = 1024;
    /// <summary>Ended shares are remembered (without data) so GET can answer 410 rather than 404.</summary>
    public static readonly TimeSpan TombstoneFor = TimeSpan.FromHours(12);

    private sealed class Share
    {
        public required Guid Owner;
        public required byte[] TokenHash;
        public required DateTimeOffset ExpiresAt;
        public byte[]? Nonce;
        public byte[]? Ciphertext;
        public DateTimeOffset? UpdatedAt;
        public DateTimeOffset? EndedAt;
    }

    private readonly ConcurrentDictionary<string, Share> _shares = new(StringComparer.Ordinal);

    public enum Result { Ok, NotFound, Gone, Unauthorized, TooMany, TooLarge, Invalid }

    public sealed record Created(string ShareId, string WriteToken, DateTimeOffset ExpiresAt);
    public sealed record Blob(byte[]? Nonce, byte[]? Ciphertext, DateTimeOffset? UpdatedAt, DateTimeOffset ExpiresAt);

    public (Result, Created?) Create(Guid owner, int expiresInMinutes)
    {
        if (expiresInMinutes is < MinMinutes or > MaxMinutes) return (Result.Invalid, null);
        Sweep();
        var now = clock.GetUtcNow();
        if (_shares.Values.Count(s => s.Owner == owner && IsActive(s, now)) >= MaxActivePerUser) return (Result.TooMany, null);
        var id = Base64Url(RandomNumberGenerator.GetBytes(16));      // 128-bit
        var token = Base64Url(RandomNumberGenerator.GetBytes(32));   // 256-bit
        var share = new Share { Owner = owner, TokenHash = SHA256.HashData(System.Text.Encoding.ASCII.GetBytes(token)), ExpiresAt = now.AddMinutes(expiresInMinutes) };
        _shares[id] = share;
        return (Result.Ok, new Created(id, token, share.ExpiresAt));
    }

    public Result Put(string id, string? token, byte[] nonce, byte[] ciphertext)
    {
        var (r, s) = Authorize(id, token);
        if (r != Result.Ok) return r;
        if (nonce.Length != NonceBytes || ciphertext.Length < 17) return Result.Invalid;   // ≥ 16-byte tag + 1
        if (ciphertext.Length > MaxCiphertextBytes) return Result.TooLarge;
        lock (s!)
        {
            s.Nonce = nonce;
            s.Ciphertext = ciphertext;
            s.UpdatedAt = clock.GetUtcNow();
        }
        return Result.Ok;
    }

    public (Result, Blob?) Get(string id)
    {
        if (!_shares.TryGetValue(id, out var s)) return (Result.NotFound, null);
        var now = clock.GetUtcNow();
        if (!IsActive(s, now)) { End(s, now); return (Result.Gone, null); }
        lock (s) return (Result.Ok, new Blob(s.Nonce, s.Ciphertext, s.UpdatedAt, s.ExpiresAt));
    }

    public Result Delete(string id, string? token)
    {
        var (r, s) = Authorize(id, token);
        if (r != Result.Ok) return r;
        End(s!, clock.GetUtcNow());
        return Result.Ok;
    }

    public int ActiveCount => _shares.Values.Count(s => IsActive(s, clock.GetUtcNow()));

    private (Result, Share?) Authorize(string id, string? token)
    {
        if (!_shares.TryGetValue(id, out var s)) return (Result.NotFound, null);
        if (string.IsNullOrEmpty(token)) return (Result.Unauthorized, null);
        var hash = SHA256.HashData(System.Text.Encoding.ASCII.GetBytes(token));
        if (!CryptographicOperations.FixedTimeEquals(hash, s.TokenHash)) return (Result.Unauthorized, null);
        var now = clock.GetUtcNow();
        if (!IsActive(s, now)) { End(s, now); return (Result.Gone, null); }
        return (Result.Ok, s);
    }

    private static bool IsActive(Share s, DateTimeOffset now) => s.EndedAt is null && now < s.ExpiresAt;

    /// <summary>Ending drops the ciphertext immediately; only a tombstone remains for the 410.</summary>
    private static void End(Share s, DateTimeOffset now)
    {
        lock (s)
        {
            s.EndedAt ??= now < s.ExpiresAt ? now : s.ExpiresAt;
            s.Nonce = null;
            s.Ciphertext = null;
        }
    }

    /// <summary>Ends expired shares and forgets tombstones older than 12 h.</summary>
    public void Sweep()
    {
        var now = clock.GetUtcNow();
        foreach (var (id, s) in _shares)
        {
            if (IsActive(s, now)) continue;
            End(s, now);
            if (now - s.EndedAt!.Value > TombstoneFor) _shares.TryRemove(id, out _);
        }
    }

    public static string Base64Url(byte[] b) => Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}

/// <summary>Sweeps expired shares every minute.</summary>
public sealed class ShareSweeper(ShareStore store) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromMinutes(1));
        while (await timer.WaitForNextTickAsync(stoppingToken)) store.Sweep();
    }
}
