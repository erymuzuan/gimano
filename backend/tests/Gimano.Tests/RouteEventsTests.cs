using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Gimano.Api.Endpoints;
using Gimano.Api.Services;
using Gimano.Core.Contracts;
using Gimano.Core.Geo;
using Gimano.Core.Routing;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Caching.Memory;
using Microsoft.Extensions.Logging;

namespace Gimano.Tests;

/// <summary>docs/events.md §6: <c>"store": false</c> on POST /routes and the stateless POST /routes/match.</summary>
public class RouteEventsTests
{
    private static readonly LatLng A = new(3.1390, 101.6869), B = new(3.1579, 101.7123);

    private static RouteRequest Req(int waypoints, bool? store) =>
        new(A, B, Enumerable.Range(0, waypoints).Select(i => new LatLng(3.14 + i * 0.001, 101.69 + i * 0.001)).ToList(),
            Vehicle.Car, null, null, null, Store: store);

    /// <summary>Only the cache is used by Remember / the reroute lookup; nothing else is touched.</summary>
    private static RouteService Service(IMemoryCache cache) => new(null!, null!, null!, null!, cache, TimeProvider.System, null!);

    private static RouteDto Dto(string id) => new(id, RouteLabel.Best, 60, 1000, 60, "", "", Vehicle.Car, false, null, true, [],
        "", [0, 0, 0, 0], [], [], []);

    // ---- store: false ----------------------------------------------------------------

    [Fact]
    public void Store_defaults_to_true_and_is_read_from_the_wire()
    {
        Assert.True(Req(0, null).Stored);
        Assert.True(Req(0, true).Stored);
        Assert.False(Req(0, false).Stored);

        var parsed = JsonSerializer.Deserialize<RouteRequest>(
            """{"origin":{"lat":3.139,"lng":101.687},"destination":{"lat":3.158,"lng":101.712},"store":false}""", GimanoJson.Options)!;
        Assert.False(parsed.Stored);
        var legacy = JsonSerializer.Deserialize<RouteRequest>(
            """{"origin":{"lat":3.139,"lng":101.687},"destination":{"lat":3.158,"lng":101.712}}""", GimanoJson.Options)!;
        Assert.True(legacy.Stored);
    }

    [Theory]
    [InlineData(5, null, true)]
    [InlineData(6, null, false)]
    [InlineData(6, true, false)]
    [InlineData(6, false, true)]
    [InlineData(25, false, true)]
    [InlineData(26, false, false)]
    public void Waypoint_limit_is_25_only_when_stateless(int waypoints, bool? store, bool ok)
    {
        var problem = RouteEndpoints.Validate(Req(waypoints, store));
        Assert.Equal(ok, problem is null);
    }

    [Fact]
    public void Stateless_route_ids_start_with_x()
    {
        Assert.StartsWith("x-", RouteService.NewRouteId(false)());
        Assert.False(RouteService.NewRouteId(true)().StartsWith("x-", StringComparison.Ordinal));
        Assert.NotEqual(RouteService.NewRouteId(false)(), RouteService.NewRouteId(false)());
    }

    [Fact]
    public void Store_false_skips_the_reroute_cache()
    {
        var cache = new MemoryCache(new MemoryCacheOptions());
        var svc = Service(cache);

        svc.Remember([Dto("x-stateless")], Req(0, false));
        Assert.Equal(0, cache.Count);
        Assert.False(cache.TryGetValue("route:x-stateless", out _));

        svc.Remember([Dto("kept")], Req(0, null));
        Assert.Equal(1, cache.Count);
        Assert.True(cache.TryGetValue("route:kept", out RouteRequest? kept));
        Assert.True(kept!.Stored);
    }

    [Fact]
    public async Task Reroute_of_a_stateless_route_is_404()
    {
        var cache = new MemoryCache(new MemoryCacheOptions());
        // Even if something were cached under an x- id, it is never used.
        cache.Set("route:x-abc", Req(0, null));
        var svc = Service(cache);
        await Assert.ThrowsAsync<NotFoundException>(() =>
            svc.RerouteAsync("x-abc", new RerouteRequest(3.14, 101.69, null), null, CancellationToken.None));
        await Assert.ThrowsAsync<NotFoundException>(() =>
            svc.RerouteAsync("x-unknown", new RerouteRequest(3.14, 101.69, null), null, CancellationToken.None));
    }

