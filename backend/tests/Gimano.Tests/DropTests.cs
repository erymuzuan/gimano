using System.Text;
using Gimano.Api;
using Gimano.Api.Endpoints;
using Gimano.Core.Events;
using Microsoft.AspNetCore.Cors.Infrastructure;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.FileProviders;
using Microsoft.Extensions.Hosting;
using R = Gimano.Core.Events.DropService.Result;

namespace Gimano.Tests;

/// <summary>In-memory stand-in for the Postgres store (same semantics as <see cref="EfDropStore"/>).</summary>
internal sealed class FakeDropStore : IDropStore
{
    public readonly Dictionary<string, Drop> Rows = new(StringComparer.Ordinal);
    /// <summary>Ids to report as taken (simulates a collision).</summary>
    public readonly Queue<bool> Collisions = new();

    public Task<bool> TryInsertAsync(Drop drop, CancellationToken ct)
    {
        if ((Collisions.Count > 0 && Collisions.Dequeue()) || Rows.ContainsKey(drop.Id)) return Task.FromResult(false);
        Rows[drop.Id] = drop;
        return Task.FromResult(true);
    }

    public Task<Drop?> FindAsync(string id, bool withBlob, CancellationToken ct) =>
        Task.FromResult(Rows.TryGetValue(id, out var d)
            ? new Drop { Id = d.Id, Blob = withBlob ? d.Blob : [], WriteTokenHash = d.WriteTokenHash, OwnerHash = d.OwnerHash,
                         CreatedAt = d.CreatedAt, UpdatedAt = d.UpdatedAt, ExpiresAt = d.ExpiresAt, DeletedAt = d.DeletedAt }
            : null);

    public Task<int> CountLiveAsync(byte[] ownerHash, DateTimeOffset now, CancellationToken ct) =>
        Task.FromResult(Rows.Values.Count(d => d.OwnerHash.AsSpan().SequenceEqual(ownerHash) && d.IsLive(now)));

    public Task<bool> UpdateAsync(string id, byte[] blob, DateTimeOffset expiresAt, DateTimeOffset now, CancellationToken ct)
    {
        if (!Rows.TryGetValue(id, out var d) || !d.IsLive(now)) return Task.FromResult(false);
        (d.Blob, d.ExpiresAt, d.UpdatedAt) = (blob, expiresAt, now);
        return Task.FromResult(true);
    }

    public Task MarkDeletedAsync(string id, DateTimeOffset now, CancellationToken ct)
    {
        if (Rows.TryGetValue(id, out var d) && d.DeletedAt is null) (d.DeletedAt, d.Blob) = (now, []);
        return Task.CompletedTask;
    }

    public Task<int> DeleteByOwnerAsync(byte[] ownerHash, CancellationToken ct)
    {
        var ids = Rows.Values.Where(d => d.OwnerHash.AsSpan().SequenceEqual(ownerHash)).Select(d => d.Id).ToList();
        ids.ForEach(i => Rows.Remove(i));
        return Task.FromResult(ids.Count);
    }

    public Task<int> PurgeAsync(DateTimeOffset now, CancellationToken ct)
    {
        var ids = Rows.Values.Where(d => d.DeletedAt != null || d.ExpiresAt <= now).Select(d => d.Id).ToList();
        ids.ForEach(i => Rows.Remove(i));
        return Task.FromResult(ids.Count);
    }
}

public class DropTests
{
    private sealed class Clock : TimeProvider
    {
        public DateTimeOffset Now = new(2026, 10, 24, 8, 0, 0, TimeSpan.Zero);
        public override DateTimeOffset GetUtcNow() => Now;
    }

    private static readonly DropOwnerKey Owners = new(Enumerable.Range(1, 32).Select(i => (byte)i).ToArray());
    private static byte[] Blob(int n = 200, byte fill = 7) => Enumerable.Repeat(fill, n).ToArray();
    private static string Iso(DateTimeOffset t) => t.ToString("O");

