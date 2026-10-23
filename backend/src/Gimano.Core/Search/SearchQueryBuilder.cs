using System.Text.Json.Nodes;
using Gimano.Core.Contracts;
using Gimano.Core.Domain;

namespace Gimano.Core.Search;

/// <param name="Viewer">The caller (if signed in): their own pending community places are included.</param>
public sealed record SearchQuery(string Q, double? Lat, double? Lng, int Limit = 10, PlaceCategory? Category = null, string? Country = null, Guid? Viewer = null);

/// <summary>Per-place review/photo aggregates carried on search documents.</summary>
public sealed record PlaceAggregates(double? Rating, int ReviewCount, string? ThumbKey);

/// <summary>Builds OpenSearch request bodies for the <c>places</c> index.</summary>
public static class SearchQueryBuilder
{
    /// <summary>Base name; the effective alias is <c>OpenSearch:IndexPrefix</c> + this (e.g. "gimano-places").</summary>
    public const string IndexName = "places";
    public const int MaxLimit = 50;
    public const double ExactNameBoost = 40;
    public const double NamePrefixBoost = 15;
    public const double AliasBoost = 10;
    public const double AreaBoost = 6;
    public const double DuplicateRadiusM = 50;

    private static JsonObject Gauss(double lat, double lng, string offset, string scale, double decay, double weight) => new()
    {
        ["gauss"] = new JsonObject
        {
            ["location"] = new JsonObject
            {
                ["origin"] = new JsonObject { ["lat"] = lat, ["lon"] = lng },
                ["offset"] = offset, ["scale"] = scale, ["decay"] = decay,
            }
        },
        ["weight"] = weight,
    };

    /// <summary>
    /// search_as_you_type (bool_prefix over name + shingles) with an exact/phrase boost,
    /// gauss geo decay around the user (relevance biased by proximity, but distant exact matches still appear),
    /// a log popularity boost, and a soft preference for the default country (MY).
    /// </summary>
    public static JsonObject Build(SearchQuery q)
    {
        var limit = Math.Clamp(q.Limit, 1, MaxLimit);
        var text = q.Q.Trim();

        // Prefix matching for as-you-type, OR a fuzzy full-word match so typos in the last/only word still hit
        // (bool_prefix never applies fuzziness to its final prefix term).
        var textMatch = new JsonArray
        {
            new JsonObject
            {
                ["multi_match"] = new JsonObject
                {
                    ["query"] = text,
                    ["type"] = "bool_prefix",
                    ["fields"] = new JsonArray("name", "name._2gram", "name._3gram", "brand^0.8", "aliases^0.9", "address^0.3"),
                    ["operator"] = "and",
                    ["fuzziness"] = text.Length >= 5 ? "AUTO" : "0",
                }
            },
        };
        if (text.Length >= 5)
            textMatch.Add(new JsonObject
            {
                ["multi_match"] = new JsonObject
                {
                    ["query"] = text,
                    ["fields"] = new JsonArray("name^0.8", "brand^0.6"),
                    ["operator"] = "and",
                    ["fuzziness"] = "AUTO",
                    ["prefix_length"] = 1,
                }
            });
        var must = new JsonArray
        {
            new JsonObject { ["bool"] = new JsonObject { ["should"] = textMatch, ["minimum_should_match"] = 1 } },
        };

        // Ranking: the whole name equal to / starting with the query, an alias hit, and areas (cities, towns…) beat long names that
        // merely contain the term somewhere (e.g. "Jabatan … Kuala Lumpur & Putrajaya" must not beat Putrajaya itself).
        var lower = text.ToLowerInvariant();
        var should = new JsonArray
        {
            new JsonObject { ["term"] = new JsonObject { ["name.raw"] = new JsonObject { ["value"] = lower, ["boost"] = ExactNameBoost } } },
            new JsonObject { ["prefix"] = new JsonObject { ["name.raw"] = new JsonObject { ["value"] = lower, ["boost"] = NamePrefixBoost } } },
            new JsonObject { ["match_phrase_prefix"] = new JsonObject { ["name"] = new JsonObject { ["query"] = text, ["boost"] = 3 } } },
            new JsonObject { ["match"] = new JsonObject { ["aliases"] = new JsonObject { ["query"] = text, ["boost"] = AliasBoost } } },
            new JsonObject { ["term"] = new JsonObject { ["category"] = new JsonObject { ["value"] = "area", ["boost"] = AreaBoost } } },
        };
        if (q.Country is null)
            should.Add(new JsonObject { ["term"] = new JsonObject { ["country"] = new JsonObject { ["value"] = "MY", ["boost"] = 0.5 } } });

        var filter = new JsonArray { Visibility(q.Viewer) };
        if (q.Category is { } cat)
            filter.Add(new JsonObject { ["term"] = new JsonObject { ["category"] = GimanoJson.ToWire(cat) } });
        if (q.Country is { } country)
            filter.Add(new JsonObject { ["term"] = new JsonObject { ["country"] = country.ToUpperInvariant() } });

        var functions = new JsonArray
        {
            new JsonObject
            {
                ["field_value_factor"] = new JsonObject
                {
                    ["field"] = "popularity", ["modifier"] = "log2p", ["factor"] = 1, ["missing"] = 1,
                }
            }
        };
        if (q.Lat is { } lat && q.Lng is { } lng)
        {
            // Two distance terms: a broad one (the metro area matters) and a local one (among equal names, nearest first).
            // Both are additive with popularity, so a strong name match far away still beats a weak one nearby.
            functions.Add(Gauss(lat, lng, "500m", "20km", 0.5, 2.5));
            functions.Add(Gauss(lat, lng, "0km", "3km", 0.5, 1.5));
        }

        var body = new JsonObject
        {
            // Over-fetch so near-duplicates (same name within 50 m) can be collapsed and still fill the page.
            ["size"] = Math.Min(MaxLimit, limit * 2 + 5),
            ["_source"] = new JsonArray("id", "name", "category", "address", "location", "brand", "country", "source", "status", "rating", "reviewCount", "thumbKey"),
            ["track_total_hits"] = false,
            ["query"] = new JsonObject
            {
                ["function_score"] = new JsonObject
                {
                    ["query"] = new JsonObject
                    {
                        ["bool"] = new JsonObject { ["must"] = must, ["should"] = should, ["filter"] = filter }
                    },
                    ["functions"] = functions,
                    ["score_mode"] = "sum",
                    ["boost_mode"] = "multiply",
                }
            },
        };
        return body;
    }

