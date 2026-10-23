using System.Text;
using System.Text.Json.Nodes;
using Gimano.Api.Identity;
using Gimano.Core.Contracts;
using Gimano.Core.Geo;
using Gimano.Core.Identity;
using Microsoft.AspNetCore.Http;

namespace Gimano.Tests;

/// <summary>The fixed vectors published in docs/identity-and-reputation.md ("Test vectors"). Both apps test against these.</summary>
public class SignatureVectorTests
{
    private static readonly byte[] Seed = Convert.FromHexString("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    private const string PublicKeyB64 = "A6EHv/POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg=";
    private const string VerifyBody = """{"answer":"yes","nameCorrect":true,"locationCorrect":true,"proximity":{"cell":"w283f8","withinM":420}}""";

    [Fact]
    public void Seed_derives_the_published_public_key() =>
        Assert.Equal(PublicKeyB64, Convert.ToBase64String(Signatures.PublicKeyFromSeed(Seed)));

    [Theory]
    [InlineData("POST", "/api/v1/places/c01a166b7f9c67924976aff1beb56c2cd/verify", 1792713600L, VerifyBody,
        "6c091ee7b6a05d273cedfd0ada1a4a1c113a2ef99a1ed14c35f8d87f36019821",
        "CPm5nQ7WU1z/tiBHSciXop7u2uc/M6Rt4/AgExSarydCkuW1iUQ99XT+gFyonROf7816WI+u2h901BueYupVAQ==")]
    [InlineData("DELETE", "/api/v1/reviews/01a16676-ce9e-78db-842e-55ffa03ef190", 1792713600L, "",
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "62H79zlWTyZDvgEzOFxiHLBA29cFklC0eto7hkBYoWdsgHaoXp54+ygVAvS7hyS/38WD2XYwOK+XQr6RE4B+Cw==")]
    [InlineData("POST", "/api/v1/reports?source=app", 1792713601L, """{"type":"hazard","subtype":"pothole","lat":3.118,"lng":101.619,"heading":90}""",
        "e7880fafa70c2dcc331adee63b54a5c4362d39a02d0ec08341d7ac06b9713980",
        "CVlQVQXZSUEbSZDJ3pEkhmeAKHgPbZh2MyAkEwcTXo9C5d+drAlcNC2X7zdrBBXfSAJN9jq3VQo+ukoTiRLJDw==")]
    public void Request_signature_vectors(string method, string path, long ts, string body, string bodySha, string sig)
    {
        var canonical = Signatures.RequestCanonical(method, path, ts, Encoding.UTF8.GetBytes(body));
        Assert.Equal($"GIMANO1\n{method}\n{path}\n{ts}\n{bodySha}", canonical);
        Assert.Equal(sig, Convert.ToBase64String(Signatures.Sign(Seed, canonical).Signature));   // Ed25519 is deterministic
        Assert.True(Signatures.Verify(Convert.FromBase64String(PublicKeyB64), canonical, Convert.FromBase64String(sig)));
    }

    [Fact]
    public void Auth_and_rotation_vectors()
    {
        var uid = Guid.Parse("01a16676-0000-7000-8000-000000000001");
        var auth = Signatures.AuthCanonical(uid, 1792713600);
        Assert.Equal("GIMANO-AUTH\n01a16676-0000-7000-8000-000000000001\n1792713600", auth);
        Assert.Equal("aApOheYmDSVKSLICJI8tuRW9Cp06hzkHJQKwdLab1N5sYCPAwvKFum8s+fnw6+6QsktcXji/+OLZzEQqijW+Cw==",
            Convert.ToBase64String(Signatures.Sign(Seed, auth).Signature));

        var newPk = Convert.ToBase64String(Signatures.PublicKeyFromSeed(Convert.FromHexString("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f")));
        Assert.Equal("Kay64UG8yvCyLhqU000LxzYeUm0L/hLIl5S8kyKWbdc=", newPk);
        var rot = Signatures.KeyRotationCanonical(uid, newPk);
        Assert.Equal("y+8fzyom8LvtpxVQGE3Hebps7dovTSlGQYQhrgqO8hAekGT4pX0PXw282mqNZc8vMCOtLmmwfDbgVqlBKYsBDg==",
            Convert.ToBase64String(Signatures.Sign(Seed, rot).Signature));
    }

    [Fact]
    public void Key_login_vector()
    {
        var canonical = Signatures.KeyLoginCanonical(PublicKeyB64, 1792713600);
        Assert.Equal("GIMANO1-LOGIN\nA6EHv/POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg=\n1792713600", canonical);
        var sig = Signatures.Sign(Seed, canonical).Signature;
        Assert.Equal("61uR1jjsZqzZb7I0Oq3t0ZgVkyxeyutovBXfKWH9GvedLaFb2TzIXtKuotj9CgnRf6njJJDUBOwf6yy3ZkfFDQ==", Convert.ToBase64String(sig));
        Assert.True(Signatures.Verify(Convert.FromBase64String(PublicKeyB64), canonical, sig));
        // A key-login signature is not valid as an auth (userId) signature and vice versa.
        Assert.False(Signatures.Verify(Convert.FromBase64String(PublicKeyB64), Signatures.AuthCanonical(Guid.Empty, 1792713600), sig));
    }

    [Fact]
    public void Tampering_with_any_part_fails_verification()
    {
        var pk = Convert.FromBase64String(PublicKeyB64);
        var good = Signatures.RequestCanonical("POST", "/api/v1/reports", 100, "{}"u8);
        var sig = Signatures.Sign(Seed, good).Signature;
        Assert.True(Signatures.Verify(pk, good, sig));
        Assert.False(Signatures.Verify(pk, Signatures.RequestCanonical("PUT", "/api/v1/reports", 100, "{}"u8), sig));
        Assert.False(Signatures.Verify(pk, Signatures.RequestCanonical("POST", "/api/v1/reports?x=1", 100, "{}"u8), sig));
        Assert.False(Signatures.Verify(pk, Signatures.RequestCanonical("POST", "/api/v1/reports", 101, "{}"u8), sig));
        Assert.False(Signatures.Verify(pk, Signatures.RequestCanonical("POST", "/api/v1/reports", 100, "{ }"u8), sig));
        Assert.False(Signatures.Verify(Signatures.PublicKeyFromSeed(new byte[32]), good, sig));
        Assert.False(Signatures.Verify(pk, good, new byte[64]));
        Assert.False(Signatures.Verify(new byte[5], good, sig));
    }

    [Theory]
    [InlineData(0, true)]
    [InlineData(300, true)]
    [InlineData(-300, true)]
    [InlineData(301, false)]
    [InlineData(-301, false)]
    public void Timestamp_window_is_300_seconds(int skew, bool ok)
    {
        var now = DateTimeOffset.FromUnixTimeSeconds(1792713600);
        Assert.Equal(ok, Signatures.InWindow(1792713600 + skew, now));
    }

    [Fact]
    public void Path_and_query_is_the_raw_request_target()
    {
        var ctx = new DefaultHttpContext();
        ctx.Request.Path = "/api/v1/places/c1/verify";
        ctx.Request.QueryString = new QueryString("?a=1&b=%20");
        Assert.Equal("/api/v1/places/c1/verify?a=1&b=%20", SignatureMiddleware.PathAndQuery(ctx));
    }

    [Fact]
    public void Attestation_client_data_vector() =>
        Assert.Equal("ff664bac6820f2ce7bb5823ae9a2b33b103e47bca5bb6aa4ec11af46a9aa78be",
            Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(AppAttest.VoteClientData("c01a166b7f9c67924976aff1beb56c2cd", 1792713600))));
}

