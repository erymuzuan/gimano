using System.Collections.Concurrent;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Gimano.Core.Identity;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Api.Identity;

public enum AttestationMode { Off, Log, Enforce }

/// <summary>
/// Device attestation for votes (docs/identity-and-reputation.md §5). Only a boolean verdict is kept (with the pending vote).
/// Android: Play Integrity standard request decoded server-side (service account). The APK is sideloaded, so
/// appRecognitionVerdict is expected to be UNRECOGNIZED: only MEETS_DEVICE_INTEGRITY + package name (+ requestHash) count.
/// iOS: App Attest key attested once (POST /me/attest), then an assertion per vote.
/// </summary>
public sealed class AttestationService(IConfiguration config, IHttpClientFactory http, GimanoDbContext db, TimeProvider clock, ILogger<AttestationService> logger)
{
    private static readonly ConcurrentDictionary<string, (byte[] Challenge, DateTimeOffset Expires)> Challenges = new();
    private static (string Token, DateTimeOffset Expires)? _googleToken;
    private static readonly SemaphoreSlim GoogleGate = new(1, 1);

    public AttestationMode Mode => Enum.TryParse<AttestationMode>(config["Attestation:Mode"], true, out var m) ? m : AttestationMode.Log;

    /// <summary>Verdict for a vote: true/false, or null if not provided or not configured.</summary>
    public async Task<bool?> VerifyVoteAsync(Guid userId, AttestationClaim? claim, string placeId, long ts, CancellationToken ct)
    {
        if (Mode == AttestationMode.Off) return null;
        if (claim is null || string.IsNullOrWhiteSpace(claim.Token)) { Log(null, "no attestation sent"); return null; }
        var clientData = AppAttest.VoteClientData(placeId, ts);
        bool? verdict;
        try
        {
            verdict = claim.Platform switch
            {
                Platform.Android => await AndroidAsync(claim.Token, clientData, ct),
                Platform.Ios => await IosAssertionAsync(userId, claim.Token, clientData, ct),
                _ => null,
            };
        }
        catch (Exception ex) when (ex is CryptographicException or FormatException or HttpRequestException or JsonException or InvalidOperationException
                                   or System.Formats.Cbor.CborContentException)
        {
            logger.LogInformation("Attestation ({Platform}) failed: {Message}", claim.Platform, ex.Message);
            verdict = false;
        }
        Log(verdict, claim.Platform.ToString());
        return verdict;
    }

    private void Log(bool? verdict, string what)
    {
        if (Mode == AttestationMode.Log) logger.LogInformation("Attestation (log mode) verdict={Verdict} ({What})", verdict?.ToString() ?? "n/a", what);
    }

    // ---- Android: Play Integrity ----------------------------------------------------------------------

    private async Task<bool?> AndroidAsync(string token, byte[] clientData, CancellationToken ct)
    {
        var package = config["Attestation:Android:PackageName"];
        var sa = config["Attestation:Android:ServiceAccountJson"]
                 ?? (config["Attestation:Android:ServiceAccountFile"] is { Length: > 0 } f && File.Exists(f) ? await File.ReadAllTextAsync(f, ct) : null);
        if (string.IsNullOrEmpty(package) || string.IsNullOrEmpty(sa))
        {
            logger.LogInformation("Play Integrity not configured (Attestation:Android:PackageName / ServiceAccountJson); verdict n/a");
            return null;
        }
        var access = await GoogleAccessTokenAsync(sa, ct);
        using var req = new HttpRequestMessage(HttpMethod.Post, $"https://playintegrity.googleapis.com/v1/{package}:decodeIntegrityToken")
        {
            Content = JsonContent.Create(new { integrity_token = token }),
        };
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", access);
        using var resp = await http.CreateClient("attestation").SendAsync(req, ct);
        resp.EnsureSuccessStatusCode();
        var payload = (await resp.Content.ReadFromJsonAsync<JsonNode>(ct))?["tokenPayloadExternal"];
        return EvaluatePlayIntegrity(payload, package, Convert.ToHexStringLower(SHA256.HashData(clientData)));
    }

    /// <summary>
    /// Decoded verdict → OK when the device meets integrity, the package matches, and the requestHash (if the app sent one)
    /// is hex(sha256(clientData)). appRecognitionVerdict is ignored (sideloaded APK → UNRECOGNIZED).
    /// </summary>
    internal static bool EvaluatePlayIntegrity(JsonNode? payload, string package, string expectedRequestHash)
    {
        if (payload is null) return false;
        var device = payload["deviceIntegrity"]?["deviceRecognitionVerdict"]?.AsArray().Select(v => v?.GetValue<string>()).ToList() ?? [];
        var pkg = payload["requestDetails"]?["requestPackageName"]?.GetValue<string>();
        var hash = payload["requestDetails"]?["requestHash"]?.GetValue<string>();
        return device.Contains("MEETS_DEVICE_INTEGRITY") && pkg == package && (hash is null || hash == expectedRequestHash);
    }

