using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Gimano.Core.Contracts;
using Gimano.Core.Domain;
using Microsoft.Extensions.Logging;
using OpenSearch.Net;

namespace Gimano.Core.Search;

public sealed record PlaceHit(string Id, string Name, PlaceCategory Category, string Address, double Lat, double Lng, string? Brand, double Score,
    PlaceSource Source = PlaceSource.Osm, PlaceStatus? Status = null, double? Rating = null, int ReviewCount = 0, string? ThumbKey = null);

/// <summary>Thin wrapper over the OpenSearch low-level client for the <c>places</c> index.</summary>
public sealed class PlaceSearchIndex(IOpenSearchLowLevelClient client, ILogger<PlaceSearchIndex> logger)
{
    public const string Index = SearchQueryBuilder.IndexName;

    public async Task<bool> PingAsync(CancellationToken ct = default)
    {
        var r = await client.PingAsync<StringResponse>(ctx: ct);
        return r.Success;
    }

    public async Task EnsureIndexAsync(bool recreate = false, CancellationToken ct = default)
    {
        var exists = await client.Indices.ExistsAsync<StringResponse>(Index, ctx: ct);
        if (exists.HttpStatusCode == 200 && recreate)
        {
            await client.Indices.DeleteAsync<StringResponse>(Index, ctx: ct);
            exists = await client.Indices.ExistsAsync<StringResponse>(Index, ctx: ct);
        }
        if (exists.HttpStatusCode == 200) return;

        var r = await client.Indices.CreateAsync<StringResponse>(Index, PostData.String(SearchQueryBuilder.IndexDefinition().ToJsonString()), ctx: ct);
        if (!r.Success) throw new InvalidOperationException($"Creating index '{Index}' failed: {r.DebugInformation}");
        logger.LogInformation("Created OpenSearch index {Index}", Index);
    }

    public Task<int> BulkIndexAsync(IEnumerable<Place> places, CancellationToken ct = default) =>
        BulkIndexAsync(places.Select(p => SearchQueryBuilder.ToDocument(p)), ct);

    /// <summary>Indexes prepared documents (each must have an "id").</summary>
    public async Task<int> BulkIndexAsync(IEnumerable<JsonObject> docs, CancellationToken ct = default)
    {
        var sb = new StringBuilder();
        var n = 0;
        foreach (var d in docs)
        {
            sb.Append("{\"index\":{\"_index\":\"").Append(Index).Append("\",\"_id\":").Append(JsonSerializer.Serialize(d["id"]!.GetValue<string>())).Append("}}\n");
            sb.Append(d.ToJsonString()).Append('\n');
            n++;
        }
        if (n == 0) return 0;
        var r = await client.BulkAsync<StringResponse>(PostData.String(sb.ToString()), ctx: ct);
        if (!r.Success) throw new InvalidOperationException($"Bulk index failed: {r.DebugInformation}");
        if (r.Body.Contains("\"errors\":true", StringComparison.Ordinal))
            logger.LogWarning("Bulk index reported {Count} item errors", System.Text.RegularExpressions.Regex.Matches(r.Body, "\"error\":").Count);
        return n;
    }

    public async Task DeleteAsync(string id, CancellationToken ct = default) =>
        await client.DeleteAsync<StringResponse>(Index, id, ctx: ct);

    public async Task RefreshAsync(CancellationToken ct = default) =>
        await client.Indices.RefreshAsync<StringResponse>(Index, ctx: ct);

    public async Task<IReadOnlyList<PlaceHit>> SearchAsync(SearchQuery q, CancellationToken ct = default)
    {
        var body = SearchQueryBuilder.Build(q).ToJsonString();
        var r = await client.SearchAsync<StringResponse>(Index, PostData.String(body), ctx: ct);
        if (!r.Success) throw new SearchUnavailableException($"OpenSearch query failed: {r.HttpStatusCode} {r.OriginalException?.Message}");
        return ParseHits(r.Body);
    }

    public static IReadOnlyList<PlaceHit> ParseHits(string json)
    {
        var root = JsonNode.Parse(json)!;
        var hits = root["hits"]?["hits"]?.AsArray() ?? [];
        var result = new List<PlaceHit>(hits.Count);
        foreach (var h in hits)
        {
            var s = h!["_source"]!;
            GimanoJson.TryParseWire<PlaceCategory>(s["category"]?.GetValue<string>(), out var cat);
            var source = s["source"]?.GetValue<string>() == "community" ? PlaceSource.Community : PlaceSource.Osm;
            PlaceStatus? status = GimanoJson.TryParseWire<PlaceStatus>(s["status"]?.GetValue<string>(), out var st) ? st : null;
            result.Add(new PlaceHit(
                s["id"]!.GetValue<string>(),
                s["name"]!.GetValue<string>(),
                cat,
                s["address"]?.GetValue<string>() ?? "",
                s["location"]!["lat"]!.GetValue<double>(),
                s["location"]!["lon"]!.GetValue<double>(),
                s["brand"]?.GetValue<string>(),
                h["_score"]?.GetValue<double>() ?? 0,
                source, status,
                s["rating"]?.GetValue<double>(),
                s["reviewCount"]?.GetValue<int>() ?? 0,
                s["thumbKey"]?.GetValue<string>()));
        }
        return result;
    }
}

public sealed class SearchUnavailableException(string message) : Exception(message);