    /// <summary>
    /// Visibility (docs/api.md): OSM places, published community places, and the viewer's own pending places.
    /// Hidden/closed community places never match.
    /// </summary>
    public static JsonObject Visibility(Guid? viewer)
    {
        var should = new JsonArray
        {
            new JsonObject { ["term"] = new JsonObject { ["source"] = "osm" } },
            new JsonObject { ["term"] = new JsonObject { ["status"] = "published" } },
        };
        if (viewer is { } v)
            should.Add(new JsonObject
            {
                ["bool"] = new JsonObject
                {
                    ["filter"] = new JsonArray(
                        new JsonObject { ["term"] = new JsonObject { ["status"] = "pending" } },
                        new JsonObject { ["term"] = new JsonObject { ["ownerId"] = v.ToString("D") } }),
                }
            });
        return new JsonObject { ["bool"] = new JsonObject { ["should"] = should, ["minimum_should_match"] = 1 } };
    }

    /// <summary>Index definition: search_as_you_type on name, geo_point location.</summary>
    /// <param name="replicas">0 on single-node clusters (otherwise the index stays yellow); OpenSearch:Replicas.</param>
    public static JsonObject IndexDefinition(int replicas = 0) => new()
    {
        ["settings"] = new JsonObject
        {
            ["number_of_shards"] = 1,
            ["number_of_replicas"] = replicas,
            ["analysis"] = new JsonObject
            {
                ["normalizer"] = new JsonObject
                {
                    ["lowercase_ascii"] = new JsonObject
                    {
                        ["type"] = "custom",
                        ["filter"] = new JsonArray("lowercase", "asciifolding"),
                    }
                },
                ["analyzer"] = new JsonObject
                {
                    ["folded"] = new JsonObject
                    {
                        ["type"] = "custom",
                        ["tokenizer"] = "standard",
                        ["filter"] = new JsonArray("lowercase", "asciifolding"),
                    }
                }
            }
        },
        ["mappings"] = new JsonObject
        {
            ["properties"] = new JsonObject
            {
                ["id"] = new JsonObject { ["type"] = "keyword" },
                ["name"] = new JsonObject
                {
                    ["type"] = "search_as_you_type",
                    ["analyzer"] = "folded",
                    ["fields"] = new JsonObject
                    {
                        ["raw"] = new JsonObject { ["type"] = "keyword", ["normalizer"] = "lowercase_ascii" }
                    }
                },
                ["brand"] = new JsonObject { ["type"] = "text", ["analyzer"] = "folded" },
                ["aliases"] = new JsonObject { ["type"] = "text", ["analyzer"] = "folded" },
                ["address"] = new JsonObject { ["type"] = "text", ["analyzer"] = "folded" },
                ["category"] = new JsonObject { ["type"] = "keyword" },
                ["country"] = new JsonObject { ["type"] = "keyword" },
                ["location"] = new JsonObject { ["type"] = "geo_point" },
                ["popularity"] = new JsonObject { ["type"] = "float" },
                ["source"] = new JsonObject { ["type"] = "keyword" },
                ["status"] = new JsonObject { ["type"] = "keyword" },
                ["ownerId"] = new JsonObject { ["type"] = "keyword" },
                ["rating"] = new JsonObject { ["type"] = "float" },
                ["reviewCount"] = new JsonObject { ["type"] = "integer" },
                ["thumbKey"] = new JsonObject { ["type"] = "keyword", ["index"] = false },
            }
        }
    };