    private async Task<string> GoogleAccessTokenAsync(string serviceAccountJson, CancellationToken ct)
    {
        if (_googleToken is { } t && t.Expires > clock.GetUtcNow().AddMinutes(2)) return t.Token;
        await GoogleGate.WaitAsync(ct);
        try
        {
            if (_googleToken is { } t2 && t2.Expires > clock.GetUtcNow().AddMinutes(2)) return t2.Token;
            var sa = JsonNode.Parse(serviceAccountJson)!;
            var email = sa["client_email"]!.GetValue<string>();
            using var rsa = RSA.Create();
            rsa.ImportFromPem(sa["private_key"]!.GetValue<string>());
            var now = clock.GetUtcNow().ToUnixTimeSeconds();
            static string B64(byte[] b) => Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_');
            var header = B64(Encoding.UTF8.GetBytes("""{"alg":"RS256","typ":"JWT"}"""));
            var claims = B64(JsonSerializer.SerializeToUtf8Bytes(new Dictionary<string, object>
            {
                ["iss"] = email, ["scope"] = "https://www.googleapis.com/auth/playintegrity",
                ["aud"] = "https://oauth2.googleapis.com/token", ["iat"] = now, ["exp"] = now + 3600,
            }));
            var sig = B64(rsa.SignData(Encoding.ASCII.GetBytes($"{header}.{claims}"), HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1));
            using var resp = await http.CreateClient("attestation").PostAsync("https://oauth2.googleapis.com/token", new FormUrlEncodedContent(
                new Dictionary<string, string> { ["grant_type"] = "urn:ietf:params:oauth:grant-type:jwt-bearer", ["assertion"] = $"{header}.{claims}.{sig}" }), ct);
            resp.EnsureSuccessStatusCode();
            var body = (await resp.Content.ReadFromJsonAsync<JsonNode>(ct))!;
            var token = body["access_token"]!.GetValue<string>();
            _googleToken = (token, clock.GetUtcNow().AddSeconds(body["expires_in"]?.GetValue<int>() ?? 3600));
            return token;
        }
        finally { GoogleGate.Release(); }
    }

    // ---- iOS: App Attest ------------------------------------------------------------------------------

    public byte[] NewChallenge(Guid userId)
    {
        var c = RandomNumberGenerator.GetBytes(32);
        Challenges[userId.ToString("N")] = (c, clock.GetUtcNow().AddMinutes(5));
        return c;
    }

    /// <summary>POST /me/attest: verifies the attestation object and stores the key (public key + counter only).</summary>
    public async Task AttestIosKeyAsync(Guid userId, string keyId, string attestationBase64, CancellationToken ct)
    {
        var appId = config["Attestation:Ios:AppId"] ?? throw new InvalidOperationException("Attestation:Ios:AppId (TEAMID.bundle.id) is not configured.");
        if (!Challenges.TryRemove(userId.ToString("N"), out var ch) || ch.Expires < clock.GetUtcNow())
            throw new CryptographicException("No valid challenge; call GET /me/attest/challenge first.");
        if (!Signatures.TryBase64(attestationBase64, out _)) throw new FormatException("attestation must be base64.");
        var obj = Convert.FromBase64String(Pad(attestationBase64));
        var key = AppAttest.VerifyAttestation(obj, keyId, ch.Challenge, appId, clock.GetUtcNow(), config.GetValue("Attestation:Ios:AllowDevelopment", true));
        var existing = await db.AppAttestKeys.FindAsync([key.KeyId], ct);
        if (existing is null) db.AppAttestKeys.Add(new AppAttestKey { KeyId = key.KeyId, UserId = userId, PublicKey = key.PublicKeySpki });
        else if (existing.UserId != userId) throw new CryptographicException("Key already attested for another account.");
        await db.SaveChangesAsync(ct);
    }

    /// <summary>Vote token for iOS = base64(keyId) + "." + base64(assertion CBOR).</summary>
    private async Task<bool?> IosAssertionAsync(Guid userId, string token, byte[] clientData, CancellationToken ct)
    {
        var appId = config["Attestation:Ios:AppId"];
        if (string.IsNullOrEmpty(appId)) { logger.LogInformation("App Attest not configured (Attestation:Ios:AppId); verdict n/a"); return null; }
        var parts = token.Split('.', 2);
        if (parts.Length != 2) return false;
        var key = await db.AppAttestKeys.FirstOrDefaultAsync(k => k.KeyId == Pad(parts[0]) || k.KeyId == parts[0], ct);
        if (key is null || key.UserId != userId) return false;
        key.Counter = AppAttest.VerifyAssertion(Convert.FromBase64String(Pad(parts[1])), clientData, key.PublicKey, key.Counter, appId);
        await db.SaveChangesAsync(ct);
        return true;
    }

    private static string Pad(string s)
    {
        var t = s.Replace('-', '+').Replace('_', '/');
        return t + new string('=', (4 - t.Length % 4) % 4);
    }
}
