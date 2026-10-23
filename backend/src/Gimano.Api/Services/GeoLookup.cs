using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Gimano.Core.Geo;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Api.Services;

/// <summary>PostGIS lookups: nearest street, reverse geocoding, jams and drivers in a bbox.</summary>
public sealed class GeoLookup(GimanoDbContext db, Live.DriverSnapshotStore drivers, TimeProvider clock)
{
    public async Task<string?> StreetNameAsync(GeoPoint p, CancellationToken ct, double radiusM = 50)
    {
        var pt = p.ToPoint();
        return await db.RoadSegments.AsNoTracking()
            .Where(s => (s.Name != null || s.Ref != null) && s.Geom.IsWithinDistance(pt, radiusM))
            .OrderBy(s => s.Geom.Distance(pt))
            .Select(s => s.Name ?? s.Ref)
            .FirstOrDefaultAsync(ct);
    }

    public async Task<ReverseGeocodeResponse> ReverseAsync(GeoPoint p, CancellationToken ct)
    {
        var pt = p.ToPoint();
        var place = await db.Places.AsNoTracking()
            // Unnamed car parks/toilets/ATMs ("Parking") and areas don't name a picked point.
            .Where(x => x.Location.IsWithinDistance(pt, 40)
                        && x.Category != PlaceCategory.Parking && x.Category != PlaceCategory.Toilets && x.Category != PlaceCategory.Area
                        && !(x.Category == PlaceCategory.Atm && x.Name == "ATM"))
            .OrderBy(x => x.Location.Distance(pt))
            .Select(x => new { x.Name, x.Address, x.Location })
            .FirstOrDefaultAsync(ct);
        var street = await StreetNameAsync(p, ct, 150);
        var area = await db.Places.AsNoTracking()
            .Where(x => x.Address != "" && x.Location.IsWithinDistance(pt, 1500))
            .OrderBy(x => x.Location.Distance(pt))
            .Select(x => x.Address)
            .FirstOrDefaultAsync(ct);
        var areaTail = AreaTail(area);

        if (place is not null)
            return new ReverseGeocodeResponse(place.Name, FirstNonEmpty(place.Address, Join(street, areaTail)) ?? "", place.Location.Y, place.Location.X);

        return new ReverseGeocodeResponse(street ?? "Dropped pin", Join(street, areaTail) ?? p.ToString(), p.Lat, p.Lng);
    }

    /// <summary>Drops the street part of an address ("12, Jalan X, 47800 PJ, Selangor" → "47800 PJ, Selangor").</summary>
    internal static string? AreaTail(string? address)
    {
        if (string.IsNullOrWhiteSpace(address)) return null;
        var parts = address.Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);
        var idx = Array.FindIndex(parts, x => x.Length >= 5 && x[..5].All(char.IsDigit));
        var tail = idx >= 0 ? parts[idx..] : parts[^Math.Min(2, parts.Length)..];
        return string.Join(", ", tail);
    }

    private static string? Join(string? a, string? b) =>
        (a, b) switch { (null, null) => null, (null, _) => b, (_, null) => a, _ => $"{a}, {b}" };

    private static string? FirstNonEmpty(params string?[] values) => values.FirstOrDefault(v => !string.IsNullOrWhiteSpace(v));

    public async Task<IReadOnlyList<Jam>> JamsInBBoxAsync(BBox bbox, CancellationToken ct)
    {
        var poly = bbox.ToPolygon();
        var since = clock.GetUtcNow().AddMinutes(-15);
        return await db.Jams.AsNoTracking()
            .Where(j => j.UpdatedAt > since && j.Geom.Intersects(poly))
            .OrderByDescending(j => j.Level)
            .Take(500)
            .ToListAsync(ct);
    }

    /// <summary>Anonymised drivers (≥ 3 per cell, rounded) from the worker's in-memory snapshot. Nothing is read from the database.</summary>
    public Task<IReadOnlyList<DriverDto>> DriversInBBoxAsync(BBox bbox, CancellationToken ct) => Task.FromResult(drivers.InBBox(bbox));
}
