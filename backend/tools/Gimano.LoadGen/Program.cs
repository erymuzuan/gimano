using System.Collections.Concurrent;
using System.Diagnostics;
using System.Globalization;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Runtime.CompilerServices;
using System.Text.Json;
using Gimano.Core.Contracts;
using Gimano.Core.Geo;

// Gimano location load generator: simulated drivers moving along real Valhalla routes, POSTing /locations.
//   dotnet run -c Release --project tools/Gimano.LoadGen -- --base http://100.101.150.9:5080 --drivers 5000 --duration 60
// Each driver produces 1 ping/s and sends a batch of 5 every 5 s → drivers pings/s, drivers/5 requests/s.
// --jam-share of drivers crawl at 6–10 km/h on the first 4 km of route 0 so the worker should detect a jam there.

string Arg(string name, string def) => args.SkipWhile(a => a != name).Skip(1).FirstOrDefault() ?? def;
var baseUrl = Arg("--base", "http://localhost:5080").TrimEnd('/');
var drivers = int.Parse(Arg("--drivers", "5000"), CultureInfo.InvariantCulture);
var duration = TimeSpan.FromSeconds(int.Parse(Arg("--duration", "60"), CultureInfo.InvariantCulture));
var jamShare = double.Parse(Arg("--jam-share", "0.1"), CultureInfo.InvariantCulture);
var concurrency = int.Parse(Arg("--concurrency", "512"), CultureInfo.InvariantCulture);
const int BatchEvery = 5;

var json = GimanoJson.Options;
using var http = new HttpClient(new SocketsHttpHandler { MaxConnectionsPerServer = concurrency, PooledConnectionLifetime = TimeSpan.FromMinutes(5) })
{ BaseAddress = new Uri(baseUrl + "/"), Timeout = TimeSpan.FromSeconds(30) };

// 1. Real routes around the Klang Valley.
(double, double, double, double)[] pairs =
[
    (3.1180, 101.6190, 3.1176, 101.6773), // SS2 → Mid Valley (via SPRINT)
    (3.1579, 101.7116, 3.0738, 101.5183), // KLCC → Shah Alam
    (3.0470, 101.5870, 3.1390, 101.6869), // Subang → KL
    (3.1478, 101.6155, 2.9935, 101.7880), // TTDI → Kajang
    (3.0727, 101.6070, 3.2028, 101.6380), // Sunway → Kepong
    (3.1390, 101.6869, 2.9264, 101.6964), // KL → Putrajaya
];
var routes = new List<IReadOnlyList<GeoPoint>>();
foreach (var (oLat, oLng, dLat, dLng) in pairs)
{
    var resp = await http.PostAsJsonAsync("api/v1/routes", new { origin = new { lat = oLat, lng = oLng }, destination = new { lat = dLat, lng = dLng }, alternatives = 0 });
    resp.EnsureSuccessStatusCode();
    var doc = await resp.Content.ReadFromJsonAsync<JsonElement>();
    var poly = doc.GetProperty("routes")[0].GetProperty("polyline").GetString()!;
    routes.Add(Densify(Polyline.Decode(poly)));
}
Console.WriteLine($"routes: {string.Join(", ", routes.Select(r => $"{GeoMath.LengthM(r) / 1000:n1} km"))}");

// 2. One device token per driver.
var tokens = new string[drivers];
var sw = Stopwatch.StartNew();
await Parallel.ForAsync(0, drivers, new ParallelOptions { MaxDegreeOfParallelism = 64 }, async (i, ct) =>
{
    var r = await http.PostAsJsonAsync("api/v1/auth/device", new { deviceId = $"loadgen-{i:D6}", platform = "android", locale = "ms" }, ct);
    r.EnsureSuccessStatusCode();
    tokens[i] = (await r.Content.ReadFromJsonAsync<JsonElement>(ct)).GetProperty("token").GetString()!;
});
Console.WriteLine($"tokens: {drivers} in {sw.Elapsed.TotalSeconds:n1}s");

// 3. Drivers.
var rnd = new Random(7);
var state = Enumerable.Range(0, drivers).Select(i =>
{
    var jam = i < drivers * jamShare;
    var route = jam ? 0 : rnd.Next(routes.Count);
    var len = GeoMath.LengthM(routes[route]);
    return new Driver(route, jam ? rnd.NextDouble() * Math.Min(4000, len) : rnd.NextDouble() * len,
        jam ? 6 + rnd.NextDouble() * 4 : 35 + rnd.NextDouble() * 50, jam, i % BatchEvery);
}).ToArray();

