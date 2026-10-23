using System.Globalization;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Gimano.Core.Geo;
using Gimano.Core.Routing;
using Gimano.Core.Traffic;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Caching.Memory;

namespace Gimano.Api.Services;

public sealed class RouteService(
    ValhallaClient valhalla, ReportService reports, GeoLookup geo, GimanoDbContext db,
    IMemoryCache cache, TimeProvider clock, ILogger<RouteService> logger)
{
    private static readonly TimeSpan CacheFor = TimeSpan.FromHours(3);

    public async Task<IReadOnlyList<RouteDto>> RouteAsync(RouteRequest req, Guid? userId, CancellationToken ct, double? originHeading = null)
    {
        var response = await valhalla.RouteAsync(ValhallaMapper.BuildRouteRequest(req, originHeading), ct);
        var candidates = ValhallaMapper.MapResponse(response);
        if (candidates.Count == 0) throw new NoRouteException("No route found.");

        var bbox = candidates.Select(c => BBox.Of(c.Points)).Aggregate((a, b) =>
            new BBox(Math.Min(a.MinLng, b.MinLng), Math.Min(a.MinLat, b.MinLat), Math.Max(a.MaxLng, b.MaxLng), Math.Max(a.MaxLat, b.MaxLat)))
            .Expand(200);

        var activeReports = await reports.ActiveInBBoxAsync(bbox, ct);
        var jams = (await geo.JamsInBBoxAsync(bbox, ct))
            .Select(j => new JamLine(j.Level, j.SpeedKmh, GeoMath.FromLineString(j.Geom))).ToList();

        var routes = RouteDecorator.Decorate(candidates, activeReports, jams, () => Guid.CreateVersion7().ToString("N"));
        foreach (var r in routes) cache.Set(CacheKey(r.Id), req, CacheFor);

        if (userId is { } uid && originHeading is null)
        {
            try { await RecordRecentAsync(uid, req.Destination, ct); }
            catch (Exception ex) { logger.LogWarning(ex, "Could not record recent destination"); }
        }
        return routes;
    }

    public async Task<IReadOnlyList<RouteDto>> RerouteAsync(string routeId, RerouteRequest r, Guid? userId, CancellationToken ct)
    {
        if (!cache.TryGetValue(CacheKey(routeId), out RouteRequest? original) || original is null)
            throw new NotFoundException($"Route {routeId} is unknown or expired; request a new route.");
        // Waypoints already driven past can't be detected reliably; reroute straight to the destination.
        var req = original with { Origin = new LatLng(r.Lat, r.Lng), Waypoints = [], DepartAt = null };
        return await RouteAsync(req, userId, ct, r.Heading);
    }

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

    private async Task RecordRecentAsync(Guid userId, LatLng dest, CancellationToken ct)
    {
        string name, address;
        var point = new GeoPoint(dest.Lat, dest.Lng);
        Place? place = dest.PlaceId is null ? null : await db.Places.AsNoTracking().FirstOrDefaultAsync(p => p.Id == dest.PlaceId, ct);
        if (place is not null) (name, address) = (place.Name, place.Address);
        else
        {
            var rev = await geo.ReverseAsync(point, ct);
            (name, address) = (rev.Name, rev.Address);
        }

        var pt = point.ToPoint();
        var existing = await db.Recents
            .Where(r => r.UserId == userId && (dest.PlaceId != null ? r.PlaceId == dest.PlaceId : r.Location.IsWithinDistance(pt, 30)))
            .FirstOrDefaultAsync(ct);
        var now = clock.GetUtcNow();
        if (existing is not null) existing.LastUsedAt = now;
        else db.Recents.Add(new RecentDestination { UserId = userId, Name = name, Address = address, Location = pt, PlaceId = dest.PlaceId, LastUsedAt = now });
        await db.SaveChangesAsync(ct);

        // Keep only the last 20.
        var stale = await db.Recents.Where(r => r.UserId == userId).OrderByDescending(r => r.LastUsedAt).Skip(20).ToListAsync(ct);
        if (stale.Count > 0)
        {
            db.Recents.RemoveRange(stale);
            await db.SaveChangesAsync(ct);
        }
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