public class GeohashTests
{
    [Theory]
    [InlineData(3.1180, 101.6190, "w2838r")]
    [InlineData(3.1579, 101.7116, "w283fw")]
    [InlineData(1.3521, 103.8198, "w21zdq")]
    [InlineData(4.9031, 114.9398, "w8cezs")]
    public void Encodes_geohash6(double lat, double lng, string expected) => Assert.Equal(expected, Geohash.Encode(lat, lng));

    [Fact]
    public void Bounds_contain_the_point_and_neighbours_surround_it()
    {
        var b = Geohash.Bounds("w2838r");
        Assert.True(b.Contains(3.1180, 101.6190));
        var n = Geohash.WithNeighbours("w2838r");
        Assert.Equal(9, n.Count);
        Assert.Contains("w2838r", n);
        // A point just across each edge lands in one of the neighbours.
        var dLat = b.MaxLat - b.MinLat; var dLng = b.MaxLng - b.MinLng;
        foreach (var (y, x) in new[] { (1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (-1, -1) })
            Assert.Contains(Geohash.Encode(3.1180 + y * dLat, 101.6190 + x * dLng), n);
        Assert.DoesNotContain(Geohash.Encode(3.1180 + 3 * dLat, 101.6190), n);
    }

    [Theory]
    [InlineData("w2838r", true)]
    [InlineData("w2838a", false)]   // 'a' isn't in the geohash alphabet
    [InlineData("", false)]
    public void Validates(string h, bool ok) => Assert.Equal(ok, Geohash.IsValid(h));
}

public class ReputationTests
{
    private static readonly DateTimeOffset Now = new(2026, 10, 23, 0, 0, 0, TimeSpan.Zero);
    private static ReputationCounters C(int acc = 0, int rej = 0, int votes = 0, int agreed = 0, int ageDays = 365, int disputes = 0, int rej7 = 0) =>
        new(acc, rej, votes, agreed, Now.AddDays(-ageDays), disputes, rej7);

