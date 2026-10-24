using System.Security.Cryptography;
using System.Text;

namespace Gimano.Core.Community;

/// <summary>
/// Opaque, stable per-author handle for "hide content from this contributor" (Apple 1.2): the apps keep a block list of
/// handles on the device only. handle = base64url(HMAC-SHA256(server secret, user id)[..12]). It is not the user id or the
/// pseudonym's public key, can't be reversed without the secret, and the same author always gets the same handle.
/// Nothing is stored: DELETE /me has nothing to remove (once the user row is gone, their content shows no handle at all).
/// </summary>
public sealed class AuthorHandles
{
    public const int Bytes = 12;   // 16 base64url characters
    private readonly byte[] _key;

    public AuthorHandles(byte[] key)
    {
        if (key.Length < 32) throw new ArgumentException("The author-handle key must be at least 32 bytes.", nameof(key));
        _key = key;
    }

    /// <summary>
    /// Community:AuthorHandleKey (base64, >= 32 bytes) when set; otherwise a key derived from the JWT signing key with a
    /// separate label (HKDF), so dev/prod work without another secret. Set the dedicated key in production: rotating the
    /// JWT key would otherwise change every handle and empty the apps' block lists.
    /// </summary>
    public static AuthorHandles FromConfig(string? handleKeyBase64, string jwtSigningKey)
    {
        if (!string.IsNullOrWhiteSpace(handleKeyBase64)) return new AuthorHandles(Convert.FromBase64String(handleKeyBase64.Trim()));
        var ikm = Encoding.UTF8.GetBytes(jwtSigningKey);
        return new AuthorHandles(HKDF.DeriveKey(HashAlgorithmName.SHA256, ikm, 32, info: Encoding.ASCII.GetBytes("manotu/author-handle/v1")));
    }

    public string? For(Guid? userId)
    {
        if (userId is not { } id) return null;
        Span<byte> data = stackalloc byte[16];
        id.TryWriteBytes(data);
        Span<byte> mac = stackalloc byte[32];
        HMACSHA256.HashData(_key, data, mac);
        return Convert.ToBase64String(mac[..Bytes]).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    }
}