    private static (DropService Svc, FakeDropStore Store, Clock Clock) New()
    {
        var clock = new Clock();
        var store = new FakeDropStore();
        return (new DropService(store, Owners, clock), store, clock);
    }

    [Fact]
    public async Task Lifecycle_create_get_put_delete_then_410_then_404_after_purge()
    {
        var (svc, store, clock) = New();
        var expires = clock.Now.AddDays(30);
        var (r, c) = await svc.CreateAsync(Guid.NewGuid(), Blob(), Iso(expires));
        Assert.Equal(R.Ok, r);
        Assert.Equal(expires, c!.ExpiresAt);

        var (g, blob) = await svc.GetAsync(c.DropId);
        Assert.Equal(R.Ok, g);
        Assert.Equal(Blob(), blob);

        clock.Now = clock.Now.AddHours(1);
        var (p, newExpiry) = await svc.PutAsync(c.DropId, c.WriteToken, Blob(300, 9), null);
        Assert.Equal(R.Ok, p);
        Assert.Equal(expires, newExpiry);                              // no header: expiry unchanged
        Assert.Equal(Blob(300, 9), (await svc.GetAsync(c.DropId)).Item2);
        Assert.Equal(clock.Now, store.Rows[c.DropId].UpdatedAt);

        Assert.Equal(R.Ok, await svc.DeleteAsync(c.DropId, c.WriteToken));
        Assert.Empty(store.Rows[c.DropId].Blob);                       // blob gone at once, tombstone left
        Assert.Equal(R.Gone, (await svc.GetAsync(c.DropId)).Item1);
        Assert.Equal(R.Gone, (await svc.PutAsync(c.DropId, c.WriteToken, Blob(), null)).Item1);
        Assert.Equal(R.Gone, await svc.DeleteAsync(c.DropId, c.WriteToken));

        Assert.Equal(1, await store.PurgeAsync(clock.Now, default));
        Assert.Equal(R.NotFound, (await svc.GetAsync(c.DropId)).Item1);
    }

    [Fact]
    public async Task Expired_drops_answer_410_until_purged_then_404()
    {
        var (svc, store, clock) = New();
        var (_, c) = await svc.CreateAsync(Guid.NewGuid(), Blob(), Iso(clock.Now.AddDays(2)));
        clock.Now = clock.Now.AddDays(2);
        Assert.Equal(R.Gone, (await svc.GetAsync(c!.DropId)).Item1);
        Assert.Equal(R.Gone, (await svc.PutAsync(c.DropId, c.WriteToken, Blob(), null)).Item1);
        await store.PurgeAsync(clock.Now, default);
        Assert.Equal(R.NotFound, (await svc.GetAsync(c.DropId)).Item1);
    }

    [Theory]
    [InlineData("nope")]
    [InlineData("0000000000")]            // '0' is not base58
    [InlineData("7Kx2Qp9mWd")]            // well-formed, unknown
    [InlineData("7Kx2Qp9mWdX")]           // 11 chars
    public async Task Unknown_or_malformed_ids_are_404(string id)
    {
        var (svc, _, _) = New();
        Assert.Equal(R.NotFound, (await svc.GetAsync(id)).Item1);
        Assert.Equal(R.NotFound, await svc.DeleteAsync(id, "x"));
    }

    [Fact]
    public async Task Ids_are_10_base58_chars_and_tokens_256_bit_b64u_stored_only_as_sha256()
    {
        var (svc, store, clock) = New();
        var ids = new HashSet<string>();
        for (var i = 0; i < 20; i++)
        {
            var (_, c) = await svc.CreateAsync(Guid.NewGuid(), Blob(), Iso(clock.Now.AddDays(5)));
            Assert.True(DropService.IsValidId(c!.DropId));
            Assert.All(c.DropId, ch => Assert.Contains(ch, DropService.Base58Alphabet));
            Assert.Equal(43, c.WriteToken.Length);
            Assert.DoesNotContain('=', c.WriteToken);
            Assert.DoesNotContain('+', c.WriteToken);
            Assert.DoesNotContain('/', c.WriteToken);
            var row = store.Rows[c.DropId];
            Assert.Equal(System.Security.Cryptography.SHA256.HashData(Encoding.ASCII.GetBytes(c.WriteToken)), row.WriteTokenHash);
            Assert.DoesNotContain(c.WriteToken, Encoding.ASCII.GetString(row.WriteTokenHash));
            ids.Add(c.DropId);
        }
        Assert.Equal(20, ids.Count);
        Assert.Equal(58, DropService.Base58Alphabet.Length);
    }

