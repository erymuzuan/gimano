using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Gimano.Core.Contracts;
using Gimano.Core.Domain;

namespace Gimano.Core.Support;

/// <summary>
/// AES-256-GCM encryption of volunteered contact details. The key comes from config (Feedback:ContactKey, base64 32 bytes)
/// and is never stored in the database. The ciphertext is bound to its report (associated data = subject type + id).
/// </summary>
public sealed partial class ContactCrypto
{
    public const string KeyId = "v1";
    private readonly byte[]? _key;

    public ContactCrypto(string? base64Key)
    {
        if (string.IsNullOrWhiteSpace(base64Key)) return;
        var k = Convert.FromBase64String(base64Key.Trim());
        if (k.Length != 32) throw new InvalidOperationException("Feedback:ContactKey must be 32 bytes (base64).");
        _key = k;
    }

    public bool Enabled => _key is not null;

    public ReportContact Encrypt(ContactSubject type, Guid subjectId, ContactDetails contact, DateTimeOffset now)
    {
        if (_key is null) throw new InvalidOperationException("Contact encryption key is not configured.");
        var plain = JsonSerializer.SerializeToUtf8Bytes(new { email = contact.Email, whatsapp = contact.Whatsapp });
        var nonce = RandomNumberGenerator.GetBytes(AesGcm.NonceByteSizes.MaxSize);
        var cipher = new byte[plain.Length + AesGcm.TagByteSizes.MaxSize];
        using var aes = new AesGcm(_key, AesGcm.TagByteSizes.MaxSize);
        aes.Encrypt(nonce, plain, cipher.AsSpan(0, plain.Length), cipher.AsSpan(plain.Length), Aad(type, subjectId));
        CryptographicOperations.ZeroMemory(plain);
        return new ReportContact { SubjectType = type, SubjectId = subjectId, Nonce = nonce, Ciphertext = cipher, KeyId = KeyId, CreatedAt = now };
    }

    public ContactDetails Decrypt(ReportContact c)
    {
        if (_key is null) throw new InvalidOperationException("Contact encryption key is not configured.");
        var tagLen = AesGcm.TagByteSizes.MaxSize;
        var plain = new byte[c.Ciphertext.Length - tagLen];
        using var aes = new AesGcm(_key, tagLen);
        aes.Decrypt(c.Nonce, c.Ciphertext.AsSpan(0, plain.Length), c.Ciphertext.AsSpan(plain.Length), plain, Aad(c.SubjectType, c.SubjectId));
        var doc = JsonDocument.Parse(plain).RootElement;
        return new ContactDetails(doc.GetProperty("email").GetString(), doc.GetProperty("whatsapp").GetString(), true);
    }

    private static byte[] Aad(ContactSubject type, Guid id) => Encoding.ASCII.GetBytes($"gimano-contact:{type}:{id:N}");

    /// <summary>Contact needs consent=true and at least one of a valid email / E.164 WhatsApp number.</summary>
    public static string? Validate(ContactDetails? c)
    {
        if (c is null) return null;
        var email = c.Email?.Trim(); var wa = c.Whatsapp?.Trim();
        if (string.IsNullOrEmpty(email) && string.IsNullOrEmpty(wa)) return c.Consent ? "contact needs an email or a WhatsApp number." : null;
        if (!c.Consent) return "contact requires consent: true.";
        if (!string.IsNullOrEmpty(email) && (email.Length > 254 || !EmailRegex().IsMatch(email))) return "contact.email is not a valid email address.";
        if (!string.IsNullOrEmpty(wa) && !E164Regex().IsMatch(wa)) return "contact.whatsapp must be in E.164 format, e.g. +60123456789.";
        return null;
    }

    public static ContactDetails Normalize(ContactDetails c) =>
        new(string.IsNullOrWhiteSpace(c.Email) ? null : c.Email.Trim(), string.IsNullOrWhiteSpace(c.Whatsapp) ? null : c.Whatsapp.Trim(), c.Consent);

    [GeneratedRegex(@"^[^@\s]{1,64}@[^@\s]+\.[^@\s]{2,}$")]
    private static partial Regex EmailRegex();

    [GeneratedRegex(@"^\+[1-9]\d{7,14}$")]
    private static partial Regex E164Regex();
}

/// <summary>Server-side re-scrub of crash texts (safety net; the apps scrub first) and crash grouping.</summary>
public static partial class CrashScrubber
{
    public const string Redacted = "[redacted]";

    public static string? Scrub(string? text, int max)
    {
        if (string.IsNullOrEmpty(text)) return text;
        var s = Jwt().Replace(text, Redacted);
        s = UrlQuery().Replace(s, m => m.Groups[1].Value + "?" + Redacted);
        s = Email().Replace(s, Redacted);
        s = LatLng().Replace(s, Redacted);
        s = AppDataPath().Replace(s, Redacted);
        s = Phone().Replace(s, Redacted);
        return s.Length <= max ? s : s[..max];
    }

    /// <summary>sha256(exception + "\n" + top 5 app frames), frames normalised (line numbers and addresses dropped).</summary>
    public static string Signature(string exception, string? stack)
    {
        var frames = (stack ?? "").Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
            .Where(l => l.StartsWith("at ", StringComparison.Ordinal) || FrameLike().IsMatch(l))
            .ToList();
        var app = frames.Where(IsAppFrame).ToList();
        var top = (app.Count > 0 ? app : frames).Take(5).Select(NormalizeFrame);
        var input = exception.Trim() + "\n" + string.Join("\n", top);
        return Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(input)));
    }

    internal static bool IsAppFrame(string frame) =>
        frame.Contains("my.gimano", StringComparison.Ordinal) || frame.Contains("Gimano", StringComparison.Ordinal);

    internal static string NormalizeFrame(string frame) =>
        Address().Replace(LineNumber().Replace(frame, ")"), "").Trim();

    [GeneratedRegex(@"eyJ[\w-]{5,}\.[\w-]{5,}\.[\w-]{5,}")]
    private static partial Regex Jwt();
    [GeneratedRegex(@"(https?://[^\s?""']+)\?[^\s""']+")]
    private static partial Regex UrlQuery();
    [GeneratedRegex(@"[\w.+-]+@[\w-]+(\.[\w-]+)+")]
    private static partial Regex Email();
    [GeneratedRegex(@"-?\d{1,3}\.\d{3,}\s*[,;/ ]\s*-?\d{1,3}\.\d{3,}")]
    private static partial Regex LatLng();
    [GeneratedRegex(@"(/data/(user|data)/\d+/[^\s:)]+|/var/mobile/Containers/Data/Application/[^\s:)]+|/private/var/mobile/[^\s:)]+)")]
    private static partial Regex AppDataPath();
    [GeneratedRegex(@"(?<![\w.:#])\+?\d[\d -]{7,14}\d(?![\w.])")]
    private static partial Regex Phone();
    [GeneratedRegex(@"^\d+\s+\S+\s+0x[0-9a-f]+")]
    private static partial Regex FrameLike();
    [GeneratedRegex(@":\d+\)")]
    private static partial Regex LineNumber();
    [GeneratedRegex(@"0x[0-9a-fA-F]+(\s*\+\s*\d+)?")]
    private static partial Regex Address();
}