    // ---- more stops than Valhalla accepts per request -----------------------------------------

    [Fact]
    public void Up_to_20_locations_is_one_valhalla_request()
    {
        var req = Req(18, false);
        Assert.Same(req, Assert.Single(ValhallaMapper.SplitStops(req)));
    }

    [Fact]
    public void Twenty_five_waypoints_are_split_into_chunks_sharing_their_boundary_stop()
    {
        var req = Req(25, false) with { Alternatives = 2 };
        var chunks = ValhallaMapper.SplitStops(req);
        Assert.Equal(2, chunks.Count);
        Assert.All(chunks, c => Assert.True(2 + c.Waypoints!.Count <= ValhallaMapper.MaxLocationsPerRequest));
        Assert.All(chunks, c => Assert.Equal(0, c.Alternatives));
        Assert.Equal(req.Origin, chunks[0].Origin);
        Assert.Equal(chunks[0].Destination, chunks[1].Origin);
        Assert.Equal(req.Destination, chunks[1].Destination);
        // 27 locations → 26 legs in total, every stop kept in order.
        var stops = chunks.SelectMany(c => c.Waypoints!.Append(c.Destination)).ToList();
        Assert.Equal(req.Waypoints!.Append(req.Destination), stops);
        Assert.Equal(26, chunks.Sum(c => c.Waypoints!.Count + 1));
    }

    [Fact]
    public void Chunk_routes_join_into_one_route_with_shifted_shape_indexes()
    {
        var part = ValhallaMapper.MapResponse(ValhallaMappingTests.SampleResponse(withAlternate: false, twoLegs: true))[0];
        var joined = ValhallaMapper.Join([part, part]);

        Assert.Equal(part.Points.Count * 2 - 1, joined.Points.Count);
        Assert.Equal(Polyline.Encode(joined.Points), joined.Polyline);
        Assert.Equal(part.Legs.Count * 2, joined.Legs.Count);
        Assert.Equal(Math.Round(part.DurationS * 2, 1), joined.DurationS);
        Assert.Equal(Math.Round(part.DistanceM * 2, 1), joined.DistanceM);
        var offset = part.Points.Count - 1;
        Assert.Equal(part.Legs[0].Maneuvers[0].BeginShapeIndex + offset, joined.Legs[part.Legs.Count].Maneuvers[0].BeginShapeIndex);
        Assert.Equal(joined.Points.Count - 1, joined.Legs[^1].Maneuvers[^1].EndShapeIndex);
    }

    // ---- POST /routes/match: validation ---------------------------------------------------------

    private static RouteMatchRequest Match(int n, Vehicle? v = Vehicle.Bicycle) =>
        new(Enumerable.Range(0, n).Select(i => (MatchPoint?)new MatchPoint(3.158 + i * 0.0001, 101.712 + i * 0.0001)).ToList(), v);

    [Theory]
    [InlineData(2, true)]
    [InlineData(5000, true)]
    [InlineData(1, false)]
    [InlineData(0, false)]
    [InlineData(5001, false)]
    public void Match_needs_2_to_5000_points(int n, bool ok) => Assert.Equal(ok, RouteEndpoints.ValidateMatch(Match(n)) is null);

    [Fact]
    public void Match_validates_every_point()
    {
        Assert.NotNull(RouteEndpoints.ValidateMatch(new RouteMatchRequest(null, Vehicle.Car)));
        Assert.NotNull(RouteEndpoints.ValidateMatch(new RouteMatchRequest([new MatchPoint(3.1, 101.6), null], Vehicle.Car)));
        Assert.NotNull(RouteEndpoints.ValidateMatch(new RouteMatchRequest([new MatchPoint(3.1, 101.6), new MatchPoint(91, 101.6)], Vehicle.Car)));
        Assert.NotNull(RouteEndpoints.ValidateMatch(new RouteMatchRequest([new MatchPoint(3.1, 101.6), new MatchPoint(3.1, 181)], Vehicle.Car)));
        Assert.NotNull(RouteEndpoints.ValidateMatch(new RouteMatchRequest([new MatchPoint(3.1, 101.6), new MatchPoint(0, 0)], Vehicle.Car)));
        Assert.Null(RouteEndpoints.ValidateMatch(new RouteMatchRequest([new MatchPoint(3.1, 101.6), new MatchPoint(3.2, 101.7)], null)));

        var bad = RouteEndpoints.ValidateMatch(new RouteMatchRequest([new MatchPoint(3.1, 101.6), new MatchPoint(3.1, 101.6), new MatchPoint(99, 1)], Vehicle.Car));
        Assert.Contains("points[2]", bad!.ProblemDetails.Errors.Keys);
    }