    [Fact]
    public async Task Id_collisions_are_retried()
    {
        var (svc, store, clock) = New();
        store.Collisions.Enqueue(true);
        store.Collisions.Enqueue(true);
        var (r, c) = await svc.CreateAsync(Guid.NewGuid(), Blob(), Iso(clock.Now.AddDays(5)));
        Assert.Equal(R.Ok, r);
        Assert.True(store.Rows.ContainsKey(c!.DropId));
        Assert.Empty(store.Collisions);
    }

    [Fact]
    public async Task Writes_need_the_write_token()
    {
        var (svc, store, clock) = New();
        var (_, c) = await svc.CreateAsync(Guid.NewGuid(), Blob(), Iso(clock.Now.AddDays(5)));
        Assert.Equal(R.Unauthorized, (await svc.PutAsync(c!.DropId, null, Blob(), null)).Item1);
        Assert.Equal(R.Unauthorized, (await svc.PutAsync(c.DropId, "", Blob(), null)).Item1);
        Assert.Equal(R.Unauthorized, (await svc.PutAsync(c.DropId, c.WriteToken + "x", Blob(), null)).Item1);
        Assert.Equal(R.Unauthorized, await svc.DeleteAsync(c.DropId, "wrong"));
        Assert.Equal(R.Unauthorized, await svc.DeleteAsync(c.DropId, new string('a', 500)));
        Assert.Null(store.Rows[c.DropId].DeletedAt);
        Assert.Equal(Blob(), store.Rows[c.DropId].Blob);
    }

    [Theory]
    [InlineData(DropService.MinBlobBytes - 1, R.Invalid)]
    [InlineData(DropService.MinBlobBytes, R.Ok)]
    [InlineData(DropService.MaxBlobBytes, R.Ok)]
    [InlineData(DropService.MaxBlobBytes + 1, R.TooLarge)]
    public async Task Blob_size_limits(int size, R expected)
    {
        var (svc, _, clock) = New();
        Assert.Equal(expected, (await svc.CreateAsync(Guid.NewGuid(), new byte[size], Iso(clock.Now.AddDays(5)))).Item1);
        var (_, c) = await svc.CreateAsync(Guid.NewGuid(), Blob(), Iso(clock.Now.AddDays(5)));
        Assert.Equal(expected, (await svc.PutAsync(c!.DropId, c.WriteToken, new byte[size], null)).Item1);
    }

    [Theory]
    [InlineData(-5.0, 1.0)]          // in the past → now + 1 day
    [InlineData(0.5, 1.0)]           // 12 h → now + 1 day
    [InlineData(1.0, 1.0)]
    [InlineData(30.0, 30.0)]
    [InlineData(400.0, 400.0)]
    [InlineData(401.0, 400.0)]       // → now + 400 days
    [InlineData(5000.0, 400.0)]
    public async Task Expiry_is_clamped_to_1_to_400_days(double requestedDays, double expectedDays)
    {
        var (svc, _, clock) = New();
        var (r, c) = await svc.CreateAsync(Guid.NewGuid(), Blob(), Iso(clock.Now.AddDays(requestedDays)));
        Assert.Equal(R.Ok, r);
        Assert.Equal(clock.Now.AddDays(expectedDays), c!.ExpiresAt);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("tomorrow")]
    [InlineData("2026-13-40T00:00:00Z")]
    public async Task Create_needs_a_valid_expiry_header(string? header)
    {
        var (svc, store, _) = New();
        Assert.Equal(R.InvalidExpiry, (await svc.CreateAsync(Guid.NewGuid(), Blob(), header)).Item1);
        Assert.Empty(store.Rows);
    }