    [Fact]
    public void Accuracy_is_null_under_five_items()
    {
        Assert.Null(Reputation.Accuracy(C(acc: 2, votes: 2, agreed: 2)));
        Assert.Equal(0.8, Reputation.Accuracy(C(acc: 3, rej: 1, votes: 6, agreed: 5)));   // (3+5)/(3+1+6)
    }

    [Theory]
    [InlineData(9, 0, 0, 0, 365, 0, Tier.Baharu)]     // not enough accepted
    [InlineData(10, 0, 0, 0, 365, 0, Tier.Perak)]     // 100 %
    [InlineData(10, 2, 0, 0, 365, 0, Tier.Baharu)]    // 10/12 = 83 %
    [InlineData(50, 1, 10, 10, 365, 0, Tier.Emas)]    // 60/61 = 98 %
    [InlineData(50, 1, 10, 10, 60, 0, Tier.Perak)]    // account < 90 days
    [InlineData(50, 1, 10, 10, 365, 1, Tier.Perak)]   // upheld dispute in 90 days
    [InlineData(50, 4, 0, 0, 365, 0, Tier.Perak)]     // 50/54 = 92.6 % < 95
    public void Tier_rules(int acc, int rej, int votes, int agreed, int age, int disputes, Tier expected) =>
        Assert.Equal(expected, Reputation.ComputeTier(C(acc, rej, votes, agreed, age, disputes), Now));

    [Fact]
    public void Two_upheld_disputes_demote_emas_to_perak()
    {
        var emas = C(60, 0, 20, 20);
        Assert.Equal(Tier.Emas, Reputation.ComputeTier(emas, Now));
        Assert.Equal(Tier.Perak, Reputation.ComputeTier(emas with { UpheldDisputes90d = 2 }, Now));
    }

    [Fact]
    public void Next_tier_lists_what_is_missing()
    {
        var n = Reputation.Next(C(acc: 4, ageDays: 3), Now)!;
        Assert.Equal(Tier.Perak, n.Name);
        Assert.Contains("placesAccepted:6", n.Missing);
        Assert.Contains("accuracy:90", n.Missing);
        var p = Reputation.Next(C(acc: 12, ageDays: 30), Now)!;
        Assert.Equal(Tier.Emas, p.Name);
        Assert.Contains("placesAccepted:38", p.Missing);
        Assert.Contains("accountDays:60", p.Missing);
        Assert.Null(Reputation.Next(C(60, 0, 20, 20), Now));
    }