    [Fact]
    public void Match_request_wire_shape()
    {
        var r = JsonSerializer.Deserialize<RouteMatchRequest>(
            """{"points":[{"lat":3.1,"lng":101.7},{"lat":3.2,"lng":101.8}],"vehicle":"walk"}""", GimanoJson.Options)!;
        Assert.Equal(Vehicle.Walk, r.Vehicle);
        Assert.Equal(101.8, r.Points![1]!.Lng);

        var json = JsonNode.Parse(JsonSerializer.Serialize(
            new RouteMatchResponse("abc", 615, 61.4, [new UnmatchedRange(3, 4)]), GimanoJson.Options))!;
        Assert.Equal("abc", json["polyline"]!.GetValue<string>());
        Assert.Equal(615, json["distanceM"]!.GetValue<double>());
        Assert.Equal(61.4, json["durationS"]!.GetValue<double>());
        Assert.Equal(3, json["unmatched"]![0]!["fromIndex"]!.GetValue<int>());
        Assert.Equal(4, json["unmatched"]![0]!["toIndex"]!.GetValue<int>());
    }

    // ---- POST /routes/match: Valhalla mapping ---------------------------------------------------

    [Theory]
    [InlineData(Vehicle.Car, "auto")]
    [InlineData(Vehicle.Motorcycle, "motorcycle")]
    [InlineData(Vehicle.Bicycle, "bicycle")]
    [InlineData(Vehicle.Walk, "pedestrian")]
    public void Trace_request_uses_map_snap_and_the_route_costing(Vehicle v, string costing)
    {
        var body = ValhallaMapper.BuildTraceRequest([new MatchPoint(3.1, 101.6), new MatchPoint(3.2, 101.7)], v);
        Assert.Equal(costing, body["costing"]!.GetValue<string>());
        Assert.NotNull(body["costing_options"]![costing]);
        Assert.Equal("map_snap", body["shape_match"]!.GetValue<string>());
        Assert.Equal(101.7, body["shape"]![1]!["lon"]!.GetValue<double>());
        Assert.Equal(3.2, body["shape"]![1]!["lat"]!.GetValue<double>());
        var attrs = body["filters"]!["attributes"]!.AsArray().Select(a => a!.GetValue<string>()).ToList();
        Assert.Contains("matched.type", attrs);
        Assert.Contains("edge.length", attrs);
        Assert.Contains("shape", attrs);
        Assert.Equal("include", body["filters"]!["action"]!.GetValue<string>());
    }

    /// <summary>Matched road geometry along Jalan Ampang, KL (what Valhalla returns as <c>shape</c>).</summary>
    private static readonly GeoPoint[] MatchedShape =
    [
        new(3.157900, 101.712000), new(3.158050, 101.712900), new(3.158200, 101.713850),
        new(3.158400, 101.714800), new(3.158700, 101.715750), new(3.159000, 101.716600),
    ];