    [Fact]
    public void Expiry_parses_offsets_and_dates_as_utc()
    {
        var now = new DateTimeOffset(2026, 10, 24, 8, 0, 0, TimeSpan.Zero);
        Assert.Equal(new DateTimeOffset(2026, 11, 30, 16, 0, 0, TimeSpan.Zero), DropService.ParseExpiry("2026-12-01T00:00:00+08:00", now));
        Assert.Equal(new DateTimeOffset(2026, 12, 1, 0, 0, 0, TimeSpan.Zero), DropService.ParseExpiry("2026-12-01T00:00:00", now));
        Assert.Equal(new DateTimeOffset(2026, 12, 1, 0, 0, 0, TimeSpan.Zero), DropService.ParseExpiry("2026-12-01", now));
    }

    [Fact]
    public async Task Put_can_move_the_expiry_within_the_same_bounds()
    {
        var (svc, store, clock) = New();
        var (_, c) = await svc.CreateAsync(Guid.NewGuid(), Blob(), Iso(clock.Now.AddDays(10)));
        clock.Now = clock.Now.AddDays(3);
        var (r, e) = await svc.PutAsync(c!.DropId, c.WriteToken, Blob(), Iso(clock.Now.AddDays(1000)));
        Assert.Equal(R.Ok, r);
        Assert.Equal(clock.Now.AddDays(400), e);
        Assert.Equal(clock.Now.AddDays(400), store.Rows[c.DropId].ExpiresAt);
        Assert.Equal(R.InvalidExpiry, (await svc.PutAsync(c.DropId, c.WriteToken, Blob(), "garbage")).Item1);
    }

    [Fact]
    public async Task At_most_50_live_drops_per_user()
    {
        var (svc, _, clock) = New();
        var u = Guid.NewGuid();
        var made = new List<DropService.Created>();
        for (var i = 0; i < DropService.MaxLivePerUser; i++)
            made.Add((await svc.CreateAsync(u, Blob(), Iso(clock.Now.AddDays(2 + i % 3)))).Item2!);
        Assert.Equal(R.TooMany, (await svc.CreateAsync(u, Blob(), Iso(clock.Now.AddDays(5)))).Item1);
        Assert.Equal(R.Ok, (await svc.CreateAsync(Guid.NewGuid(), Blob(), Iso(clock.Now.AddDays(5)))).Item1);   // others unaffected

        await svc.DeleteAsync(made[0].DropId, made[0].WriteToken);                                          // ending one frees a slot
        Assert.Equal(R.Ok, (await svc.CreateAsync(u, Blob(), Iso(clock.Now.AddDays(5)))).Item1);
        Assert.Equal(R.TooMany, (await svc.CreateAsync(u, Blob(), Iso(clock.Now.AddDays(5)))).Item1);

        clock.Now = clock.Now.AddDays(2);                                                                   // expired ones don't count
        Assert.Equal(R.Ok, (await svc.CreateAsync(u, Blob(), Iso(clock.Now.AddDays(5)))).Item1);
    }

    [Fact]
    public async Task Delete_me_removes_the_users_drops_only()
    {
        var (svc, store, clock) = New();
        var (me, other) = (Guid.NewGuid(), Guid.NewGuid());
        var mine = (await svc.CreateAsync(me, Blob(), Iso(clock.Now.AddDays(5)))).Item2!;
        var mineEnded = (await svc.CreateAsync(me, Blob(), Iso(clock.Now.AddDays(5)))).Item2!;
        await svc.DeleteAsync(mineEnded.DropId, mineEnded.WriteToken);
        var theirs = (await svc.CreateAsync(other, Blob(), Iso(clock.Now.AddDays(5)))).Item2!;

        Assert.Equal(2, await svc.DeleteAllForUserAsync(me));
        Assert.Equal(R.NotFound, (await svc.GetAsync(mine.DropId)).Item1);
        Assert.Equal(R.NotFound, (await svc.GetAsync(mineEnded.DropId)).Item1);
        Assert.Equal(R.Ok, (await svc.GetAsync(theirs.DropId)).Item1);
    }