var latencies = new ConcurrentBag<double>();
long sent = 0, failed = 0, pings = 0;
using var gate = new SemaphoreSlim(concurrency);
var inflight = new List<Task>();
var start = Stopwatch.StartNew();
var tick = 0;
while (start.Elapsed < duration)
{
    var tickStart = start.Elapsed;
    foreach (var d in state) d.Advance(routes[d.Route], 1.0);
    for (var i = 0; i < drivers; i++)
    {
        var d = state[i];
        if (tick % BatchEvery != d.Slot) continue;
        var pts = d.TakeBatch();
        if (pts.Count == 0) continue;
        var token = tokens[i];
        await gate.WaitAsync();
        inflight.Add(Task.Run(async () =>
        {
            try
            {
                var t = Stopwatch.StartNew();
                using var req = new HttpRequestMessage(HttpMethod.Post, "api/v1/locations") { Content = JsonContent.Create(new { routeId = (string?)null, points = pts }) };
                req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
                using var resp = await http.SendAsync(req);
                latencies.Add(t.Elapsed.TotalMilliseconds);
                Interlocked.Increment(ref sent);
                Interlocked.Add(ref pings, pts.Count);
                if ((int)resp.StatusCode != 202) Interlocked.Increment(ref failed);
            }
            catch { Interlocked.Increment(ref failed); }
            finally { gate.Release(); }
        }));
    }
    inflight.RemoveAll(t => t.IsCompleted);
    tick++;
    if (tick % 10 == 0) Console.WriteLine($"t={start.Elapsed.TotalSeconds:n0}s requests={sent:n0} pings={pings:n0} failed={failed} inflight={inflight.Count}");
    var wait = TimeSpan.FromSeconds(1) - (start.Elapsed - tickStart);
    if (wait > TimeSpan.Zero) await Task.Delay(wait);
}
await Task.WhenAll(inflight);
var secs = start.Elapsed.TotalSeconds;
var lat = latencies.Order().ToArray();
double P(double q) => lat.Length == 0 ? 0 : lat[Math.Min(lat.Length - 1, (int)(q * lat.Length))];
Console.WriteLine($"""
    RESULT drivers={drivers} duration={secs:n0}s requests={sent:n0} ({sent / secs:n0}/s) pings={pings:n0} ({pings / secs:n0}/s) failed={failed}
    POST /locations latency ms: p50={P(0.50):n1} p95={P(0.95):n1} p99={P(0.99):n1} max={(lat.Length > 0 ? lat[^1] : 0):n1}
    jam drivers: {state.Count(d => d.Jam)} on route 0 (first 4 km)
    """);

static List<GeoPoint> Densify(IReadOnlyList<GeoPoint> pts)
{
    var list = new List<GeoPoint> { pts[0] };
    for (var i = 1; i < pts.Count; i++)
    {
        var d = GeoMath.HaversineM(pts[i - 1], pts[i]);
        var n = (int)Math.Ceiling(d / 20);
        for (var k = 1; k <= n; k++)
            list.Add(new GeoPoint(pts[i - 1].Lat + (pts[i].Lat - pts[i - 1].Lat) * k / n, pts[i - 1].Lng + (pts[i].Lng - pts[i - 1].Lng) * k / n));
    }
    return list;
}

sealed class Driver(int route, double along, double speedKmh, bool jam, int slot)
{
    public int Route = route;
    public double Along = along;
    public readonly double SpeedKmh = speedKmh;
    public readonly bool Jam = jam;
    public readonly int Slot = slot;
    private readonly List<object> _pending = [];
    private double _start = along;

    public void Advance(IReadOnlyList<GeoPoint> route, double seconds)
    {
        Along += SpeedKmh / 3.6 * seconds;
        var limit = Jam ? Math.Min(4000, TotalLength(route)) : TotalLength(route);
        if (Along >= limit) Along = Jam ? 0 : 0;
        var (p, heading) = At(route, Along);
        _pending.Add(new { lat = Math.Round(p.Lat, 6), lng = Math.Round(p.Lng, 6), speedKmh = Math.Round(SpeedKmh + Random.Shared.NextDouble() * 4 - 2, 1), heading = Math.Round(heading), accuracyM = 6, ts = DateTimeOffset.UtcNow });
    }

    public List<object> TakeBatch() { var b = _pending.ToList(); _pending.Clear(); return b; }

    private static readonly ConditionalWeakTable<IReadOnlyList<GeoPoint>, double[]> Cum = new();
    private static double[] CumLen(IReadOnlyList<GeoPoint> r) => Cum.GetValue(r, rr =>
    {
        var c = new double[rr.Count];
        for (var i = 1; i < rr.Count; i++) c[i] = c[i - 1] + GeoMath.HaversineM(rr[i - 1], rr[i]);
        return c;
    });
    private static double TotalLength(IReadOnlyList<GeoPoint> r) => CumLen(r)[^1];

    private static (GeoPoint, double) At(IReadOnlyList<GeoPoint> r, double along)
    {
        var c = CumLen(r);
        var i = Array.BinarySearch(c, along);
        if (i < 0) i = ~i;
        i = Math.Clamp(i, 1, r.Count - 1);
        return (r[i], GeoMath.Bearing(r[i - 1], r[i]));
    }
}
