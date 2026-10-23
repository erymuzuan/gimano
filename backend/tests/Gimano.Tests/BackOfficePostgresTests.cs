using System.Security.Cryptography;
using Gimano.Core.BackOffice;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Gimano.Core.Support;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Npgsql;

namespace Gimano.Tests;

/// <summary>TriageService against a real PostGIS (GIMANO_PG_TEST, see backend/tests/postgres-it.ps1). Skipped otherwise.</summary>
public sealed class BackOfficePostgresTests : IAsyncLifetime
{
    private NpgsqlDataSource? _ds;
    private string? _dbName;
    private string? _admin;
    private static readonly DateTimeOffset T0 = new(2026, 10, 1, 0, 0, 0, TimeSpan.Zero);
    private readonly Clock _clock = new(T0);
    private readonly ContactCrypto _crypto = new(Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)));

    private sealed class Clock(DateTimeOffset now) : TimeProvider
    {
        public DateTimeOffset Now { get; set; } = now;
        public override DateTimeOffset GetUtcNow() => Now;
    }

    public async ValueTask InitializeAsync()
    {
        _admin = Environment.GetEnvironmentVariable("GIMANO_PG_TEST");
        if (string.IsNullOrEmpty(_admin)) return;
        _dbName = "gimano_it_" + Guid.NewGuid().ToString("N")[..12];
        await using (var c = new NpgsqlConnection(_admin))
        {
            await c.OpenAsync();
            await using var cmd = new NpgsqlCommand($"CREATE DATABASE {_dbName}", c);
            await cmd.ExecuteNonQueryAsync();
        }
        _ds = GimanoDb.BuildDataSource(new NpgsqlConnectionStringBuilder(_admin) { Database = _dbName }.ConnectionString);
        await using var db = NewDb();
        await db.Database.MigrateAsync();
    }

    public async ValueTask DisposeAsync()
    {
        if (_ds is null) return;
        await _ds.DisposeAsync();
        NpgsqlConnection.ClearAllPools();
        await using var c = new NpgsqlConnection(_admin);
        await c.OpenAsync();
        await using var cmd = new NpgsqlCommand($"DROP DATABASE IF EXISTS {_dbName} WITH (FORCE)", c);
        await cmd.ExecuteNonQueryAsync();
    }

    private GimanoDbContext NewDb()
    {
        var o = new DbContextOptionsBuilder<GimanoDbContext>();
        GimanoDb.Configure(o, _ds!);
        return new GimanoDbContext(o.Options);
    }

    private TriageService Triage(GimanoDbContext db) => new(db, _clock, _crypto);
    private static readonly Actor Me = Actor.Maintainer("me@example.com");

    private async Task<(string Signature, Guid ReportId)> SeedCrashWithContactAsync()
    {
        await using var db = NewDb();
        var sig = new string('c', 64);
        db.CrashGroups.Add(new CrashGroup { Signature = sig, Exception = "java.lang.IllegalStateException", FirstSeen = T0, LastSeen = T0, Count = 1 });
        var r = new CrashReport
        {
            Signature = sig, Exception = "java.lang.IllegalStateException", Kind = CrashKind.Crash, CreatedAt = T0,
            Device = new DeviceInfo("0.3.0", 12, "android", "16", 36, "SM-S921B", "samsung", null, "ms", null, null),
        };
        db.CrashReports.Add(r);
        db.ReportContacts.Add(_crypto.Encrypt(ContactSubject.Crash, r.Id, new ContactDetails("ali@example.com", null, true), T0));
        await db.SaveChangesAsync();
        return (sig, r.Id);
    }

    [Fact]
    public async Task Crash_contacts_expire_30_days_after_the_group_closes_and_reopening_restores_90_days()
    {
        if (_ds is null) { Assert.Skip("GIMANO_PG_TEST not set"); return; }
        var (sig, reportId) = await SeedCrashWithContactAsync();

        _clock.Now = T0.AddDays(1);
        await using (var db = NewDb()) await Triage(db).TriageCrashAsync(sig, new CrashTriage(CrashGroupStatus.WontFix), Me);
        _clock.Now = T0.AddDays(5);
        await using (var db = NewDb()) await Triage(db).TriageCrashAsync(sig, new CrashTriage(CrashGroupStatus.Triaged), Me);   // reopened
        await using (var db = NewDb())
        {
            await ReportRetention.RunAsync(db, T0.AddDays(40), CancellationToken.None);
            Assert.True(await db.ReportContacts.AnyAsync(c => c.SubjectId == reportId), "reopened: kept until created + 90 d");
            await Triage(db).TriageCrashAsync(sig, new CrashTriage(CrashGroupStatus.Done, FixedInBuild: 13, FixedInVersion: "0.3.1"), Me);
            var view = await Triage(db).GetCrashGroupAsync(sig);
            Assert.Equal(T0.AddDays(5), view!.ClosedAt);
        }
        await using (var db = NewDb())
        {
            await ReportRetention.RunAsync(db, T0.AddDays(34), CancellationToken.None);
            Assert.True(await db.ReportContacts.AnyAsync(c => c.SubjectId == reportId));
            await ReportRetention.RunAsync(db, T0.AddDays(35), CancellationToken.None);
            Assert.False(await db.ReportContacts.AnyAsync(c => c.SubjectId == reportId), "closed + 30 d");
        }
    }

    [Fact]
    public async Task Invites_work_once_for_their_email_and_failed_registrations_can_retry()
    {
        if (_ds is null) { Assert.Skip("GIMANO_PG_TEST not set"); return; }
        // Built like Program.cs does: IdentityDbContext shapes its model from IdentityOptions (Stores.SchemaVersion = Version3 adds
        // AspNetUserPasskeys). A bare `new ApplicationDbContext(options)` gets the default schema version, so its model differs
        // from the migrations and MigrateAsync throws PendingModelChangesWarning.
        var cs = new NpgsqlConnectionStringBuilder(_admin) { Database = _dbName }.ConnectionString;
        await using var sp = new Microsoft.Extensions.DependencyInjection.ServiceCollection()
            .AddLogging()
            .AddDbContext<Gimano.BackOffice.Data.ApplicationDbContext>(o => Gimano.BackOffice.Data.ApplicationDbContext.Configure(o, cs))
            .AddIdentityCore<Gimano.BackOffice.Data.ApplicationUser>(op => op.Stores.SchemaVersion = Microsoft.AspNetCore.Identity.IdentitySchemaVersions.Version3)
            .AddEntityFrameworkStores<Gimano.BackOffice.Data.ApplicationDbContext>().Services
            .BuildServiceProvider();
        await using var scope = sp.CreateAsyncScope();
        var idb = scope.ServiceProvider.GetRequiredService<Gimano.BackOffice.Data.ApplicationDbContext>();
        await idb.Database.MigrateAsync();
        var allow = new Gimano.BackOffice.Data.MaintainerAllowlist(new StaticOptions(new() { Maintainers = ["me@example.com"] }));
        var invites = new Gimano.BackOffice.Data.MaintainerInvites(idb, allow, _clock);
        await Assert.ThrowsAsync<InvalidOperationException>(() => invites.CreateAsync("attacker@example.com"));
        var token = await invites.CreateAsync("me@example.com");
        Assert.False(await invites.TryConsumeAsync(token, "attacker@example.com"));
        Assert.True(await invites.TryConsumeAsync(token, "ME@example.com"));
        Assert.False(await invites.TryConsumeAsync(token, "me@example.com"));          // single use
        await invites.ReleaseAsync(token);                                               // account creation failed
        _clock.Now = T0.AddHours(25);
        Assert.False(await invites.TryConsumeAsync(token, "me@example.com"));          // expired
        Assert.DoesNotContain(token, string.Join(",", idb.MaintainerInvites.Select(i => i.TokenHash)));
    }

    private sealed class StaticOptions(Gimano.BackOffice.Data.BackOfficeOptions value) : Microsoft.Extensions.Options.IOptionsMonitor<Gimano.BackOffice.Data.BackOfficeOptions>
    {
        public Gimano.BackOffice.Data.BackOfficeOptions CurrentValue => value;
        public Gimano.BackOffice.Data.BackOfficeOptions Get(string? name) => value;
        public IDisposable? OnChange(Action<Gimano.BackOffice.Data.BackOfficeOptions, string?> listener) => null;
    }

    [Fact]
    public async Task Triage_errors_are_domain_exceptions_and_reveal_is_audited_without_values()
    {
        if (_ds is null) { Assert.Skip("GIMANO_PG_TEST not set"); return; }
        var (sig, reportId) = await SeedCrashWithContactAsync();
        await using var db = NewDb();
        var t = Triage(db);
        await Assert.ThrowsAsync<NotFoundException>(() => t.TriageFeedbackAsync(Guid.NewGuid(), new FeedbackTriage(Note: "x"), Me));
        await Assert.ThrowsAsync<ValidationException>(() => t.TriageCrashAsync(sig, new CrashTriage(CrashGroupStatus.Done), Me));   // needs fixedInBuild
        await Assert.ThrowsAsync<ForbiddenException>(() => t.RevealContactAsync("crash", reportId, "reproduce it", Actor.Agent("claude", AgentScopes.All)));
        var contact = await t.RevealContactAsync("crash", reportId, "need a screen recording", Me);
        Assert.Equal("ali@example.com", contact.Email);
        var audit = await db.AuditLog.SingleAsync(a => a.Action == "contact.reveal");
        Assert.DoesNotContain("ali@", audit.Detail);
    }

    [Fact]
    public async Task Feedback_search_filters_by_platform_and_approved_replies_reach_the_inbox()
    {
        if (_ds is null) { Assert.Skip("GIMANO_PG_TEST not set"); return; }
        Guid feedbackId;
        await using (var db = NewDb())
        {
            var u = new User { DisplayName = "Pemandu", Locale = "en" };
            db.Users.Add(u);
            var f = new Feedback { UserId = u.Id, Message = "Voice stops", Category = FeedbackCategory.Bug, CreatedAt = T0,
                Device = new DeviceInfo("0.3.0", 12, "ios", "26", null, "iPhone17,3", "Apple", null, "en", null, null) };
            db.Feedback.Add(f);
            db.Feedback.Add(new Feedback { Message = "Android one", CreatedAt = T0,
                Device = new DeviceInfo("0.3.0", 12, "android", "16", 36, "SM-S921B", "samsung", null, "ms", null, null) });
            await db.SaveChangesAsync();
            feedbackId = f.Id;
        }
        await using (var db = NewDb())
        {
            var t = Triage(db);
            var ios = await t.SearchFeedbackAsync(new FeedbackQuery(Platform: "ios", Text: "voice"));
            Assert.Equal(feedbackId, Assert.Single(ios).Id);
            Assert.Equal("ios", ios[0].Platform);
            var draft = await t.DraftReplyAsync(feedbackId, "Fixed in 0.3.1, thanks!", FeedbackStatus.Done, Actor.Agent("claude", [AgentScopes.RepliesDraft]));
            await t.ApproveReplyAsync(draft.Id, null, Me);
            await Assert.ThrowsAsync<ConflictException>(() => t.ApproveReplyAsync(draft.Id, null, Me));
        }
        await using (var db = NewDb())
        {
            var item = await db.InboxItems.SingleAsync();
            Assert.Equal(("Your feedback: fixed", "Fixed in 0.3.1, thanks!", InboxKind.FeedbackStatus), (item.Title, item.Body, item.Kind));
            Assert.NotNull((await db.Feedback.SingleAsync(f => f.Id == feedbackId)).ClosedAt);
        }
    }
}
