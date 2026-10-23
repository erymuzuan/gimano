using System.Security.Cryptography;
using System.Text.Json;
using Gimano.Core.Contracts;
using Microsoft.Extensions.Options;

namespace Gimano.Core.BackOffice;

public sealed class ContactOptions
{
    public const string Section = "Contact";
    /// <summary>Base64 of 32 random bytes. Set per host (deploy/.env CONTACT_ENCRYPTION_KEY), never in the repo or database.</summary>
    public string? EncryptionKey { get; set; }
}

public sealed record UnsealedContact(string? Email, string? Whatsapp);

/// <summary>AES-GCM for volunteered contact details (docs/api.md "Contact details"). The key never touches the database.</summary>
public sealed class ContactProtector
{
    private const int NonceSize = 12, TagSize = 16;
    private readonly byte[] _key;

    public ContactProtector(IOptions<ContactOptions> options)
    {
        var raw = options.Value.EncryptionKey;
        if (string.IsNullOrWhiteSpace(raw))
            throw new InvalidOperationException("Contact:EncryptionKey is not set (base64 of 32 random bytes; openssl rand -base64 32).");
        _key = Convert.FromBase64String(raw);
        if (_key.Length != 32) throw new InvalidOperationException("Contact:EncryptionKey must decode to exactly 32 bytes.");
    }

    public byte[] Seal(ContactDetails contact)
    {
        var plain = JsonSerializer.SerializeToUtf8Bytes(new UnsealedContact(Clean(contact.Email), Clean(contact.Whatsapp)));
        var output = new byte[NonceSize + TagSize + plain.Length];
        var nonce = output.AsSpan(0, NonceSize);
        RandomNumberGenerator.Fill(nonce);
        using var aes = new AesGcm(_key, TagSize);
        aes.Encrypt(nonce, plain, output.AsSpan(NonceSize + TagSize), output.AsSpan(NonceSize, TagSize));
        CryptographicOperations.ZeroMemory(plain);
        return output;
    }

    public UnsealedContact Unseal(byte[] sealedBytes)
    {
        if (sealedBytes.Length < NonceSize + TagSize) throw new CryptographicException("Sealed contact is too short.");
        var plain = new byte[sealedBytes.Length - NonceSize - TagSize];
        using var aes = new AesGcm(_key, TagSize);
        aes.Decrypt(sealedBytes.AsSpan(0, NonceSize), sealedBytes.AsSpan(NonceSize + TagSize), sealedBytes.AsSpan(NonceSize, TagSize), plain);
        try { return JsonSerializer.Deserialize<UnsealedContact>(plain)!; }
        finally { CryptographicOperations.ZeroMemory(plain); }
    }

    private static string? Clean(string? s) => string.IsNullOrWhiteSpace(s) ? null : s.Trim();
}
