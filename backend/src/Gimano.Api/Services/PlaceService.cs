using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Gimano.Core.Geo;
using Gimano.Core.Places;
using Gimano.Core.Routing;
using Gimano.Core.Search;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Api.Services;

public sealed class PlaceService(GimanoDbContext db, PlaceSearchIndex index, TimeProvider clock, ILogger<PlaceService> logger)
{
    public async Task<IReadOnlyList<PlaceSummary>> SearchAsync(SearchQuery q, CancellationToken ct)
    {
        IReadOnlyList<PlaceHit> hits;
        try
        {
            hits = await index.SearchAsync(q, ct);
        }
        catch (Exception ex) when (ex is SearchUnavailableException or HttpRequestException)
        {
            logger.LogWarning("OpenSearch unavailable ({Message}); falling back to Postgres trigram search", ex.Message);
            hits = await FallbackSearchAsync(q, ct);
        }
        return hits.Select(h => new PlaceSummary(h.Id, h.Name, h.Category, h.Address, h.Lat, h.Lng, Distance(q.Lat, q.Lng, h.Lat, h.Lng), h.Brand)).ToList();
    }

    private async Task<IReadOnlyList<PlaceHit>> FallbackSearchAsync(SearchQuery q, CancellationToken ct)
    {
        var pattern = "%" + q.Q.Trim().Replace("%", "").Replace("_", "") + "%";
        var query = db.Places.AsNoTracking().Where(p => EF.Functions.ILike(p.Name, pattern) || (p.Brand != null && EF.Functions.ILike(p.Brand, pattern)));
        if (q.Category is { } cat) query = query.Where(p => p.Category == cat);
        if (q.Country is { } c) query = query.Where(p => p.Country == c);
        if (q.Lat is { } lat && q.Lng is { } lng)
        {
            var pt = new GeoPoint(lat, lng).ToPoint();
            query = query.OrderBy(p => p.Location.Distance(pt));
        }
        else query = query.OrderByDescending(p => p.Popularity);
        var rows = await query.Take(Math.Clamp(q.Limit, 1, SearchQueryBuilder.MaxLimit)).ToListAsync(ct);
        return rows.Select(p => new PlaceHit(p.Id, p.Name, p.Category, p.Address, p.Location.Y, p.Location.X, p.Brand, 0)).ToList();
    }

    public async Task<IReadOnlyList<PlaceSummary>> NearbyAsync(GeoPoint at, PlaceCategory? category, double radiusM, int limit, CancellationToken ct)
    {
        var pt = at.ToPoint();
        var query = db.Places.AsNoTracking().Where(p => p.Location.IsWithinDistance(pt, radiusM));
        if (category is { } c) query = query.Where(p => p.Category == c);
        var rows = await query.OrderBy(p => p.Location.Distance(pt)).Take(limit).ToListAsync(ct);
        return rows.Select(p => ToSummary(p, at.Lat, at.Lng)).ToList();
    }

    public async Task<PlaceDto?> GetAsync(string id, double? lat, double? lng, CancellationToken ct)
    {
        var p = await db.Places.AsNoTracking().FirstOrDefaultAsync(x => x.Id == id, ct);
        if (p is null) return null;
        var local = TimeZoneInfo.ConvertTime(clock.GetUtcNow(), ValhallaMapper.MalaysiaTime).DateTime;
        return new PlaceDto(p.Id, p.Name, p.Category, p.Address, p.Location.Y, p.Location.X, Distance(lat, lng, p.Location.Y, p.Location.X), p.Brand,
            p.Phone, p.Website, p.OpeningHours, OpeningHours.IsOpen(p.OpeningHours, local), p.Amenities, p.Tags, p.OsmType, p.OsmId);
    }

    public static PlaceSummary ToSummary(Place p, double? lat, double? lng) =>
        new(p.Id, p.Name, p.Category, p.Address, p.Location.Y, p.Location.X, Distance(lat, lng, p.Location.Y, p.Location.X), p.Brand);

    private static double? Distance(double? lat, double? lng, double pLat, double pLng) =>
        lat is { } a && lng is { } b ? Math.Round(GeoMath.HaversineM(a, b, pLat, pLng)) : null;
}
