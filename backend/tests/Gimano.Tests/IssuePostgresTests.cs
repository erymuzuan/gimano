using Gimano.Api.Services;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Gimano.Core.Geo;
using Gimano.Core.Issues;
using Gimano.Core.Messaging;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Npgsql;

namespace Gimano.Tests;

/// <summary>
/// Issues against a real PostGIS (a throwaway database per run): GIMANO_PG_TEST = a connection string to a server where we
/// may CREATE DATABASE (see backend/tests/postgres-it.ps1). Skipped otherwise.
/// </summary>
public sealed class PostgresIntegrationIssueTests : IAsyncLifetime
{
    private NpgsqlDataSource? _ds;
    private string? _dbName;
    private string? _admin;

    private sealed class Bus : IEventPublisher
    {
        public readonly List<(string Key, object Payload)> Sent = [];
        public Task<bool> PublishAsync<T>(string routingKey, T payload, CancellationToken ct = default) { Sent.Add((routingKey, payload!)); return Task.FromResult(true); }
    }

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
        var cs = new NpgsqlConnectionStringBuilder(_admin) { Database = _dbName }.ConnectionString;
        _ds = GimanoDb.BuildDataSource(cs);
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

    private (IssueService Svc, GimanoDbContext Db, Bus Bus) Service(Clock clock)
    {
        var db = NewDb();
        var services = new ServiceCollection().AddScoped(_ => NewDb()).BuildServiceProvider();
        var directory = new IssueDirectory(services.GetRequiredService<IServiceScopeFactory>(), clock, NullLogger<IssueDirectory>.Instance);
        var bus = new Bus();
        return (new IssueService(db, bus, directory, clock, NullLogger<IssueService>.Instance), db, bus);
    }

    private static readonly DateTimeOffset T0 = new(2026, 10, 23, 2, 0, 0, TimeSpan.Zero);
    private const double Lat = 3.1196, Lng = 101.6214;

    private async Task<List<User>> SeedAsync(int users, string highway = "residential", Tier tier = Tier.Baharu, bool emasFirst = false)
    {
        await using var db = NewDb();
        var line = new[] { new GeoPoint(Lat - 0.001, Lng), new GeoPoint(Lat + 0.001, Lng) };
        db.RoadSegments.Add(new RoadSegment { OsmWayId = 4242, Seq = 0, Name = "Jalan SS 2/24", Highway = highway, Oneway = false, FreeFlowKmh = 30,
            LengthM = 222, FromNode = 1, ToNode = 2, Geom = GeoMath.ToLineString(line) });
        db.Places.Add(new Place { Id = "n77", Name = "Kedai Runcit Ali", Category = PlaceCategory.Convenience, Location = new GeoPoint(Lat, Lng + 0.0005).ToPoint(), OsmType = "node" });
        var list = Enumerable.Range(0, users).Select(k => new User
        {
            DisplayName = $"Pemandu {k}", JoinedAt = T0.AddDays(-30), Tier = emasFirst && k == 0 ? Tier.Emas : tier,
        }).ToList();
        db.Users.AddRange(list);
        await db.SaveChangesAsync();
        foreach (var u in list.Where(u => u.Tier == Tier.Emas)) db.UserKeys.Add(new UserKey { UserId = u.Id, PublicKey = new byte[32] });
        await db.SaveChangesAsync();
        return list;
    }

    private static ProximityClaim Near => new(Geohash.Encode(Lat, Lng, 6), 200);

    private static CreateIssueRequest Closed(string subtype = "accident") =>
        new(IssueKind.RoadClosedTemp, subtype, Lat + 0.00005, Lng + 0.00003, 0, null, null, null, IssueSource.Report, null);

