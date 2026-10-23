using System.Formats.Cbor;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace Gimano.Core.Identity;

/// <summary>
/// Apple App Attest verification (attestation once per key, then assertions), following Apple's
/// "Validating apps that connect to your server". No device identifiers are kept: only the attested public key + counter.
/// </summary>
public static class AppAttest
{
    /// <summary>Apple App Attestation Root CA (SHA-256 1C:B9:82:3B:A2:8B:A6:AD:2D:33:A0:06:94:1D:E2:AE:4F:51:3E:F1:D4:E8:31:B9:F7:E0:FA:7B:62:42:C9:32).</summary>
    public const string AppleRootPem = """
-----BEGIN CERTIFICATE-----
MIICITCCAaegAwIBAgIQC/O+DvHN0uD7jG5yH2IXmDAKBggqhkjOPQQDAzBSMSYw
JAYDVQQDDB1BcHBsZSBBcHAgQXR0ZXN0YXRpb24gUm9vdCBDQTETMBEGA1UECgwK
QXBwbGUgSW5jLjETMBEGA1UECAwKQ2FsaWZvcm5pYTAeFw0yMDAzMTgxODMyNTNa
Fw00NTAzMTUwMDAwMDBaMFIxJjAkBgNVBAMMHUFwcGxlIEFwcCBBdHRlc3RhdGlv
biBSb290IENBMRMwEQYDVQQKDApBcHBsZSBJbmMuMRMwEQYDVQQIDApDYWxpZm9y
bmlhMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAERTHhmLW07ATaFQIEVwTtT4dyctdh
NbJhFs/Ii2FdCgAHGbpphY3+d8qjuDngIN3WVhQUBHAoMeQ/cLiP1sOUtgjqK9au
Yen1mMEvRq9Sk3Jm5X8U62H+xTD3FE9TgS41o0IwQDAPBgNVHRMBAf8EBTADAQH/
MB0GA1UdDgQWBBSskRBTM72+aEH/pwyp5frq5eWKoTAOBgNVHQ8BAf8EBAMCAQYw
CgYIKoZIzj0EAwMDaAAwZQIwQgFGnByvsiVbpTKwSga0kP0e8EeDS4+sQmTvb7vn
53O5+FRXgeLhpJ06ysC5PrOyAjEAp5U4xDgEgllF7En3VcE3iexZZtKeYnpqtijV
oyFraWVIyd/dganmrduC1bmTBGwD
-----END CERTIFICATE-----
""";

    private const string NonceOid = "1.2.840.113635.100.8.2";
    private static readonly byte[] AaguidProd = Encoding.ASCII.GetBytes("appattest").Concat(new byte[7]).ToArray();
    private static readonly byte[] AaguidDev = Encoding.ASCII.GetBytes("appattestdevelop");

    public sealed record AttestedKey(string KeyId, byte[] PublicKeySpki);

    /// <summary>The client data both apps hash for a vote: <c>"GIMANO-ATTEST\n" + placeId + "\n" + ts</c>.</summary>
    public static byte[] VoteClientData(string placeId, long ts) => Encoding.UTF8.GetBytes($"GIMANO-ATTEST\n{placeId}\n{ts}");