    /// <summary>
    /// A /trace_attributes response in Valhalla's documented format (units km, <c>matched_points</c> index-aligned with the
    /// 7 input points; points 3–4 were drawn across a building and match nothing).
    /// </summary>
    internal static JsonNode TraceFixture(bool elapsedTime = true, string? types = null)
    {
        var t = (types ?? "matched,matched,interpolated,unmatched,unmatched,matched,matched").Split(',');
        var endNode = elapsedTime ? """, "end_node": {"elapsed_time": 61.4, "type": "street_intersection"}""" : "";
        var mp = string.Join(",\n", t.Select((type, i) => type == "unmatched"
            ? $$"""{"type": "unmatched", "lat": {{3.1580 + i * 0.0002:0.000000}}, "lon": {{101.7125 + i * 0.0008:0.000000}}, "distance_along_edge": 0}"""
            : $$"""{"type": "{{type}}", "lat": {{3.1580 + i * 0.0002:0.000000}}, "lon": {{101.7125 + i * 0.0008:0.000000}}, "edge_index": {{Math.Min(i / 3, 2)}}, "distance_along_edge": 0.42, "distance_from_trace_point": 7.8}"""));
        return JsonNode.Parse($$"""
        {
          "osm_changeset": 0,
          "admins": [{"country_code": "MY", "country_text": "Malaysia", "state_code": "14", "state_text": "Kuala Lumpur"}],
          "edges": [
            {"length": 0.198, "speed": 40, "names": ["Jalan Ampang"], "way_id": 4361021, "begin_shape_index": 0, "end_shape_index": 2,
             "end_node": {"elapsed_time": 17.8, "type": "street_intersection"} },
            {"length": 0.212, "speed": 30, "names": ["Jalan Ampang"], "way_id": 4361021, "begin_shape_index": 2, "end_shape_index": 4,
             "end_node": {"elapsed_time": 43.2, "type": "street_intersection"} },
            {"length": 0.205, "speed": 45, "names": ["Jalan Ampang"], "way_id": 28839121, "begin_shape_index": 4, "end_shape_index": 5{{endNode}} }
          ],
          "matched_points": [
          {{mp}}
          ],
          "shape": "{{Polyline.Encode(MatchedShape)}}",
          "confidence_score": 1,
          "units": "kilometers"
        }
        """)!;
    }

    [Fact]
    public void Trace_response_maps_polyline_distance_duration_and_unmatched_ranges()
    {
        var m = ValhallaMapper.MapTrace(TraceFixture(), 7)!;
        Assert.Equal(Polyline.Encode(MatchedShape), m.Polyline);
        Assert.Equal(MatchedShape, Polyline.Decode(m.Polyline));
        Assert.Equal(615.0, m.DistanceM);
        Assert.Equal(61.4, m.DurationS);
        Assert.Equal([new UnmatchedRange(3, 4)], m.Unmatched);
    }

    [Fact]
    public void Trace_duration_falls_back_to_edge_speeds()
    {
        var m = ValhallaMapper.MapTrace(TraceFixture(elapsedTime: false), 7)!;
        // 198 m @ 40 + 212 m @ 30 + 205 m @ 45 km/h
        Assert.Equal(Math.Round(198 / (40 / 3.6) + 212 / (30 / 3.6) + 205 / (45 / 3.6), 1), m.DurationS);
    }

    [Fact]
    public void Nothing_matched_is_null()
    {
        Assert.Null(ValhallaMapper.MapTrace(TraceFixture(types: "unmatched,unmatched,unmatched"), 3));
        Assert.Null(ValhallaMapper.MapTrace(JsonNode.Parse("""{"edges":[],"matched_points":[],"shape":"","units":"kilometers"}""")!, 2));
    }

    public static TheoryData<string, int[]> Ranges() => new()
    {
        { "m,m,m", [] },
        { "u,m,m", [0, 0] },
        { "m,m,u,u", [2, 3] },
        { "u,u,m,i,u,m,u", [0, 1, 4, 4, 6, 6] },
        { "u,u", [0, 1] },
    };

    [Theory]
    [MemberData(nameof(Ranges))]
    public void Unmatched_ranges_are_inclusive_runs_of_input_indexes(string types, int[] expected)
    {
        var t = types.Split(',').Select(c => c switch { "u" => "unmatched", "i" => "interpolated", _ => "matched" }).ToList();
        var ranges = ValhallaMapper.UnmatchedRanges(t);
        Assert.Equal(expected, ranges.SelectMany(r => new[] { r.FromIndex, r.ToIndex }));
    }

    // ---- POST /routes/match: Valhalla errors → 422 / 503, never logging coordinates ---------------