    [Fact]
    public async Task Report_snaps_merges_confirms_and_votes_unlink_on_refute()
    {
        if (_ds is null) { Assert.Skip("GIMANO_PG_TEST not set"); return; }
        var users = await SeedAsync(4);
        var clock = new Clock(T0);
        var (svc, db, bus) = Service(clock);

        var a = await svc.CreateAsync(users[0].Id, Closed(), "ms", default);
        Assert.Equal(IssueState.Suspected, a.State);
        Assert.Equal(4242, a.Segment!.WayId);
        Assert.Equal("Jalan SS 2/24", a.Segment.Name);
        Assert.Equal(T0.AddHours(2), a.ExpiresAt);
        Assert.Contains("ditutup (kemalangan)", a.Summary);

        // A second driver reporting the same closure 20 m away is merged into it: two weighted votes → confirmed.
        clock.Now = T0.AddMinutes(10);
        var b = await svc.CreateAsync(users[1].Id, Closed() with { Lat = Lat - 0.0001 }, "ms", default);
        Assert.True(b.Merged);
        Assert.Equal(a.Id, b.Id);
        Assert.Equal(IssueState.Confirmed, b.State);
        Assert.Contains(bus.Sent, s => s.Key == RoutingKeys.IssueChanged && ((IssueChangedEvent)s.Payload).StateChanged && ((IssueChangedEvent)s.Payload).State == IssueState.Confirmed);
        await using (var check = NewDb())
            Assert.Equal(1, (await check.Users.FirstAsync(u => u.Id == users[0].Id)).PlacesAccepted);   // reporter accuracy

        // Two "dah dibuka" answers refute it; every vote is unlinked and the voters' accuracy is updated.
        await svc.VoteAsync(users[2].Id, a.Id, new IssueVoteRequest(IssueAnswer.Cleared, Near), default);
        await svc.VoteAsync(users[3].Id, a.Id, new IssueVoteRequest(IssueAnswer.Cleared, Near), default);
        await using var after = NewDb();
        var issue = await after.Issues.FirstAsync(i => i.Id == a.Id);
        Assert.Equal(IssueState.Refuted, issue.State);
        var votes = await after.IssueVotes.Where(v => v.IssueId == a.Id).ToListAsync();
        Assert.Equal(4, votes.Count);
        Assert.All(votes, v => { Assert.Null(v.VoterId); Assert.NotNull(v.VoterHash); Assert.Null(v.CreatedAt); });
        Assert.NotNull((await after.IssueSalts.FirstAsync(s => s.IssueId == a.Id)).ResolvedAt);
        var voter = await after.Users.FirstAsync(u => u.Id == users[2].Id);
        Assert.Equal((1, 1), (voter.VerifierVotes, voter.VerifierAgreed));
        await Assert.ThrowsAsync<ConflictException>(() => svc.VoteAsync(users[1].Id, a.Id, new IssueVoteRequest(IssueAnswer.Still, Near), default));
    }

    [Fact]
    public async Task One_way_reports_for_opposite_directions_are_not_merged()
    {
        if (_ds is null) { Assert.Skip("GIMANO_PG_TEST not set"); return; }
        var users = await SeedAsync(3);
        var (svc, _, _) = Service(new Clock(T0));
        CreateIssueRequest OneWay(double heading) =>
            new(IssueKind.OnewayWrongClosedDir, null, Lat, Lng + 0.00003, heading, null, null, null, IssueSource.Report, null);

        var north = await svc.CreateAsync(users[0].Id, OneWay(0), "en", default);
        var south = await svc.CreateAsync(users[1].Id, OneWay(180), "en", default);
        Assert.False(south.Merged);   // before: merged into the northbound issue as a "still" vote for the wrong direction
        Assert.NotEqual(north.Id, south.Id);
        Assert.Equal((IssueDirection.Forward, IssueDirection.Backward), (north.Direction, south.Direction));

        var north2 = await svc.CreateAsync(users[2].Id, OneWay(5), "en", default);
        Assert.True(north2.Merged);
        Assert.Equal(north.Id, north2.Id);
    }

    [Fact]
    public async Task Votes_need_proximity_and_new_accounts_weigh_nothing()
    {
        if (_ds is null) { Assert.Skip("GIMANO_PG_TEST not set"); return; }
        var users = await SeedAsync(3);
        var (svc, db, _) = Service(new Clock(T0));
        var a = await svc.CreateAsync(users[0].Id, Closed(), "en", default);
        await Assert.ThrowsAsync<ForbiddenException>(() => svc.VoteAsync(users[1].Id, a.Id, new IssueVoteRequest(IssueAnswer.Still, new ProximityClaim("w0zzzz", 100)), default));
        await Assert.ThrowsAsync<ValidationException>(() => svc.VoteAsync(users[1].Id, a.Id, new IssueVoteRequest(IssueAnswer.Still, null), default));
        await db.Users.Where(u => u.Id == users[2].Id).ExecuteUpdateAsync(s => s.SetProperty(u => u.JoinedAt, T0.AddDays(-1)));
        var r = await svc.VoteAsync(users[2].Id, a.Id, new IssueVoteRequest(IssueAnswer.Still, Near), default);
        Assert.Equal(IssueState.Suspected, r.State);   // < 7 days old: weight 0
        Assert.Equal(1, r.ConfirmationsWeighted);
    }

    [Fact]
    public async Task Major_roads_wait_for_passive_evidence_and_traffic_refutes()
    {
        if (_ds is null) { Assert.Skip("GIMANO_PG_TEST not set"); return; }
        var users = await SeedAsync(3, highway: "trunk");
        var clock = new Clock(T0);
        var (svc, db, _) = Service(clock);
        var a = await svc.CreateAsync(users[0].Id, Closed("flood"), "en", default);
        await svc.VoteAsync(users[1].Id, a.Id, new IssueVoteRequest(IssueAnswer.Still, Near), default);
        var r = await svc.VoteAsync(users[2].Id, a.Id, new IssueVoteRequest(IssueAnswer.Still, Near), default);
        Assert.Equal(IssueState.Suspected, r.State);   // major road: 3 weighted but no passive evidence
        Assert.Equal(3, r.Required);

        // Five vehicles drive through in the hour after the report → refuted by traffic (the 15-minute job).
        db.SegmentTraversalCounts.Add(new SegmentTraversalCount { WayId = 4242, Seq = 0, Direction = IssueDirection.Forward, Hour = T0.AddHours(1), Count = 5 });
        await db.SaveChangesAsync();
        clock.Now = T0.AddHours(2.5);
        var issue = await db.Issues.FirstAsync(i => i.Id == a.Id);
        Assert.Equal(IssueState.Refuted, await IssueLedger.EvaluateAsync(db, issue, clock.Now, default));
        Assert.Contains("traffic 5", issue.History[^1]);
    }