    [Theory]
    [InlineData(Tier.Baharu, 30, VoteAnswer.Yes, 1.0)]
    [InlineData(Tier.Perak, 30, VoteAnswer.No, 1.5)]
    [InlineData(Tier.Emas, 30, VoteAnswer.Yes, 2.0)]
    [InlineData(Tier.Emas, 3, VoteAnswer.Yes, 0.0)]     // account < 7 days
    [InlineData(Tier.Perak, 30, VoteAnswer.Unsure, 0.0)]
    public void Vote_weights(Tier tier, int ageDays, VoteAnswer a, double w) =>
        Assert.Equal(w, Reputation.VoteWeight(tier, Now.AddDays(-ageDays), Now, a));

    [Fact]
    public void Required_confirmations_and_daily_limits_by_tier()
    {
        Assert.Equal((3.0, 2.0, 0.0), (Reputation.RequiredConfirmations(Tier.Baharu), Reputation.RequiredConfirmations(Tier.Perak), Reputation.RequiredConfirmations(Tier.Emas)));
        Assert.Equal(5, Reputation.PublicPlacesPerDay(Tier.Baharu));
    }

    [Theory]
    [InlineData(2.0, 0.0, 3.0, Reputation.Outcome.Pending)]
    [InlineData(3.0, 0.0, 3.0, Reputation.Outcome.Published)]
    [InlineData(2.0, 0.0, 2.0, Reputation.Outcome.Published)]   // perak contributor
    [InlineData(1.5, 1.5, 2.0, Reputation.Outcome.Pending)]
    [InlineData(5.0, 3.0, 3.0, Reputation.Outcome.Hidden)]      // weighted no ≥ 3 wins
    [InlineData(0.0, 0.0, 0.0, Reputation.Outcome.Pending)]     // emas: published at creation, not via votes
    public void Resolution_thresholds(double yes, double no, double required, Reputation.Outcome expected) =>
        Assert.Equal(expected, Reputation.Resolve(yes, no, required));

    [Fact]
    public void Agreement_and_suspension()
    {
        Assert.True(Reputation.Agreed(VoteAnswer.Yes, Reputation.Outcome.Published));
        Assert.True(Reputation.Agreed(VoteAnswer.No, Reputation.Outcome.Hidden));
        Assert.False(Reputation.Agreed(VoteAnswer.Yes, Reputation.Outcome.Hidden));
        Assert.Null(Reputation.Agreed(VoteAnswer.Unsure, Reputation.Outcome.Published));
        Assert.False(Reputation.ShouldSuspend(2));
        Assert.True(Reputation.ShouldSuspend(3));
    }

    [Fact]
    public void Unlinking_hash_depends_on_the_per_place_salt()
    {
        var voter = Guid.NewGuid();
        var saltA = new byte[32]; saltA[0] = 1;
        var saltB = new byte[32]; saltB[0] = 2;
        Assert.Equal(Reputation.VoterHash(saltA, voter), Reputation.VoterHash(saltA, voter));
        Assert.NotEqual(Reputation.VoterHash(saltA, voter), Reputation.VoterHash(saltB, voter));   // can't join across places
        Assert.DoesNotContain(voter.ToString("N"), Convert.ToHexStringLower(Reputation.VoterHash(saltA, voter)));
        Assert.Equal(32, Reputation.VoterHash(saltA, voter).Length);
    }

    [Theory]
    [InlineData(800, false, true)]
    [InlineData(1200, false, false)]
    [InlineData(4500, true, true)]
    [InlineData(0, false, false)]
    public void Proximity_radius(int withinM, bool rural, bool ok) => Assert.Equal(ok, Reputation.WithinProximity(withinM, rural));