    private sealed class FakeValhalla(Func<HttpRequestMessage, HttpResponseMessage> respond) : HttpMessageHandler
    {
        public string? LastPath, LastBody;
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            LastPath = request.RequestUri!.AbsolutePath;
            LastBody = request.Content is null ? null : await request.Content.ReadAsStringAsync(ct);
            return respond(request);
        }
    }

    private sealed class ListLogger<T> : ILogger<T>
    {
        public readonly List<string> Lines = [];
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;
        public bool IsEnabled(LogLevel logLevel) => true;
        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter)
            => Lines.Add(formatter(state, exception));
    }

    private static (RouteMatchService Svc, FakeValhalla Handler, ListLogger<ValhallaClient> Log) Matcher(HttpStatusCode status, string body)
    {
        var handler = new FakeValhalla(_ => new HttpResponseMessage(status) { Content = new StringContent(body, Encoding.UTF8, "application/json") });
        var log = new ListLogger<ValhallaClient>();
        var client = new ValhallaClient(new HttpClient(handler) { BaseAddress = new Uri("http://valhalla.test:8002/") }, log);
        return (new RouteMatchService(client), handler, log);
    }

    private static RouteMatchRequest SevenPoints() => Match(7);

    [Fact]
    public async Task Match_posts_to_trace_attributes_and_maps_the_result()
    {
        var (svc, handler, _) = Matcher(HttpStatusCode.OK, TraceFixture().ToJsonString());
        var m = await svc.MatchAsync(SevenPoints(), CancellationToken.None);
        Assert.Equal("/trace_attributes", handler.LastPath);
        var sent = JsonNode.Parse(handler.LastBody!)!;
        Assert.Equal("bicycle", sent["costing"]!.GetValue<string>());
        Assert.Equal(7, sent["shape"]!.AsArray().Count);
        Assert.Equal([new UnmatchedRange(3, 4)], m.Unmatched);
    }

    [Fact]
    public async Task Nothing_matched_is_a_NoRouteException_so_422()
    {
        var (svc, _, _) = Matcher(HttpStatusCode.OK, TraceFixture(types: "unmatched,unmatched,unmatched,unmatched,unmatched,unmatched,unmatched").ToJsonString());
        await Assert.ThrowsAsync<NoRouteException>(() => svc.MatchAsync(SevenPoints(), CancellationToken.None));

        var (svc2, _, log) = Matcher(HttpStatusCode.BadRequest,
            """{"error_code":443,"error":"Map Match algorithm failed to find path: 3.158,101.712","status_code":400,"status":"Bad Request"}""");
        await Assert.ThrowsAsync<NoRouteException>(() => svc2.MatchAsync(SevenPoints(), CancellationToken.None));
        Assert.Empty(log.Lines);
    }

    [Fact]
    public async Task Valhalla_down_is_RoutingUnavailable_so_503_without_logging_the_body()
    {
        var (svc, _, log) = Matcher(HttpStatusCode.InternalServerError,
            """{"error_code":499,"error":"Unknown: 3.158,101.712","status_code":500}""");
        await Assert.ThrowsAsync<RoutingUnavailableException>(() => svc.MatchAsync(SevenPoints(), CancellationToken.None));
        var line = Assert.Single(log.Lines);
        Assert.Contains("trace_attributes", line);
        Assert.DoesNotContain("3.158", line);
        Assert.DoesNotContain("101.712", line);

        var unreachable = new ValhallaClient(new HttpClient(new FakeValhalla(_ => throw new HttpRequestException("Connection refused")))
            { BaseAddress = new Uri("http://valhalla.test:8002/") }, new ListLogger<ValhallaClient>());
        await Assert.ThrowsAsync<RoutingUnavailableException>(() => new RouteMatchService(unreachable).MatchAsync(SevenPoints(), CancellationToken.None));
    }

    private sealed class Problems : IProblemDetailsService
    {
        public Microsoft.AspNetCore.Mvc.ProblemDetails? Last;
        public ValueTask WriteAsync(ProblemDetailsContext context) { Last = context.ProblemDetails; return ValueTask.CompletedTask; }
    }

    [Theory]
    [InlineData(true, 422)]
    [InlineData(false, 503)]
    public async Task Match_errors_map_to_problem_json(bool noMatch, int status)
    {
        var problems = new Problems();
        var handler = new Gimano.Api.ApiExceptionHandler(problems, Microsoft.Extensions.Logging.Abstractions.NullLogger<Gimano.Api.ApiExceptionHandler>.Instance);
        var ctx = new DefaultHttpContext();
        Exception ex = noMatch ? new NoRouteException("The line could not be matched to any road.") : new RoutingUnavailableException("down");
        await handler.TryHandleAsync(ctx, ex, CancellationToken.None);
        Assert.Equal(status, ctx.Response.StatusCode);
        Assert.Equal(status, problems.Last!.Status);
    }
}
