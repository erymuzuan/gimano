using System.Text.Json;
using Gimano.Api.Endpoints;
using Gimano.Core.Contracts;
using Gimano.Core.Domain;
using Gimano.Core.Support;

namespace Gimano.Tests;

public class ContactTests
{
    private static readonly string Key = Convert.ToBase64String(Enumerable.Range(1, 32).Select(i => (byte)i).ToArray());

    [Theory]
    [InlineData("ali@example.com", null, true, null)]
    [InlineData(null, "+60123456789", true, null)]
    [InlineData("ali@example.com", "+60123456789", true, null)]
    [InlineData("ali@example.com", null, false, "consent")]
    [InlineData("not-an-email", null, true, "email")]
    [InlineData(null, "0123456789", true, "E.164")]
    [InlineData(null, "+6012-345 6789", true, "E.164")]
    [InlineData(null, null, true, "needs")]
    public void Validates_contact(string? email, string? wa, bool consent, string? error)
    {
        var r = ContactCrypto.Validate(new ContactDetails(email, wa, consent));
        if (error is null) Assert.Null(r); else Assert.Contains(error, r);
    }

    [Fact]
    public void Encrypts_with_aes_gcm_bound_to_the_report()
    {
        var crypto = new ContactCrypto(Key);
        var id = Guid.NewGuid();
        var c = crypto.Encrypt(ContactSubject.Feedback, id, new ContactDetails("ali@example.com", "+60123456789", true), DateTimeOffset.UtcNow);
        var stored = System.Text.Encoding.UTF8.GetString(c.Ciphertext);
        Assert.DoesNotContain("ali@example.com", stored);
        Assert.DoesNotContain("60123456789", stored);
        Assert.Equal(12, c.Nonce.Length);
        var back = crypto.Decrypt(c);
        Assert.Equal(("ali@example.com", "+60123456789"), (back.Email, back.Whatsapp));

        // Bound to its report: moving the ciphertext to another report id fails authentication.
        var moved = new ReportContact { SubjectType = ContactSubject.Feedback, SubjectId = Guid.NewGuid(), Nonce = c.Nonce, Ciphertext = c.Ciphertext };
        Assert.ThrowsAny<System.Security.Cryptography.CryptographicException>(() => crypto.Decrypt(moved));
        // Wrong key fails too.
        var other = new ContactCrypto(Convert.ToBase64String(new byte[32]));
        Assert.ThrowsAny<System.Security.Cryptography.CryptographicException>(() => other.Decrypt(c));
    }

    [Fact]
    public void Disabled_without_a_key_and_rejects_short_keys()
    {
        Assert.False(new ContactCrypto(null).Enabled);
        Assert.Throws<InvalidOperationException>(() => new ContactCrypto(Convert.ToBase64String(new byte[16])));
    }

    [Theory]
    [InlineData(0, null, false)]
    [InlineData(89, null, false)]
    [InlineData(90, null, true)]            // 90 days max
    [InlineData(10, 29, false)]             // closed 29 days ago
    [InlineData(40, 30, true)]              // closed 30 days ago
    public void Contact_retention(int ageDays, int? closedDaysAgo, bool expired)
    {
        var now = new DateTimeOffset(2026, 10, 23, 0, 0, 0, TimeSpan.Zero);
        Assert.Equal(expired, ReportRetention.ContactExpired(now.AddDays(-ageDays), closedDaysAgo is { } c ? now.AddDays(-c) : null, now));
    }

    [Fact]
    public void Feedback_request_accepts_new_and_legacy_shapes()
    {
        const string modern = """
            {"category":"bug","message":"Peta tak keluar","rating":3,"screen":"navigation",
             "device":{"appVersion":"0.2.0","appBuild":2,"platform":"android","osVersion":"16","apiLevel":36,"deviceModel":"SM-S921B",
                       "manufacturer":"samsung","screen":{"widthPx":1080,"heightPx":2340,"density":2.625,"widthDp":411,"heightDp":891},
                       "locale":"ms","theme":"light","dataMode":"live"},
             "location":{"lat":3.12,"lng":101.62},
             "contact":{"email":"ali@example.com","whatsapp":"+60123456789","consent":true},
             "routeId":"r1","placeId":"n1"}
            """;
        var m = JsonSerializer.Deserialize<CreateFeedbackRequest>(modern, GimanoJson.Options)!;
        Assert.Equal(36, m.Device!.ApiLevel);
        Assert.Equal(411, m.Device.Screen!.WidthDp);
        Assert.True(m.Contact!.Consent);
        Assert.Null(FeedbackEndpoints.Validate(m));

        var noConsent = m with { Contact = m.Contact with { Consent = false } };
        Assert.NotNull(FeedbackEndpoints.Validate(noConsent));

        const string legacy = """{"category":"map_data","message":"x","context":{"appVersion":"0.1.0 (1)","platform":"android","lat":3.118765,"lng":101.619432}}""";
        var l = JsonSerializer.Deserialize<CreateFeedbackRequest>(legacy, GimanoJson.Options)!;
        Assert.Null(FeedbackEndpoints.Validate(l));
        Assert.Equal(3.12, FeedbackEndpoints.Normalize(l).Location!.Lat);
    }
}

