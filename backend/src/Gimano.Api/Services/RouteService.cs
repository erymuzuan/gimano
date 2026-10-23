using System.Globalization;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Gimano.Core.Geo;
using Gimano.Core.Routing;
using Gimano.Core.Tolls;
using Gimano.Core.Traffic;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Caching.Memory;

namespace Gimano.Api.Services;

public sealed class RouteService(
    ValhallaClient valhalla, ReportService reports, GeoLookup geo, GimanoDbContext db,
    IMemoryCache cache, TimeProvider clock)
{
    private static readonly TimeSpan CacheFor = TimeSpan.FromHours(3);

    private static readonly TimeSpan TariffCacheFor = TimeSpan.FromMinutes(10);

    /// <param name="cacheAs">The request stored for reroute (the original request, so <c>nextStopIndex</c> always indexes its waypoints).</param>
    public async Task<IReadOnlyList<RouteDto>> RouteAsync(RouteRequest req, Guid? userId, CancellationToken ct, double? originHeading = null, RouteRequest? cacheAs = null)
    {
        var vehicle = req.Vehicle ?? Vehicle.Car;
        var stops = (req.Waypoints ?? []).Append(req.Destination).ToList();
        var response = await valhalla.RouteAsync(ValhallaMapper.BuildRouteRequest(req, originHeading), ct);
        var candidates = ValhallaMapper.MapResponse(response, stops);
        if (candidates.Count == 0) throw new NoRouteException("No route found.");

        var bbox = candidates.Select(c => BBox.Of(c.Points)).Aggregate((a, b) =>
            new BBox(Math.Min(a.MinLng, b.MinLng), Math.Min(a.MinLat, b.MinLat), Math.Max(a.MaxLng, b.MaxLng), Math.Max(a.MaxLat, b.MaxLat)))
            .Expand(200);

        var activeReports = await reports.ActiveInBBoxAsync(bbox, ct);
        var motorised = ValhallaMapper.IsMotorised(vehicle);
        List<JamLine> jams = motorised
            ? (await geo.JamsInBBoxAsync(bbox, ct)).Select(j => new JamLine(j.Level, j.SpeedKmh, GeoMath.FromLineString(j.Geom))).ToList()
            : [];

        IReadOnlyList<TollPlaza> plazas = [];
        IReadOnlyList<TollTariff> tariffs = [];
        if (motorised)
        {
            var bboxPoly = bbox.ToPolygon();
            plazas = (await db.Places.AsNoTracking()
                    .Where(p => p.Category == PlaceCategory.TollBooth && p.Location.Intersects(bboxPoly))
                    .Select(p => new { p.Id, p.Name, p.Location }).Take(5000).ToListAsync(ct))
                .Select(p => new TollPlaza(p.Id, p.Name, p.Location.Y, p.Location.X)).ToList();
            tariffs = plazas.Count > 0 ? await TariffsAsync(ct) : [];
        }

        var routes = RouteDecorator.Decorate(candidates, activeReports, jams, () => Guid.CreateVersion7().ToString("N"), vehicle, plazas, tariffs);
        foreach (var r in routes) cache.Set(CacheKey(r.Id), cacheAs ?? req, CacheFor);

        // Privacy rule: destinations/recents are never recorded server-side (they live on the device).
        return routes;
    }

    public async Task<IReadOnlyList<RouteDto>> RerouteAsync(string routeId, RerouteRequest r, Guid? userId, CancellationToken ct)
    {
        if (!cache.TryGetValue(CacheKey(routeId), out RouteRequest? original) || original is null)
            throw new NotFoundException($"Route {routeId} is unknown or expired; request a new route.");
        var req = original with { Origin = new LatLng(r.Lat, r.Lng), Waypoints = RemainingStops(original.Waypoints, r.NextStopIndex), DepartAt = null };
        return await RouteAsync(req, userId, ct, r.Heading, cacheAs: original);
    }

    /// <summary>Stops not yet reached: <c>waypoints[nextStopIndex..]</c> (out-of-range → none left, negative → all).</summary>
    public static IReadOnlyList<LatLng> RemainingStops(IReadOnlyList<LatLng>? waypoints, int? nextStopIndex)
    {
        var all = waypoints ?? [];
        var from = Math.Clamp(nextStopIndex ?? 0, 0, all.Count);
        return all.Skip(from).ToList();
    }

    private async Task<IReadOnlyList<TollTariff>> TariffsAsync(CancellationToken ct) =>
        await cache.GetOrCreateAsync("toll-tariffs", async e =>
        {
            e.AbsoluteExpirationRelativeToNow = TariffCacheFor;
            var rows = await db.TollRates.AsNoTracking().Where(t => t.MatchedPlaceIds.Count > 0).ToListAsync(ct);
            return (IReadOnlyList<TollTariff>)rows.Select(TollRateCsv.ToTariff).ToList();
        }) ?? [];

    /// <summary>"Find the best time to leave": one Valhalla query per hour 06:00–22:00 local time.</summary>
    public async Task<IReadOnlyList<DepartureSlot>> DepartureTimesAsync(GeoPoint origin, GeoPoint dest, DateOnly? date, Vehicle vehicle, CancellationToken ct)
    {
        var today = DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(clock.GetUtcNow(), ValhallaMapper.MalaysiaTime).DateTime);
        var day = date ?? today;
        var slots = Enumerable.Range(6, 17)
            .Select(h => new DateTimeOffset(day.ToDateTime(new TimeOnly(h, 0)), TimeSpan.FromHours(8)))
            .ToList();

        using var gate = new SemaphoreSlim(4);
        var tasks = slots.Select(async t =>
        {
            await gate.WaitAsync(ct);
            try
            {
                var req = new RouteRequest(new LatLng(origin.Lat, origin.Lng), new LatLng(dest.Lat, dest.Lng), [], vehicle, null, t, 0);
                var resp = await valhalla.RouteAsync(ValhallaMapper.BuildRouteRequest(req), ct);
                var c = ValhallaMapper.MapResponse(resp).FirstOrDefault();
                return c is null ? null : new DepartureSlot(t.ToUniversalTime(), c.DurationS);
            }
            finally { gate.Release(); }
        });
        return (await Task.WhenAll(tasks)).Where(s => s is not null).Cast<DepartureSlot>().ToList();
    }

    private static string CacheKey(string id) => "route:" + id;

    public static bool TryParseDate(string? s, out DateOnly? date)
    {
        date = null;
        if (string.IsNullOrWhiteSpace(s)) return true;
        if (DateOnly.TryParseExact(s, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var d)) { date = d; return true; }
        if (DateTimeOffset.TryParse(s, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var dto))
        {
            date = DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(dto, ValhallaMapper.MalaysiaTime).DateTime);
            return true;
        }
        return false;
    }
}
