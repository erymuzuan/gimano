using Gimano.Core.Contracts;
using Gimano.Core.Domain;
using Gimano.Core.Geo;
using Gimano.Core.Infrastructure;
using Gimano.Core.Search;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using OpenSearch.Net;

namespace Gimano.Tests;

/// <summary>
/// Runs against a real OpenSearch (2.19 or 3.x) when GIMANO_OS_URL is set, e.g.
///   GIMANO_OS_URL=https://localhost:9200 GIMANO_OS_USER=gimano GIMANO_OS_PASSWORD=… GIMANO_OS_INSECURE=true dotnet test --filter OpenSearchIntegration
/// The user only needs index permissions on "gimano-*" (+ the cluster-level bulk action). Skipped otherwise.
/// </summary>
public class OpenSearchIntegrationTests
{
    private static PlaceSearchIndex? Create(string prefix, out IOpenSearchLowLevelClient client)
    {
        client = null!;
        var url = Environment.GetEnvironmentVariable("GIMANO_OS_URL");
        if (string.IsNullOrEmpty(url)) return null;
        var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["OpenSearch:Url"] = url,
            ["OpenSearch:User"] = Environment.GetEnvironmentVariable("GIMANO_OS_USER"),
            ["OpenSearch:Password"] = Environment.GetEnvironmentVariable("GIMANO_OS_PASSWORD"),
            ["OpenSearch:AllowInvalidCertificate"] = Environment.GetEnvironmentVariable("GIMANO_OS_INSECURE") ?? "false",
            ["OpenSearch:IndexPrefix"] = prefix,
            ["OpenSearch:TimeoutSeconds"] = "60",
        }).Build();
        var sp = new ServiceCollection().AddLogging(b => b.AddConsole()).AddGimanoSearch(config).BuildServiceProvider();
        client = sp.GetRequiredService<IOpenSearchLowLevelClient>();
        return sp.GetRequiredService<PlaceSearchIndex>();
    }

    private static Place Osm(string id, string name, double lat, double lng, PlaceCategory cat = PlaceCategory.Fuel) => new()
    {
        Id = id, Name = name, Brand = name.Split(' ')[0], Category = cat, Location = new GeoPoint(lat, lng).ToPoint(), OsmType = "node", Popularity = 2,
    };

    [Fact]
    public async Task Prefixed_alias_bulk_search_visibility_and_rebuild_against_a_real_cluster()
    {
        var prefix = $"gimano-it{Guid.NewGuid():N}"[..20] + "-";
        var index = Create(prefix, out var client);
        if (index is null) { Assert.Skip("GIMANO_OS_URL not set"); return; }
        try
        {
            Assert.True(await index.PingAsync(), "ping (HEAD on the prefixed alias) should succeed even before the index exists");
            await index.EnsureIndexAsync();
            Assert.Equal(prefix + "places", index.Index);

            var owner = Guid.NewGuid();
            var docs = new[]
            {
                SearchQueryBuilder.ToDocument(Osm("n1", "Petronas Damansara", 3.1300, 101.6300)),
                SearchQueryBuilder.ToDocument(Osm("n2", "Petronas Kepong", 3.2100, 101.6400)),
                SearchQueryBuilder.ToDocument(Osm("n3", "Shell SS2", 3.1190, 101.6210)),
                SearchQueryBuilder.ToDocument(new CommunityPlace { Id = "c1", Name = "Petronas Kiosk Komuniti", Location = new GeoPoint(3.12, 101.62).ToPoint(),
                    Status = PlaceStatus.Pending, OwnerId = owner }),
            };
            Assert.Equal(4, await index.BulkIndexAsync(docs));
            await index.RefreshAsync();

            // search_as_you_type bool_prefix + gauss geo decay + visibility filter
            var near = await index.SearchAsync(new SearchQuery("petr", 3.11, 101.62));
            Assert.Equal(["n1", "n2"], near.Select(h => h.Id).ToArray());   // nearest first, pending community place hidden
            var asOwner = await index.SearchAsync(new SearchQuery("petr", 3.11, 101.62, Viewer: owner));
            Assert.Contains(asOwner, h => h.Id == "c1" && h.Status == PlaceStatus.Pending && h.Source == PlaceSource.Community);
            var fuzzy = await index.SearchAsync(new SearchQuery("petronaz", null, null));
            Assert.NotEmpty(fuzzy);
            var cat = await index.SearchAsync(new SearchQuery("shell", 3.11, 101.62, Category: PlaceCategory.Fuel));
            Assert.Equal("n3", Assert.Single(cat).Id);

            // Rebuild into a new physical index and swap the alias atomically.
            var physical = await index.BeginRebuildAsync();
            await index.BulkIndexAsync([SearchQueryBuilder.ToDocument(Osm("n9", "Petronas Baru", 3.12, 101.62))], physical);
            await index.CompleteRebuildAsync(physical);
            var after = await index.SearchAsync(new SearchQuery("petronas", 3.12, 101.62));
            Assert.Equal("n9", Assert.Single(after).Id);

            await index.DeleteAsync("n9");
            await index.RefreshAsync();
            Assert.Empty(await index.SearchAsync(new SearchQuery("petronas", 3.12, 101.62)));
        }
        finally
        {
            // Delete by exact name (OpenSearch 3 defaults to action.destructive_requires_name=true).
            var all = await client.Indices.GetAsync<StringResponse>(prefix + "*");
            if (all.Success && System.Text.Json.Nodes.JsonNode.Parse(all.Body) is System.Text.Json.Nodes.JsonObject o)
                foreach (var (name, _) in o) await client.Indices.DeleteAsync<StringResponse>(name);
        }
    }
}