    [Fact]
    public void About_ten_percent_of_emas_places_are_audited()
    {
        var rnd = new Random(1);
        var n = Enumerable.Range(0, 10_000).Count(_ => Reputation.PickForAudit(rnd.NextDouble));
        Assert.InRange(n, 900, 1100);
    }
}

public class AttestationTests
{
    [Fact]
    public void Play_integrity_needs_device_integrity_and_package_but_ignores_unrecognized_app()
    {
        const string hash = "ff664bac6820f2ce7bb5823ae9a2b33b103e47bca5bb6aa4ec11af46a9aa78be";
        JsonNode P(string device, string pkg = "my.gimano.app", string? reqHash = hash)
        {
            var details = new JsonObject { ["requestPackageName"] = pkg };
            if (reqHash is not null) details["requestHash"] = reqHash;
            return new JsonObject
            {
                ["requestDetails"] = details,
                ["appIntegrity"] = new JsonObject { ["appRecognitionVerdict"] = "UNRECOGNIZED" },
                ["deviceIntegrity"] = new JsonObject { ["deviceRecognitionVerdict"] = JsonNode.Parse("[" + device + "]") },
            };
        }
        Assert.True(AttestationService.EvaluatePlayIntegrity(P("\"MEETS_DEVICE_INTEGRITY\""), "my.gimano.app", hash));
        Assert.True(AttestationService.EvaluatePlayIntegrity(P("\"MEETS_DEVICE_INTEGRITY\"", reqHash: null), "my.gimano.app", hash));
        Assert.False(AttestationService.EvaluatePlayIntegrity(P(""), "my.gimano.app", hash));
        Assert.False(AttestationService.EvaluatePlayIntegrity(P("\"MEETS_BASIC_INTEGRITY\""), "my.gimano.app", hash));
        Assert.False(AttestationService.EvaluatePlayIntegrity(P("\"MEETS_DEVICE_INTEGRITY\"", pkg: "evil.app"), "my.gimano.app", hash));
        Assert.False(AttestationService.EvaluatePlayIntegrity(P("\"MEETS_DEVICE_INTEGRITY\"", reqHash: "00"), "my.gimano.app", hash));
        Assert.False(AttestationService.EvaluatePlayIntegrity(null, "my.gimano.app", hash));
    }

    [Fact]
    public void App_attest_rejects_garbage_and_non_apple_formats()
    {
        Assert.ThrowsAny<Exception>(() => AppAttest.VerifyAttestation("nope"u8.ToArray(), "AA==", new byte[32], "TEAM.my.gimano", DateTimeOffset.UtcNow, true));
        var w = new System.Formats.Cbor.CborWriter();
        w.WriteStartMap(3); w.WriteTextString("fmt"); w.WriteTextString("packed"); w.WriteTextString("authData"); w.WriteByteString(new byte[60]);
        w.WriteTextString("attStmt"); w.WriteStartMap(0); w.WriteEndMap(); w.WriteEndMap();
        Assert.Throws<System.Security.Cryptography.CryptographicException>(() =>
            AppAttest.VerifyAttestation(w.Encode(), "AA==", new byte[32], "TEAM.my.gimano", DateTimeOffset.UtcNow, true));
    }