    public static JsonObject ToDocument(Place p, PlaceAggregates? agg = null) => new()
    {
        ["id"] = p.Id,
        ["name"] = p.Name,
        ["brand"] = p.Brand,
        ["aliases"] = SearchAliases.For(p.Name, p.Brand),
        ["address"] = p.Address,
        ["category"] = GimanoJson.ToWire(p.Category),
        ["country"] = p.Country,
        ["location"] = new JsonObject { ["lat"] = p.Location.Y, ["lon"] = p.Location.X },
        ["popularity"] = p.Popularity + Boost(agg),
        ["source"] = "osm",
        ["rating"] = agg?.Rating,
        ["reviewCount"] = agg?.ReviewCount ?? 0,
        ["thumbKey"] = agg?.ThumbKey,
    };

    public static JsonObject ToDocument(CommunityPlace p, PlaceAggregates? agg = null) => new()
    {
        ["id"] = p.Id,
        ["name"] = p.Name,
        ["brand"] = null,
        ["address"] = p.Address,
        ["category"] = GimanoJson.ToWire(p.Category),
        ["country"] = p.Country,
        ["location"] = new JsonObject { ["lat"] = p.Location.Y, ["lon"] = p.Location.X },
        ["popularity"] = 1 + Boost(agg),
        ["source"] = "community",
        ["status"] = GimanoJson.ToWire(p.Status),
        ["ownerId"] = p.OwnerId?.ToString("D"),
        ["rating"] = agg?.Rating,
        ["reviewCount"] = agg?.ReviewCount ?? 0,
        ["thumbKey"] = agg?.ThumbKey,
    };

    /// <summary>Well-reviewed places rank a little higher.</summary>
    private static double Boost(PlaceAggregates? a) => a is { ReviewCount: >= 3, Rating: { } r } ? Math.Round((r - 3) * 0.5 + Math.Log10(a.ReviewCount), 2) : 0;
}

public static class SearchResults
{
    /// <summary>Collapses near-duplicates (same normalised name within 50 m — e.g. an OSM node and its building), keeping the better-ranked one.</summary>
    public static List<T> Dedupe<T>(IEnumerable<T> ranked, Func<T, string> name, Func<T, (double Lat, double Lng)> at, int take)
    {
        var kept = new List<T>();
        foreach (var r in ranked)
        {
            var n = Norm(name(r)); var p = at(r);
            if (kept.Any(k => Norm(name(k)) == n && Geo.GeoMath.HaversineM(p.Lat, p.Lng, at(k).Lat, at(k).Lng) <= SearchQueryBuilder.DuplicateRadiusM)) continue;
            kept.Add(r);
            if (kept.Count == take) break;
        }
        return kept;
    }

    private static string Norm(string s) => string.Join(' ', s.ToLowerInvariant().Split(' ', StringSplitOptions.RemoveEmptyEntries));
}