public class CrashTests
{
    private const string AndroidStack = """
        java.lang.IllegalStateException: Route was null for user ali@example.com at 3.118765, 101.619432
            at my.gimano.app.nav.NavigationController.start(NavigationController.kt:142)
            at my.gimano.app.nav.NavigationController.resume(NavigationController.kt:88)
            at my.gimano.app.ui.MainViewModel.onRoute(MainViewModel.kt:301)
            at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:108)
            at my.gimano.app.ui.MainActivity.onCreate(MainActivity.kt:55)
            at my.gimano.app.GimanoApp.onCreate(GimanoApp.kt:20)
            at my.gimano.app.Other.frame(Other.kt:1)
            at android.app.ActivityThread.main(ActivityThread.java:8177)
        """;

    [Fact]
    public void Signature_uses_exception_and_top_five_app_frames_without_line_numbers()
    {
        var s1 = CrashScrubber.Signature("java.lang.IllegalStateException", AndroidStack);
        Assert.Equal(64, s1.Length);
        // Same crash after a code change that moved line numbers → same group.
        var moved = AndroidStack.Replace("NavigationController.kt:142", "NavigationController.kt:150");
        Assert.Equal(s1, CrashScrubber.Signature("java.lang.IllegalStateException", moved));
        // A 6th+ app frame or framework frames don't change the group.
        Assert.Equal(s1, CrashScrubber.Signature("java.lang.IllegalStateException", AndroidStack.Replace("Other.frame", "Other.elsewhere")));
        Assert.Equal(s1, CrashScrubber.Signature("java.lang.IllegalStateException", AndroidStack.Replace("DispatchedTask.run", "DispatchedTask.go")));
        // Different exception or top frame → different group.
        Assert.NotEqual(s1, CrashScrubber.Signature("java.lang.NullPointerException", AndroidStack));
        Assert.NotEqual(s1, CrashScrubber.Signature("java.lang.IllegalStateException", AndroidStack.Replace("NavigationController.start", "NavigationController.stop")));
    }

    [Theory]
    [InlineData("user ali@example.com failed", "ali@example.com")]
    [InlineData("at 3.118765, 101.619432 on route", "3.118765")]
    [InlineData("call +60 12-345 6789 now", "345 6789")]
    [InlineData("Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijkl", "eyJhbGciOiJIUzI1NiJ9")]
    [InlineData("GET https://api.example.com/x?token=abc&lat=3.1 failed", "token=abc")]
    [InlineData("open /data/user/0/my.gimano.app/files/favs.db", "favs.db")]
    [InlineData("file:///var/mobile/Containers/Data/Application/1234-ABCD/Library/x.sqlite", "1234-ABCD")]
    public void Server_rescrubs_crash_text(string input, string mustNotRemain)
    {
        var s = CrashScrubber.Scrub(input, 1000)!;
        Assert.DoesNotContain(mustNotRemain, s);
        Assert.Contains("[redacted]", s);
    }

    [Fact]
    public void Scrub_keeps_ordinary_stack_lines()
    {
        const string line = "at my.gimano.app.nav.NavigationController.start(NavigationController.kt:142)";
        Assert.Equal(line, CrashScrubber.Scrub(line, 1000));
    }

    [Fact]
    public void Crash_validation()
    {
        Assert.NotNull(CrashEndpoints.Validate(new CrashReportRequest(null, CrashKind.Crash, "", null, null, null, null, null, null, null)));
        Assert.NotNull(CrashEndpoints.Validate(new CrashReportRequest(null, CrashKind.Crash, "E", null, new string('a', 33 * 1024), null, null, null, null, null)));
        Assert.NotNull(CrashEndpoints.Validate(new CrashReportRequest(null, CrashKind.Anr, "E", null, null, null, null, null, null, new ContactDetails("a@b.co", null, false))));
        Assert.Null(CrashEndpoints.Validate(new CrashReportRequest(DateTimeOffset.UtcNow, CrashKind.Native, "SIGSEGV", "m", "s", null, "home", 12, null, null)));
        Assert.Equal(CrashKind.Anr, JsonSerializer.Deserialize<CrashKind>("\"anr\"", GimanoJson.Options));
    }
}
