using Gimano.Api.Endpoints;
using Gimano.Api.Services;
using Gimano.Core.Community;
using Gimano.Core.Contracts;
using Gimano.Core.Domain;
using Gimano.Core.Support;
using Gimano.Worker.Feedback;
using Microsoft.Extensions.Caching.Memory;

namespace Gimano.Tests;

/// <summary>Store readiness (Apple 1.2, Play Data safety, privacy rule): blocking handles, contact unlinking, moderation.</summary>
public class AuthorHandleTests
{
    private static readonly byte[] Key = Enumerable.Range(1, 32).Select(i => (byte)i).ToArray();

    [Fact]
    public void Handles_are_stable_per_author_opaque_and_url_safe()
    {
        var h = new AuthorHandles(Key);
        var a = Guid.NewGuid();
        var b = Guid.NewGuid();
        Assert.Equal(h.For(a), h.For(a));
        Assert.NotEqual(h.For(a), h.For(b));
        Assert.Equal(16, h.For(a)!.Length);
        Assert.Matches("^[A-Za-z0-9_-]+$", h.For(a)!);
        Assert.DoesNotContain(a.ToString("N")[..8], h.For(a)!, StringComparison.OrdinalIgnoreCase);
        Assert.Null(h.For(null));   // deleted account: no handle
    }

    [Fact]
    public void The_handle_depends_on_the_server_secret()
    {
        var a = Guid.NewGuid();
        var other = Enumerable.Range(2, 32).Select(i => (byte)i).ToArray();
        Assert.NotEqual(new AuthorHandles(Key).For(a), new AuthorHandles(other).For(a));
        // Without a dedicated key it is derived from the JWT key (deterministic across restarts), not equal to an HMAC with it.
        var jwt = new string('k', 48);
        Assert.Equal(AuthorHandles.FromConfig(null, jwt).For(a), AuthorHandles.FromConfig("", jwt).For(a));
        Assert.NotEqual(AuthorHandles.FromConfig(null, jwt).For(a), new AuthorHandles(System.Text.Encoding.UTF8.GetBytes(jwt)).For(a));
        Assert.Equal(new AuthorHandles(Key).For(a), AuthorHandles.FromConfig(Convert.ToBase64String(Key), jwt).For(a));
    }

    [Fact]
    public void Review_and_photo_dtos_carry_the_handle()
    {
        var h = new AuthorHandles(Key);
        var user = new User { DisplayName = "Kancil 1234" };
        var photo = new Photo { PlaceId = "n1", UserId = user.Id, User = user, Key = "k", ThumbKey = "t", Status = PhotoStatus.Published };
        Assert.Equal(h.For(user.Id), PhotoService.ToDto(photo, h).UploadedBy.AuthorRef);
        Assert.Null(PhotoService.ToDto(new Photo { PlaceId = "n1" }, h).UploadedBy.AuthorRef);
    }
}

public class ContactUnlinkingTests
{
    private static readonly ContactCrypto Crypto = new(Convert.ToBase64String(Enumerable.Range(0, 32).Select(i => (byte)i).ToArray()));

    [Fact]
    public void A_report_with_a_stored_contact_is_flagged_for_unlinking()
    {
        Assert.True(FeedbackEndpoints.WillStoreContact(Crypto, new ContactDetails("ali@example.com", null, true)));
        Assert.True(FeedbackEndpoints.WillStoreContact(Crypto, new ContactDetails(null, "+60123456789", true)));
        Assert.False(FeedbackEndpoints.WillStoreContact(Crypto, null));
        Assert.False(FeedbackEndpoints.WillStoreContact(Crypto, new ContactDetails(" ", null, true)));
        // No key configured: the contact isn't stored, so the report stays linked (normal rate limits, My feedback).
        Assert.False(FeedbackEndpoints.WillStoreContact(new ContactCrypto(null), new ContactDetails("ali@example.com", null, true)));
    }

    [Fact]
    public void Withdraw_tokens_are_per_report_and_need_no_user_link()
    {
        var id = Guid.NewGuid();
        var t = Crypto.WithdrawToken(ContactSubject.Feedback, id);
        Assert.True(Crypto.CheckWithdrawToken(ContactSubject.Feedback, id, t));
        Assert.False(Crypto.CheckWithdrawToken(ContactSubject.Feedback, Guid.NewGuid(), t));
        Assert.False(Crypto.CheckWithdrawToken(ContactSubject.Crash, id, t));
        Assert.False(Crypto.CheckWithdrawToken(ContactSubject.Feedback, id, null));
        Assert.False(new ContactCrypto(null).CheckWithdrawToken(ContactSubject.Feedback, id, t));
    }

    [Fact]
    public void Unlinked_reports_still_count_toward_the_rate_limit_in_memory_only()
    {
        var cache = new MemoryCache(new MemoryCacheOptions());
        var uid = Guid.NewGuid();
        var now = DateTimeOffset.UtcNow;
        for (var i = 0; i < 3; i++) UnlinkedReports.Add(cache, "feedback", uid, now.AddMinutes(i), TimeSpan.FromHours(1));
        Assert.Equal(3, UnlinkedReports.Count(cache, "feedback", uid, now.AddMinutes(5), TimeSpan.FromHours(1)));
        Assert.Equal(0, UnlinkedReports.Count(cache, "crash", uid, now, TimeSpan.FromDays(1)));
        Assert.Equal(1, UnlinkedReports.Count(cache, "feedback", uid, now.AddMinutes(61.5), TimeSpan.FromHours(1)));
    }
}

public class ModerationRuleTests
{
    [Theory]
    [InlineData("Kancil 1234", true)]
    [InlineData("Pak Ali SS2", true)]
    [InlineData("fuck you", false)]
    [InlineData("visit www.spam.example", false)]
    public void Display_names_go_through_the_word_filter(string name, bool ok) =>
        Assert.Equal(ok, AuthAndMeEndpoints.DisplayNameAllowed(name));

    [Fact]
    public void Banned_accounts_cannot_contribute()
    {
        var u = new User { DisplayName = "x" };
        Assert.Same(u, Moderation.EnsureMayContribute(u));
        u.BannedAt = DateTimeOffset.UtcNow;
        Assert.Throws<ForbiddenException>(() => Moderation.EnsureMayContribute(u));
    }

    [Fact]
    public void The_public_github_sink_is_off_outside_development_by_default()
    {
        var o = new GitHubFeedbackOptions { GitHubRepo = "o/r", GitHubToken = "t" };
        Assert.True(o.EnabledIn("Development"));
        Assert.False(o.EnabledIn("Production"));
        o.AllowOutsideDevelopment = true;
        Assert.True(o.EnabledIn("Production"));
        Assert.False(new GitHubFeedbackOptions().EnabledIn("Development"));
    }

    [Fact]
    public void Feedback_is_kept_24_months() => Assert.Equal(730, ReportRetention.Feedback.TotalDays);
}