    [Fact]
    public void Owner_hash_is_keyed_stable_and_not_the_user_id()
    {
        var u = Guid.NewGuid();
        var h = Owners.For(u);
        Assert.Equal(32, h.Length);
        Assert.Equal(h, Owners.For(u));
        Assert.NotEqual(h, Owners.For(Guid.NewGuid()));
        Assert.NotEqual(h, new DropOwnerKey(new byte[32]).For(u));                            // a different key → unlinkable
        Assert.DoesNotContain(u.ToString("N"), Convert.ToHexString(h), StringComparison.OrdinalIgnoreCase);
        Assert.Throws<ArgumentException>(() => new DropOwnerKey(new byte[16]));
        // No dedicated key → derived from the JWT key (like Community:AuthorHandleKey), and a dedicated key wins.
        var jwt = new string('k', 48);
        Assert.Equal(DropOwnerKey.FromConfig(null, jwt).For(u), DropOwnerKey.FromConfig("", jwt).For(u));
        Assert.NotEqual(DropOwnerKey.FromConfig(null, jwt).For(u), DropOwnerKey.FromConfig(Convert.ToBase64String(new byte[32]), jwt).For(u));
    }

    [Fact]
    public void Drop_rows_hold_no_title_ip_user_agent_or_user_id()
    {
        var props = typeof(Drop).GetProperties().Select(p => p.Name).ToHashSet();
        Assert.Equal(new HashSet<string> { "Id", "Blob", "WriteTokenHash", "OwnerHash", "CreatedAt", "UpdatedAt", "ExpiresAt", "DeletedAt" }, props);
    }

    // ---- HTTP surface ----

    [Theory]
    [InlineData("Drop abc", "abc")]
    [InlineData("Drop  abc ", "abc")]
    [InlineData("Share abc", null)]
    [InlineData("Bearer abc", null)]
    [InlineData("", null)]
    public void Reads_drop_authorization_scheme(string header, string? token)
    {
        var ctx = new DefaultHttpContext();
        if (header.Length > 0) ctx.Request.Headers.Authorization = header;
        Assert.Equal(token, DropEndpoints.Token(ctx));
    }

    [Theory]
    [InlineData(R.NotFound, 404)]
    [InlineData(R.Gone, 410)]
    [InlineData(R.Unauthorized, 401)]
    [InlineData(R.TooMany, 429)]
    [InlineData(R.TooLarge, 413)]
    [InlineData(R.Invalid, 400)]
    [InlineData(R.InvalidExpiry, 400)]
    public void Results_map_to_status_codes(R r, int status) =>
        Assert.Equal(status, Assert.IsAssignableFrom<IStatusCodeHttpResult>(DropEndpoints.Map(r)).StatusCode);

    private static HttpRequest Request(byte[] body, string? contentType = "application/octet-stream", bool sendLength = true)
    {
        var ctx = new DefaultHttpContext();
        ctx.Request.Body = new MemoryStream(body);
        if (sendLength) ctx.Request.ContentLength = body.Length;
        ctx.Request.ContentType = contentType;
        return ctx.Request;
    }

    [Fact]
    public async Task Body_reader_enforces_256_KiB_and_octet_stream()
    {
        var (err, ok) = await DropEndpoints.ReadBlobAsync(Request(Blob(DropService.MaxBlobBytes)), default);
        Assert.Null(err);
        Assert.Equal(DropService.MaxBlobBytes, ok!.Length);

        var (tooBig, _) = await DropEndpoints.ReadBlobAsync(Request(new byte[DropService.MaxBlobBytes + 1]), default);
        Assert.Equal(413, Assert.IsAssignableFrom<IStatusCodeHttpResult>(tooBig).StatusCode);
        // Chunked (no Content-Length): still cut at the limit.
        var (tooBigChunked, _) = await DropEndpoints.ReadBlobAsync(Request(new byte[DropService.MaxBlobBytes + 10], sendLength: false), default);
        Assert.Equal(413, Assert.IsAssignableFrom<IStatusCodeHttpResult>(tooBigChunked).StatusCode);

        var (json, _) = await DropEndpoints.ReadBlobAsync(Request(Blob(), "application/json"), default);
        Assert.Equal(415, Assert.IsAssignableFrom<IStatusCodeHttpResult>(json).StatusCode);
        var (noType, b) = await DropEndpoints.ReadBlobAsync(Request(Blob(), null), default);
        Assert.Null(noType);
        Assert.Equal(Blob(), b);
    }

