namespace Gimano.Core.Events;

/// <summary>
/// One event short link (docs/events.md §5.6, docs/api.md "Event short links"): an end-to-end encrypted event pack the
/// server keeps without the key. Nothing else is stored: no title, IP, user agent or user id (only a keyed hash of it).
/// </summary>
public sealed class Drop
{
    /// <summary>10 base58 characters, server-chosen (CSPRNG).</summary>
    public required string Id { get; set; }
    /// <summary><c>nonce (24) ‖ XChaCha20-Poly1305 ciphertext</c>; cleared (empty) as soon as the drop is deleted.</summary>
    public required byte[] Blob { get; set; }
    /// <summary>SHA-256 of the 256-bit write token; the token itself is only ever in the organizer's app.</summary>
    public required byte[] WriteTokenHash { get; set; }
    /// <summary>HMAC-SHA256(Drops:OwnerKey, user id): only for the per-user quota and <c>DELETE /me</c>.</summary>
    public required byte[] OwnerHash { get; set; }
    public DateTimeOffset CreatedAt { get; set; }
    public DateTimeOffset UpdatedAt { get; set; }
    public DateTimeOffset ExpiresAt { get; set; }
    /// <summary>Set by <c>DELETE /drops/{id}</c>; GET answers 410 until the hourly purge removes the row.</summary>
    public DateTimeOffset? DeletedAt { get; set; }

    public bool IsLive(DateTimeOffset now) => DeletedAt is null && now < ExpiresAt;
}