    [Fact]
    public async Task Emas_confirms_a_place_closure_which_hides_it_and_two_still_open_reopen_it()
    {
        if (_ds is null) { Assert.Skip("GIMANO_PG_TEST not set"); return; }
        var users = await SeedAsync(3, emasFirst: true);
        var clock = new Clock(T0);
        var (svc, db, _) = Service(clock);
        var a = await svc.CreateAsync(users[0].Id, new CreateIssueRequest(IssueKind.PlaceClosedPerm, null, Lat, Lng, null, "n77", null, null, IssueSource.PlaceEdit, null), "ms", default);
        Assert.Equal(IssueState.Confirmed, a.State);
        Assert.Equal("Kedai Runcit Ali: tutup kekal", a.Summary);

        // While confirmed, the place is hidden from search/nearby.
        var services = new ServiceCollection().AddScoped(_ => NewDb()).BuildServiceProvider();
        var directory = new IssueDirectory(services.GetRequiredService<IServiceScopeFactory>(), clock, NullLogger<IssueDirectory>.Instance);
        var snap = await directory.GetAsync(default);
        Assert.Contains("n77", snap.ClosedPlaces);
        Assert.Equal("Tutup kekal", IssueDirectory.Badge(snap, "n77", "ms")!.Label);

        Assert.True(snap.IsClosedNow("n77"));   // openNow=false while closed

        // Two "masih buka" answers reopen it (spec: reopened by 2 votes).
        var r1 = await svc.VoteAsync(users[1].Id, a.Id, new IssueVoteRequest(IssueAnswer.Cleared, Near), default);
        Assert.Equal(IssueState.Confirmed, r1.State);
        var r2 = await svc.VoteAsync(users[2].Id, a.Id, new IssueVoteRequest(IssueAnswer.Cleared, Near), default);
        Assert.Equal(IssueState.Refuted, r2.State);
        directory.Invalidate();
        Assert.DoesNotContain("n77", (await directory.GetAsync(default)).ClosedPlaces);
        Assert.False((await directory.GetAsync(default)).IsClosedNow("n77"));

        // A temporary closure (even before it's confirmed) makes the place "not open now" until its date.
        await svc.CreateAsync(users[1].Id, new CreateIssueRequest(IssueKind.PlaceClosedTemp, null, Lat, Lng, null, "n77", null, null, IssueSource.PlaceEdit,
            System.Text.Json.Nodes.JsonNode.Parse("""{"until":"2026-11-15"}""")!.AsObject()), "ms", default);
        directory.Invalidate();
        var s2 = await directory.GetAsync(default);
        Assert.True(s2.IsClosedNow("n77"));
        Assert.Equal("Tutup sementara sehingga 2026-11-15", IssueDirectory.Badge(s2, "n77", "ms")!.Label);
    }

    [Fact]
    public async Task Expiry_and_map_resolution_by_the_maintenance_rules()
    {
        if (_ds is null) { Assert.Skip("GIMANO_PG_TEST not set"); return; }
        var users = await SeedAsync(2);
        var clock = new Clock(T0);
        var (svc, db, _) = Service(clock);
        var closed = await svc.CreateAsync(users[0].Id, Closed("accident"), "en", default);
        var noRoad = await svc.CreateAsync(users[1].Id, new CreateIssueRequest(IssueKind.RoadNotExist, "gate", Lat, Lng, null, null, Vehicle.Car, null, IssueSource.Deviation, null), "en", default);

        clock.Now = T0.AddHours(3);
        var i1 = await db.Issues.FirstAsync(i => i.Id == closed.Id);
        Assert.Equal(IssueState.Expired, await IssueLedger.EvaluateAsync(db, i1, clock.Now, default));

        var i2 = await db.Issues.FirstAsync(i => i.Id == noRoad.Id);
        Assert.False(await Gimano.Worker.Issues.IssueMaintenance.MapNowMatchesAsync(db, i2, default));
        await db.RoadSegments.Where(s => s.OsmWayId == 4242).ExecuteDeleteAsync();
        db.RoadSegments.Add(new RoadSegment { OsmWayId = 1, Seq = 0, Highway = "residential", FreeFlowKmh = 30, LengthM = 10, FromNode = 1, ToNode = 2,
            Geom = GeoMath.ToLineString([new GeoPoint(1, 101), new GeoPoint(1.0001, 101)]) });
        await db.SaveChangesAsync();
        Assert.True(await Gimano.Worker.Issues.IssueMaintenance.MapNowMatchesAsync(db, i2, default));   // the gate's way was removed from OSM
    }
}