    private static async Task<HttpContext> RunGet(DropService svc, string id)
    {
        var ctx = new DefaultHttpContext { RequestServices = new ServiceCollection().AddLogging().BuildServiceProvider() };
        ctx.Response.Body = new MemoryStream();
        var result = await DropEndpoints.Get(id, ctx, svc, default);
        await result.ExecuteAsync(ctx);
        return ctx;
    }

    [Fact]
    public async Task Get_returns_the_raw_blob_with_no_store()
    {
        var (svc, _, clock) = New();
        var (_, c) = await svc.CreateAsync(Guid.NewGuid(), Blob(64, 3), Iso(clock.Now.AddDays(5)));
        var ok = await RunGet(svc, c!.DropId);
        Assert.Equal(200, ok.Response.StatusCode);
        Assert.Equal("application/octet-stream", ok.Response.ContentType);
        Assert.Equal("no-store", ok.Response.Headers.CacheControl.ToString());
        Assert.Equal(Blob(64, 3), ((MemoryStream)ok.Response.Body).ToArray());

        var missing = await RunGet(svc, "7Kx2Qp9mWd");
        Assert.Equal(404, missing.Response.StatusCode);
        Assert.Equal("no-store", missing.Response.Headers.CacheControl.ToString());

        await svc.DeleteAsync(c.DropId, c.WriteToken);
        Assert.Equal(410, (await RunGet(svc, c.DropId)).Response.StatusCode);
    }

    [Fact]
    public void Drop_ids_never_reach_logs()
    {
        Assert.Equal("/api/v1/drops/{dropId}", DropEndpoints.LogSafePath("/api/v1/drops/7Kx2Qp9mWd"));
        Assert.Equal("/api/v1/drops", DropEndpoints.LogSafePath("/api/v1/drops"));
        Assert.Equal("/api/v1/shares/abc", DropEndpoints.LogSafePath("/api/v1/shares/abc"));
    }

    private sealed class Env : IHostEnvironment
    {
        public string EnvironmentName { get; set; } = Environments.Production;
        public string ApplicationName { get; set; } = "Gimano.Api";
        public string ContentRootPath { get; set; } = "/";
        public IFileProvider ContentRootFileProvider { get; set; } = new NullFileProvider();
    }

    [Theory]
    [InlineData("https://manotu.app", true)]
    [InlineData("https://www.bukitbunga.com", true)]
    [InlineData("https://evil.example", false)]
    [InlineData("http://manotu.app", false)]
    public async Task Get_has_CORS_for_manotu_app_and_bukitbunga_only(string origin, bool allowed)
    {
        var sp = new ServiceCollection().AddLogging().AddGimanoEdge(new ConfigurationBuilder().Build(), new Env()).BuildServiceProvider();
        var policy = await sp.GetRequiredService<ICorsPolicyProvider>().GetPolicyAsync(new DefaultHttpContext(), EdgeSetup.DropsCorsPolicy);
        Assert.NotNull(policy);
        var ctx = new DefaultHttpContext();
        ctx.Request.Method = "GET";
        ctx.Request.Headers.Origin = origin;
        var result = sp.GetRequiredService<ICorsService>().EvaluatePolicy(ctx, policy!);
        Assert.Equal(allowed, result.IsOriginAllowed);
        Assert.Contains("GET", policy!.Methods);
        Assert.DoesNotContain("PUT", policy.Methods);
    }
}
