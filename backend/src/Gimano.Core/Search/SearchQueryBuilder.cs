using System.Text.Json.Nodes;
using Gimano.Core.Contracts;
using Gimano.Core.Domain;

namespace Gimano.Core.Search;

public sealed record SearchQuery(string Q, double? Lat, double? Lng, int Limit = 10, PlaceCategory? Category = null, string? Country = null);

/// <summary>Builds OpenSearch request bodies for the <c>places</c> index.</summary>
public static class SearchQueryBuilder
{
    public const string IndexName = "places";
    public const int MaxLimit = 50;

    /// <summary>
    /// search_as_you_type (bool_prefix over name + shingles) with an exact/phrase boost,
    /// gauss geo decay around the user (relevance biased by proximity, but distant exact matches still appear),
    /// a log popularity boost, and a soft preference for the default country (MY).
    /// </summary>
    public static JsonObject Build(SearchQuery q)
    {
        var limit = Math.Clamp(q.Limit, 1, MaxLimit);
        var text = q.Q.Trim();

        var must = new JsonArray
        {
            new JsonObject
            {
                ["multi_match"] = new JsonObject
                {
                    ["query"] = text,
                    ["type"] = "bool_prefix",
                    ["fields"] = new JsonArray("name", "name._2gram", "name._3gram", "brand^0.8", "address^0.3"),
                    ["operator"] = "and",
                    ["fuzziness"] = text.Length >= 5 ? "AUTO" : "0",
                }
            }
        };

        var should = new JsonArray
        {
            new JsonObject { ["match_phrase_prefix"] = new JsonObject { ["name"] = new JsonObject { ["query"] = text, ["boost"] = 3 } } },
            new JsonObject { ["term"] = new JsonObject { ["name.raw"] = new JsonObject { ["value"] = text.ToLowerInvariant(), ["boost"] = 5 } } },
        };
        if (q.Country is null)
            should.Add(new JsonObject { ["term"] = new JsonObject { ["country"] = new JsonObject { ["value"] = "MY", ["boost"] = 0.5 } } });

        var filter = new JsonArray();
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
            functions.Add(new JsonObject
            {
                ["gauss"] = new JsonObject
                {
                    ["location"] = new JsonObject
                    {
                        ["origin"] = new JsonObject { ["lat"] = lat, ["lon"] = lng },
                        ["offset"] = "1km",
                        ["scale"] = "15km",
                        ["decay"] = 0.4,
                    }
                },
                ["weight"] = 4,
            });
        }

        var body = new JsonObject
        {
            ["size"] = limit,
            ["_source"] = new JsonArray("id", "name", "category", "address", "location", "brand", "country"),
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

    /// <summary>Index definition: search_as_you_type on name, geo_point location.</summary>
    public static JsonObject IndexDefinition() => new()
    {
        ["settings"] = new JsonObject
        {
            ["number_of_shards"] = 1,
            ["number_of_replicas"] = 0,
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
                ["address"] = new JsonObject { ["type"] = "text", ["analyzer"] = "folded" },
                ["category"] = new JsonObject { ["type"] = "keyword" },
                ["country"] = new JsonObject { ["type"] = "keyword" },
                ["location"] = new JsonObject { ["type"] = "geo_point" },
                ["popularity"] = new JsonObject { ["type"] = "float" },
            }
        }
    };

    public static JsonObject ToDocument(Place p) => new()
    {
        ["id"] = p.Id,
        ["name"] = p.Name,
        ["brand"] = p.Brand,
        ["address"] = p.Address,
        ["category"] = GimanoJson.ToWire(p.Category),
        ["country"] = p.Country,
        ["location"] = new JsonObject { ["lat"] = p.Location.Y, ["lon"] = p.Location.X },
        ["popularity"] = p.Popularity,
    };
}
