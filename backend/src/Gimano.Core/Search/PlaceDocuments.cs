using System.Text.Json.Nodes;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Core.Search;

/// <summary>Builds OpenSearch documents (OSM + community places) with their review/photo aggregates.</summary>
public static class PlaceDocuments
{
    public static async Task<Dictionary<string, PlaceAggregates>> AggregatesAsync(GimanoDbContext db, IReadOnlyCollection<string>? placeIds, CancellationToken ct)
    {
        var reviews = db.Reviews.AsNoTracking().Where(r => r.Status == ReviewStatus.Published);
        var photos = db.Photos.AsNoTracking().Where(p => p.Status == PhotoStatus.Published && p.ThumbKey != null);
        if (placeIds is not null)
        {
            reviews = reviews.Where(r => placeIds.Contains(r.PlaceId));
            photos = photos.Where(p => placeIds.Contains(p.PlaceId));
        }
        var ratings = await reviews.GroupBy(r => r.PlaceId)
            .Select(g => new { g.Key, Avg = g.Average(r => (double)r.Rating), Count = g.Count() }).ToListAsync(ct);
        var thumbs = await photos.GroupBy(p => p.PlaceId)
            .Select(g => new { g.Key, Thumb = g.OrderBy(p => p.Id).Select(p => p.ThumbKey).First() }).ToListAsync(ct);

        var result = new Dictionary<string, PlaceAggregates>();
        foreach (var r in ratings) result[r.Key] = new PlaceAggregates(Math.Round(r.Avg, 2), r.Count, null);
        foreach (var t in thumbs)
            result[t.Key] = result.TryGetValue(t.Key, out var a) ? a with { ThumbKey = t.Thumb } : new PlaceAggregates(null, 0, t.Thumb);
        return result;
    }

    /// <summary>Document for one place id (OSM or community), or null if it doesn't exist (→ delete from the index).</summary>
    public static async Task<JsonObject?> ForIdAsync(GimanoDbContext db, string id, CancellationToken ct)
    {
        var agg = (await AggregatesAsync(db, [id], ct)).GetValueOrDefault(id);
        if (CommunityPlace.IsCommunityId(id))
        {
            var c = await db.CommunityPlaces.AsNoTracking().FirstOrDefaultAsync(p => p.Id == id, ct);
            return c is null ? null : SearchQueryBuilder.ToDocument(c, agg);
        }
        var p = await db.Places.AsNoTracking().FirstOrDefaultAsync(x => x.Id == id, ct);
        return p is null ? null : SearchQueryBuilder.ToDocument(p, agg);
    }
}
