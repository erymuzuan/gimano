using System.Security.Cryptography;
using System.Text;
using NSec.Cryptography;

namespace Gimano.Core.Identity;

/// <summary>
/// Ed25519 signatures for the pseudonymous contributor identity (docs/identity-and-reputation.md).
/// Canonical strings are fixed; test vectors live in that doc so both apps can check their implementation.
/// </summary>
public static class Signatures
{
    public const int MaxSkewSeconds = 300;
    private static readonly SignatureAlgorithm Ed = SignatureAlgorithm.Ed25519;

    /// <summary><c>"GIMANO1\n" + METHOD + "\n" + PATH_AND_QUERY + "\n" + ts + "\n" + hex(sha256(body))</c></summary>
    public static string RequestCanonical(string method, string pathAndQuery, long ts, ReadOnlySpan<byte> body) =>
        $"GIMANO1\n{method.ToUpperInvariant()}\n{pathAndQuery}\n{ts}\n{Convert.ToHexStringLower(SHA256.HashData(body))}";

    /// <summary><c>"GIMANO-AUTH\n" + userId + "\n" + ts</c> (userId in lower-case "D" format).</summary>
    public static string AuthCanonical(Guid userId, long ts) => $"GIMANO-AUTH\n{userId:D}\n{ts}";

    /// <summary><c>"GIMANO-KEY\n" + userId + "\n" + base64(newPublicKey)</c>, signed by the current key to rotate.</summary>
    public static string KeyRotationCanonical(Guid userId, string newPublicKeyBase64) => $"GIMANO-KEY\n{userId:D}\n{newPublicKeyBase64}";

    public static bool Verify(byte[] publicKey, string canonical, byte[] signature)
    {
        if (publicKey.Length != 32 || signature.Length != 64) return false;
        if (!PublicKey.TryImport(Ed, publicKey, KeyBlobFormat.RawPublicKey, out var key) || key is null) return false;
        return Ed.Verify(key, Encoding.UTF8.GetBytes(canonical), signature);
    }

    /// <summary>For tests and tools: sign with a 32-byte seed (libsodium crypto_sign_seed_keypair semantics).</summary>
    public static (byte[] PublicKey, byte[] Signature) Sign(byte[] seed, string canonical)
    {
        using var key = Key.Import(Ed, seed, KeyBlobFormat.RawPrivateKey);
        return (key.PublicKey.Export(KeyBlobFormat.RawPublicKey), Ed.Sign(key, Encoding.UTF8.GetBytes(canonical)));
    }

    public static byte[] PublicKeyFromSeed(byte[] seed)
    {
        using var key = Key.Import(Ed, seed, KeyBlobFormat.RawPrivateKey);
        return key.PublicKey.Export(KeyBlobFormat.RawPublicKey);
    }

    public static bool InWindow(long ts, DateTimeOffset now) => Math.Abs(now.ToUnixTimeSeconds() - ts) <= MaxSkewSeconds;

    public static bool TryBase64(string? s, out byte[] bytes)
    {
        bytes = [];
        if (string.IsNullOrWhiteSpace(s) || s.Length > 200) return false;
        var t = s.Trim().Replace('-', '+').Replace('_', '/');
        t += new string('=', (4 - t.Length % 4) % 4);
        try { bytes = Convert.FromBase64String(t); return true; } catch (FormatException) { return false; }
    }
}