    [Fact]
    public void App_attest_assertion_signature_and_counter_are_checked()
    {
        using var ec = System.Security.Cryptography.ECDsa.Create(System.Security.Cryptography.ECCurve.NamedCurves.nistP256);
        var appId = "TEAMID1234.my.gimano.app";
        byte[] AuthData(uint counter) => [.. System.Security.Cryptography.SHA256.HashData(Encoding.UTF8.GetBytes(appId)), 0x00,
            (byte)(counter >> 24), (byte)(counter >> 16), (byte)(counter >> 8), (byte)counter];
        byte[] Assertion(uint counter, byte[] clientData)
        {
            var ad = AuthData(counter);
            var nonce = System.Security.Cryptography.SHA256.HashData([.. ad, .. System.Security.Cryptography.SHA256.HashData(clientData)]);
            var sig = ec.SignData(nonce, System.Security.Cryptography.HashAlgorithmName.SHA256, System.Security.Cryptography.DSASignatureFormat.Rfc3279DerSequence);
            var w = new System.Formats.Cbor.CborWriter();
            w.WriteStartMap(2); w.WriteTextString("signature"); w.WriteByteString(sig); w.WriteTextString("authenticatorData"); w.WriteByteString(ad); w.WriteEndMap();
            return w.Encode();
        }
        var spki = ec.ExportSubjectPublicKeyInfo();
        var cd = AppAttest.VoteClientData("c1", 1792713600);
        Assert.Equal(5, AppAttest.VerifyAssertion(Assertion(5, cd), cd, spki, 4, appId));
        Assert.Throws<System.Security.Cryptography.CryptographicException>(() => AppAttest.VerifyAssertion(Assertion(5, cd), cd, spki, 5, appId));          // replay
        Assert.Throws<System.Security.Cryptography.CryptographicException>(() => AppAttest.VerifyAssertion(Assertion(6, cd), AppAttest.VoteClientData("c2", 1), spki, 5, appId)); // other vote
        Assert.Throws<System.Security.Cryptography.CryptographicException>(() => AppAttest.VerifyAssertion(Assertion(7, cd), cd, spki, 5, "OTHER.app"));   // other app
    }
}

public class SignaturePolicyTests
{
    [Theory]
    // keyed accounts: always verified (unsigned → rejected by verification), whatever the flags
    [InlineData(true, false, true, SignatureDecision.Verify)]
    [InlineData(true, false, false, SignatureDecision.Verify)]
    [InlineData(true, true, true, SignatureDecision.Verify)]
    // legacy key-less: accepted unsigned while AllowLegacyUnsigned and no kill switch
    [InlineData(false, false, true, SignatureDecision.AllowUnsigned)]
    [InlineData(false, false, false, SignatureDecision.RejectNoKey)]
    // kill switch: reject every unsigned request, legacy included
    [InlineData(false, true, true, SignatureDecision.RejectNoKey)]
    public void Per_account_enforcement(bool hasKey, bool requireAll, bool allowLegacy, SignatureDecision expected) =>
        Assert.Equal(expected, SignaturePolicy.Decide(hasKey, requireAll, allowLegacy));

    [Fact]
    public void Legacy_accounts_are_capped_at_baharu_and_weigh_at_most_one()
    {
        var now = new DateTimeOffset(2026, 10, 23, 0, 0, 0, TimeSpan.Zero);
        Assert.Equal(Tier.Baharu, Reputation.CapForLegacy(Tier.Emas, hasKey: false));
        Assert.Equal(Tier.Emas, Reputation.CapForLegacy(Tier.Emas, hasKey: true));
        Assert.Equal(1.0, Reputation.VoteWeight(Tier.Emas, now.AddDays(-100), now, VoteAnswer.Yes, hasKey: false));
        Assert.Equal(2.0, Reputation.VoteWeight(Tier.Emas, now.AddDays(-100), now, VoteAnswer.Yes, hasKey: true));
        var perakCounters = new ReputationCounters(12, 0, 0, 0, now.AddDays(-200), 0, 0);
        var next = Reputation.Next(perakCounters, now, hasKey: false)!;
        Assert.Equal(Tier.Perak, next.Name);
        Assert.Contains("signingKey", next.Missing);
        Assert.DoesNotContain("signingKey", Reputation.Next(new ReputationCounters(0, 0, 0, 0, now, 0, 0), now)!.Missing);
    }

    [Fact]
    public void Legacy_inbox_notice_text()
    {
        Assert.Equal("Kemas kini Manotu untuk melindungi akaun anda", Gimano.Core.Community.InboxRules.LegacyUpdateText("ms-MY").Title);
        Assert.Equal("Update Manotu to protect your account", Gimano.Core.Community.InboxRules.LegacyUpdateText("en").Title);
    }
}