    /// <param name="appId">"TEAMID.bundle.id"</param>
    /// <param name="challenge">The server challenge the app hashed as clientDataHash.</param>
    public static AttestedKey VerifyAttestation(byte[] attestationObject, string keyIdBase64, byte[] challenge, string appId, DateTimeOffset now, bool allowDevelopment)
    {
        var r = new CborReader(attestationObject);
        string? fmt = null; byte[]? authData = null; List<byte[]> x5c = [];
        var n = r.ReadStartMap();
        for (var i = 0; i < n; i++)
        {
            switch (r.ReadTextString())
            {
                case "fmt": fmt = r.ReadTextString(); break;
                case "authData": authData = r.ReadByteString(); break;
                case "attStmt":
                    var m = r.ReadStartMap();
                    for (var j = 0; j < m; j++)
                    {
                        if (r.ReadTextString() == "x5c")
                        {
                            var len = r.ReadStartArray();
                            for (var k = 0; k < len; k++) x5c.Add(r.ReadByteString());
                            r.ReadEndArray();
                        }
                        else r.SkipValue();
                    }
                    r.ReadEndMap();
                    break;
                default: r.SkipValue(); break;
            }
        }
        if (fmt != "apple-appattest" || authData is null || x5c.Count < 2) throw new CryptographicException("Not an App Attest attestation.");

        using var cred = X509CertificateLoader.LoadCertificate(x5c[0]);
        using var inter = X509CertificateLoader.LoadCertificate(x5c[1]);
        using var root = X509Certificate2.CreateFromPem(AppleRootPem);
        using var chain = new X509Chain();
        chain.ChainPolicy.TrustMode = X509ChainTrustMode.CustomRootTrust;
        chain.ChainPolicy.CustomTrustStore.Add(root);
        chain.ChainPolicy.ExtraStore.Add(inter);
        chain.ChainPolicy.RevocationMode = X509RevocationMode.NoCheck;
        chain.ChainPolicy.VerificationTime = now.UtcDateTime;
        if (!chain.Build(cred)) throw new CryptographicException("App Attest certificate chain is not rooted in Apple's App Attestation CA.");

        // nonce = SHA256(authData || SHA256(challenge)) must equal the credential certificate's extension.
        var nonce = SHA256.HashData([.. authData, .. SHA256.HashData(challenge)]);
        var ext = cred.Extensions.FirstOrDefault(e => e.Oid?.Value == NonceOid) ?? throw new CryptographicException("Nonce extension missing.");
        if (!ext.RawData.AsSpan()[^32..].SequenceEqual(nonce)) throw new CryptographicException("Nonce mismatch.");

        // keyId = SHA256(uncompressed EC public point).
        using var ec = cred.GetECDsaPublicKey() ?? throw new CryptographicException("Not an EC key.");
        var p = ec.ExportParameters(false);
        var point = new byte[] { 0x04 }.Concat(p.Q.X!).Concat(p.Q.Y!).ToArray();
        if (!Signatures.TryBase64(keyIdBase64, out var keyId) || !SHA256.HashData(point).AsSpan().SequenceEqual(keyId))
            throw new CryptographicException("keyId doesn't match the attested key.");

        CheckAuthData(authData, appId, expectCounter: 0);
        var aaguid = authData.AsSpan(37, 16);
        if (!aaguid.SequenceEqual(AaguidProd) && !(allowDevelopment && aaguid.SequenceEqual(AaguidDev)))
            throw new CryptographicException("Unexpected App Attest environment (aaguid).");
        var credIdLen = (authData[53] << 8) | authData[54];
        if (!authData.AsSpan(55, credIdLen).SequenceEqual(keyId)) throw new CryptographicException("credentialId doesn't match keyId.");

        return new AttestedKey(Convert.ToBase64String(keyId), ec.ExportSubjectPublicKeyInfo());
    }

    /// <summary>Verifies an assertion; returns the new counter (must be greater than the stored one).</summary>
    public static long VerifyAssertion(byte[] assertion, byte[] clientData, byte[] publicKeySpki, long storedCounter, string appId)
    {
        var r = new CborReader(assertion);
        byte[]? sig = null, authData = null;
        var n = r.ReadStartMap();
        for (var i = 0; i < n; i++)
        {
            switch (r.ReadTextString())
            {
                case "signature": sig = r.ReadByteString(); break;
                case "authenticatorData": authData = r.ReadByteString(); break;
                default: r.SkipValue(); break;
            }
        }
        if (sig is null || authData is null || authData.Length < 37) throw new CryptographicException("Malformed assertion.");
        var nonce = SHA256.HashData([.. authData, .. SHA256.HashData(clientData)]);
        using var ec = ECDsa.Create();
        ec.ImportSubjectPublicKeyInfo(publicKeySpki, out _);
        if (!ec.VerifyData(nonce, sig, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence))
            throw new CryptographicException("Assertion signature invalid.");
        var counter = CheckAuthData(authData, appId, expectCounter: null);
        if (counter <= storedCounter) throw new CryptographicException("Assertion counter did not increase (replay).");
        return counter;
    }

    private static long CheckAuthData(byte[] authData, string appId, long? expectCounter)
    {
        if (!authData.AsSpan(0, 32).SequenceEqual(SHA256.HashData(Encoding.UTF8.GetBytes(appId))))
            throw new CryptographicException("rpIdHash doesn't match the app id.");
        long counter = (authData[33] << 24) | (authData[34] << 16) | (authData[35] << 8) | authData[36];
        if (expectCounter is { } c && counter != c) throw new CryptographicException("Unexpected counter in attestation.");
        return counter;
    }
}
