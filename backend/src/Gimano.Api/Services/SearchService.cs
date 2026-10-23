using System.Globalization;
using System.Text.Json.Nodes;
using Gimano.Core.Community;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Geo;
using Gimano.Core.Search;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Api.Services;

/// <param name="Lat">The user (distances are measured from here).</param>
/// <param name="NearLat">Map centre (<c>near=</c>): ranking origin when given.</param>
public sealed record SearchRequest(string Q, double? Lat, double? Lng, double? NearLat = null, double? NearLng = null, int Limit = 10,
    PlaceCategory? Category = null, string? Country = null, Guid? Viewer = null, bool SortNearest = false, string Lang = L10n.En,
    bool Exact = false);

/// <summary>
/// The index-only part of "Finding places" (docs/api.md): postcode / street address → category words → text (+ brand near an
/// area) → near-duplicates collapsed → chains nearest-first → chain groups; and suggestions for an empty result.
/// </summary>
public sealed class SearchPipeline(PlaceSearchIndex index, Func<AliasTable> aliases, TimeProvider? clock = null)
{
    private DateTime LocalNow => TimeZoneInfo.ConvertTime((clock ?? TimeProvider.System).GetUtcNow(), Core.Routing.ValhallaMapper.MalaysiaTime).DateTime;

    public const double ChainRadiusM = 10_000;
    private static readonly double[] CategoryRadiiM = [5_000, 15_000, 50_000];

    /// <param name="LiteralTexts">For text searches: the normalised query (and alias rewrites) that results must literally contain
    /// to count as "as typed"; otherwise the response carries correctedQuery.</param>
    public sealed record Ranked(IReadOnlyList<PlaceHit> Hits, SearchInterpretation Interpretation, double? OriginLat, double? OriginLng, bool KeepOrder = false,
        IReadOnlyList<string>? LiteralTexts = null);

    /// <summary>Runs the steps and returns finished summaries (distances from the user).</summary>
    public async Task<(List<PlaceSummary> Results, SearchInterpretation Interpretation, string? CorrectedQuery)> RunAsync(
        SearchRequest r, ParsedQuery? parsed, AreaLocator areas, CancellationToken ct)
    {
        var (oLat, oLng) = r.NearLat is not null && r.NearLng is not null ? (r.NearLat, r.NearLng) : (r.Lat, r.Lng);
        var (uLat, uLng) = r.Lat is not null ? (r.Lat, r.Lng) : (oLat, oLng);
        var limit = Math.Clamp(r.Limit, 1, SearchQueryBuilder.MaxLimit);
        var ranked = await ResolveAsync(r, parsed, areas, oLat, oLng, limit, ct);
        var results = await FinishAsync(ranked, r, uLat, uLng, limit, ct);
        return (results, ranked.Interpretation, CorrectedFor(ranked.LiteralTexts, results));
    }

    public const double MaxCorrectionDistanceM = 3_000;

    /// <summary>Penalty per rank of a secondary category: "surau" lists suraus first, a masjid only when ≥ 2 km nearer.</summary>
    public const double SecondaryCategoryPenaltyM = 2_000;

    /// <summary>"surau" → surau first, masjid as secondary (and vice versa for "masjid").</summary>
    internal static double CategoryOrder(double distanceM, PlaceCategory category, IReadOnlyList<PlaceCategory> cats)
    {
        var i = cats.ToList().IndexOf(category);
        return distanceM + (i < 0 ? 2 : i) * SecondaryCategoryPenaltyM;
    }

    /// <summary>For "surau", OSM suraus named "Madrasah …" (often religious schools) come after the other suraus nearby.</summary>
    internal static double SurauOrder(double order, string name, PlaceCategory category, IReadOnlyList<PlaceCategory> cats) =>
        cats.Count > 0 && cats[0] == PlaceCategory.Surau && category == PlaceCategory.Surau
            && name.StartsWith("madrasah", StringComparison.OrdinalIgnoreCase) ? order + 1_500 : order;

    /// <summary>
    /// Results that came only through typo tolerance are labelled: when none of the top 3 literally contains the query (after
    /// normalisation and aliases), correctedQuery = the top result's brand or name ("famly mart" → "FamilyMart").
    /// </summary>
    internal static string? CorrectedFor(IReadOnlyList<string>? literalTexts, IReadOnlyList<PlaceSummary> results)
    {
        if (literalTexts is null || results.Count == 0) return null;
        if (results.Take(3).Any(s => literalTexts.Any(t => LiterallyMatches(t, s.Name, s.Brand, s.Address)))) return null;
        return results[0].Brand ?? results[0].Name;
    }

    /// <summary>The spacing-free query is inside the name/brand, or every query word starts some word of the name, brand or address.</summary>
    internal static bool LiterallyMatches(string text, string name, string? brand, string? address)
    {
        var compact = TextNormalizer.Compact(text);
        if (compact.Length == 0) return true;
        if (TextNormalizer.Compact(name).Contains(compact, StringComparison.Ordinal) || TextNormalizer.Compact(brand).Contains(compact, StringComparison.Ordinal)) return true;
        var hay = TextNormalizer.Tokens(name).Concat(TextNormalizer.Tokens(brand)).Concat(TextNormalizer.Tokens(address)).ToList();
        return TextNormalizer.Tokens(text).All(t => hay.Any(h => h.StartsWith(t, StringComparison.Ordinal)));
    }

    private async Task<Ranked> ResolveAsync(SearchRequest r, ParsedQuery? parsed, AreaLocator areas, double? oLat, double? oLng, int limit, CancellationToken ct)
    {
        var norm = TextNormalizer.Normalize(r.Q);
        var table = aliases();

        switch (parsed)
        {
            case PostcodeQuery pc:
            {
                var hits = await index.SearchAsync(SearchQueryBuilder.BuildPostcode(pc.Postcode), ct);
                if (hits.Count > 0) return new Ranked(hits, new SearchInterpretation("postcode", norm), oLat, oLng);
                break;
            }
            case StreetQuery sq:
            {
                var key = QueryParser.StreetKey(sq.Street);
                var baseQ = new SearchQuery(r.Q, oLat, oLng, limit, Country: r.Country);
                var found = new List<PlaceHit>();
                if (sq.HouseNumber is { } hn)
                    found.AddRange(await index.SearchAsync(SearchQueryBuilder.BuildStreet(baseQ with { Scope = SearchScope.Addresses, HouseNumber = hn }, key), ct));
                var streets = await index.SearchAsync(SearchQueryBuilder.BuildStreet(baseQ with { Scope = SearchScope.Streets }, key), ct);
                var kind = found.Count > 0 ? "address" : "street";
                found.AddRange(streets);
                if (found.Count > 0) return new Ranked(found, new SearchInterpretation(kind, norm), oLat, oLng, KeepOrder: true);
                break;
            }
        }

        // Step 5: the whole query is a category word ("minyak", "stesen minyak", "tandas", "surau", "atm").
        if (r.Category is null && table.Category(norm) is { Length: > 0 } cats)
            return new Ranked(await NearestOfAsync(cats, oLat, oLng, r, limit, ct), new SearchInterpretation("category", norm, cats), oLat, oLng);

        var variants = table.Rewrites(norm);
        var textQ = new SearchQuery(r.Q, oLat, oLng, limit, r.Category, r.Country, r.Viewer, variants, SortNearest: r.SortNearest);
        var general = await index.SearchAsync(textQ, ct);
        var keys = variants.Append(norm).Select(TextNormalizer.Compact).ToHashSet();
        var exact = general.Any(h => keys.Contains(TextNormalizer.Compact(h.Name)) || (h.Brand is { } b && keys.Contains(TextNormalizer.Compact(b))));

        // Step 4: brand (or category) + area — "kfc ss2", "mydin shah alam", "minyak shah alam".
        if (!exact && r.Category is null && SplitArea(norm, areas, oLat, oLng) is { } split)
        {
            var (area, rest) = split;
            var restCats = table.Category(rest);
            IReadOnlyList<PlaceHit> hits;
            string kind;
            if (restCats is { Length: > 0 })
            {
                hits = await NearestOfAsync(restCats, area.Lat, area.Lng, r, limit, ct);
                kind = "category_area";
            }
            else
            {
                var restVariants = table.Rewrites(rest);
                hits = await index.SearchAsync(new SearchQuery(rest, area.Lat, area.Lng, limit, null, r.Country, r.Viewer, restVariants, RadiusM: 10_000), ct);
                kind = "brand_area";
            }
            if (hits.Count > 0)
                return new Ranked(hits, new SearchInterpretation(kind, norm, restCats, area.Name, area.Lat, area.Lng), area.Lat, area.Lng,
                    LiteralTexts: restCats is { Length: > 0 } ? null : table.Rewrites(rest).Prepend(rest).ToList());
        }

        return new Ranked(general, new SearchInterpretation("text", norm), oLat, oLng, LiteralTexts: variants.Prepend(norm).ToList());
    }

    /// <summary>Longest area phrase at the end (or start) of the query, leaving at least one word: "kfc ss2" → (SS2, "kfc").</summary>
    internal static (AreaRef Area, string Remainder)? SplitArea(string norm, AreaLocator areas, double? lat, double? lng)
    {
        var t = norm.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        for (var k = Math.Min(3, t.Length - 1); k >= 1; k--)
        {
            if (areas.Find(string.Join(' ', t[^k..]), lat, lng) is { } tail) return (tail, string.Join(' ', t[..^k]));
            if (areas.Find(string.Join(' ', t[..k]), lat, lng) is { } head) return (head, string.Join(' ', t[k..]));
        }
        return null;
    }

    private async Task<IReadOnlyList<PlaceHit>> NearestOfAsync(PlaceCategory[] cats, double? lat, double? lng, SearchRequest r, int limit, CancellationToken ct)
    {
        IReadOnlyList<PlaceHit> hits = [];
        foreach (var radius in lat is null ? [double.NaN] : CategoryRadiiM)
        {
            var q = new SearchQuery(r.Q, lat, lng, limit, null, r.Country, r.Viewer, Categories: cats, RadiusM: double.IsNaN(radius) ? null : radius, SortNearest: true);
            hits = await index.SearchAsync(SearchQueryBuilder.BuildCategory(q), ct);
            if (hits.Count >= Math.Min(3, limit)) break;
        }
        return hits;
    }

    private async Task<List<PlaceSummary>> FinishAsync(Ranked ranked, SearchRequest r, double? uLat, double? uLng, int limit, CancellationToken ct)
    {
        double? FromOrigin(PlaceHit h) => ranked.OriginLat is { } a && ranked.OriginLng is { } b ? GeoMath.HaversineM(a, b, h.Lat, h.Lng) : null;

        var list = SearchResults.Dedupe(ranked.Hits, h => h.Name, h => (h.Lat, h.Lng), ranked.Hits.Count);
        if (!ranked.KeepOrder) list = SearchResults.ChainsNearestFirst(list, h => SearchResults.ChainKey(h.Name, h.Brand), FromOrigin);
        if (ranked.Interpretation.Kind is "category" or "category_area" && ranked.Interpretation.Categories is { Count: > 1 } cats)
            list = list.OrderBy(h => SurauOrder(CategoryOrder(FromOrigin(h) ?? 0, h.Category, cats), h.Name, h.Category, cats)).ToList();
        else if (r.SortNearest || ranked.Interpretation.Kind is "category" or "category_area")
            list = list.OrderBy(h => FromOrigin(h) ?? 0).ToList();
        list = list.Take(limit).ToList();

        var summaries = list.Select(h => new PlaceSummary(h.Id, h.Name, h.Category,
            h.Category == PlaceCategory.Area ? L10n.AreaAddress(h.Address, r.Lang) : h.Address,
            h.Lat, h.Lng, uLat is { } a && uLng is { } b ? Math.Round(GeoMath.HaversineM(a, b, h.Lat, h.Lng)) : null, h.Brand,
            h.Source, h.Status is PlaceStatus.Pending or PlaceStatus.Published ? h.Status : null, h.Rating, h.ReviewCount, MediaKeys.Url(h.ThumbKey),
            OpenNow: Core.Places.OpeningHours.IsOpen(h.OpeningHours, LocalNow))).ToList();

        // Chain groups: "FamilyMart · 12 berdekatan".
        if (ranked.OriginLat is { } oa && ranked.OriginLng is { } ob)
        {
            var groups = summaries.Where(s => s.Category is not (PlaceCategory.Street or PlaceCategory.Address or PlaceCategory.Area))
                .GroupBy(s => SearchResults.ChainKey(s.Name, s.Brand)).Where(g => g.Count() >= 2 && g.Key.Length > 0).Take(3).ToList();
            foreach (var g in groups)
            {
                int n;
                try { n = await index.CountAsync(SearchQueryBuilder.BuildChainCount(g.Key, oa, ob, ChainRadiusM, r.Viewer), ct); }
                catch (SearchUnavailableException) { n = g.Count(); }
                var group = new ChainGroup(g.Key, g.First().Brand ?? g.First().Name, Math.Max(n, g.Count()));
                for (var i = 0; i < summaries.Count; i++)
                    if (SearchResults.ChainKey(summaries[i].Name, summaries[i].Brand) == g.Key) summaries[i] = summaries[i] with { ChainGroup = group };
            }
        }
        return summaries;
    }

    /// <summary>Did-you-mean (loose fuzzy match) and category suggestions for an empty result (step 8).</summary>
    public async Task<IReadOnlyList<SearchSuggestion>> SuggestAsync(SearchRequest r, CancellationToken ct)
    {
        var norm = TextNormalizer.Normalize(r.Q);
        var compact = TextNormalizer.Compact(r.Q);
        var list = new List<SearchSuggestion>();
        try
        {
            var (gLat, gLng) = r.NearLat is not null && r.NearLng is not null ? (r.NearLat, r.NearLng) : (r.Lat, r.Lng);
            JsonNode query = new JsonObject
            {
                ["bool"] = new JsonObject
                {
                    ["should"] = new JsonArray(
                        new JsonObject { ["match"] = new JsonObject { ["name"] = new JsonObject { ["query"] = norm, ["fuzziness"] = 2, ["operator"] = "or" } } },
                        new JsonObject { ["fuzzy"] = new JsonObject { ["nameKeys"] = new JsonObject { ["value"] = compact, ["fuzziness"] = 2 } } }),
                    ["minimum_should_match"] = 1,
                    ["filter"] = new JsonArray(SearchQueryBuilder.Visibility(r.Viewer)),
                    ["must_not"] = new JsonArray(new JsonObject { ["terms"] = new JsonObject { ["category"] = new JsonArray("address", "postcode", "street") } }),
                }
            };
            if (gLat is { } la0 && gLng is { } lo0)
                query = new JsonObject
                {
                    ["function_score"] = new JsonObject
                    {
                        ["query"] = query,
                        ["functions"] = new JsonArray(new JsonObject
                        {
                            ["gauss"] = new JsonObject { ["location"] = new JsonObject { ["origin"] = new JsonObject { ["lat"] = la0, ["lon"] = lo0 }, ["scale"] = "20km", ["decay"] = 0.5 } },
                        }),
                        ["boost_mode"] = "multiply",
                    }
                };
            var body = new JsonObject
            {
                ["size"] = 10,
                ["_source"] = new JsonArray("id", "name", "category", "address", "location", "brand", "country", "source"),
                ["query"] = query,
                // deterministic: ties broken by id
                ["sort"] = new JsonArray(new JsonObject { ["_score"] = new JsonObject { ["order"] = "desc" } }, new JsonObject { ["id"] = new JsonObject { ["order"] = "asc" } }),
            };
            // Only a near-miss spelling of the query counts ("Lotos" → "Lotus's"), never just any fuzzy hit far away.
            var queryWords = norm.Split(" ", StringSplitOptions.RemoveEmptyEntries).Length;
            // A correction must be close in spelling AND nearby (≤ 3 km), unless it's a chain (its nearest outlet is fine).
            var hit = (await index.SearchAsync(body, ct)).FirstOrDefault(h => IsCloseSpelling(compact, h.Name, queryWords, norm)
                && (h.Brand is not null || gLat is null || GeoMath.HaversineM(gLat.Value, gLng!.Value, h.Lat, h.Lng) <= MaxCorrectionDistanceM));
            if (hit is not null && TextNormalizer.Compact(hit.Name) != compact)
                list.Add(new SearchSuggestion(hit.Name, hit.Name, null, hit.Id, hit.Lat, hit.Lng,
                    r.Lat is { } ul && r.Lng is { } ug ? Math.Round(GeoMath.HaversineM(ul, ug, hit.Lat, hit.Lng)) : null));
        }
        catch (SearchUnavailableException) { }

        var table = aliases();
        var (oLat, oLng) = r.NearLat is not null && r.NearLng is not null ? (r.NearLat, r.NearLng) : (r.Lat, r.Lng);
        // Category words in or near the query: a phrase contained in the query first ("kedai runcit" in "kedai runcit pak ali"),
        // then exact words, then near spellings ("tandass").
        var words = norm.Split(' ', StringSplitOptions.RemoveEmptyEntries).Distinct().ToList();
        var candidates = new List<(int Rank, int Dist, string Word, PlaceCategory[] Cats)>();
        foreach (var cw in table.CategoryWords)
        {
            if (table.Category(cw) is not { Length: > 0 } cats) continue;
            if ((" " + norm + " ").Contains(" " + cw + " ", StringComparison.Ordinal)) { candidates.Add((0, -cw.Length, cw, cats)); continue; }
            var best = words.Append(norm).Where(w => w.Length >= 3).Select(w => Levenshtein(w, cw)).DefaultIfEmpty(99).Min();
            if (best <= (cw.Length >= 6 ? 2 : 1)) candidates.Add((1, best, cw, cats));
        }
        foreach (var (_, _, cw, cats) in candidates.OrderBy(c => c.Rank).ThenBy(c => c.Dist))
        {
            if (list.Any(s => s.Category == cats[0])) continue;
            int? count = null;
            if (oLat is { } la && oLng is { } lo)
                try { count = await DistinctCountAsync(cats, la, lo, r.Viewer, ct); }
                catch (SearchUnavailableException) { }
            list.Add(new SearchSuggestion(L10n.Category(cats[0], r.Lang), cw, cats[0], Count: count));
        }

        // Never empty: popular categories nearby when nothing else applies.
        if (list.Count == 0)
            foreach (var (cat, word) in PopularFallback(r.Lang))
            {
                int? count = null;
                if (oLat is { } la && oLng is { } lo)
                    try { count = await DistinctCountAsync([cat], la, lo, r.Viewer, ct); }
                    catch (SearchUnavailableException) { }
                if (count is 0) continue;
                list.Add(new SearchSuggestion(L10n.Category(cat, r.Lang), word, cat, Count: count));
            }
        return list.Take(4).ToList();
    }

    /// <summary>
    /// A near-miss spelling, word by word against the name's first words: words up to 3 letters must be equal, 4–6 letters may
    /// differ by 1, 7+ by 2 ("lotos mutiara" ≈ "Lotus's Mutiara"; "pak ali" ≠ "Pak Abu"). Spacing differences allow 1 edit overall
    /// ("famly mart" ≈ "FamilyMart").
    /// </summary>
    internal static bool IsCloseSpelling(string compactQuery, string name, int queryWords, string? queryNorm = null)
    {
        var tokens = TextNormalizer.Tokens(name);
        if (queryNorm is not null)
        {
            var q = TextNormalizer.Tokens(queryNorm);
            static bool Near(string a, string b) => a.Length >= 4 && Levenshtein(a, b) <= (a.Length >= 7 ? 2 : 1);
            // "lotuss" (Lotus's) also compares without the possessive s
            if (q.Length <= tokens.Length && q.Zip(tokens).All(p => p.First == p.Second || Near(p.First, p.Second)
                    || (p.Second.Length > 4 && p.Second.EndsWith("ss", StringComparison.Ordinal) && Near(p.First, p.Second[..^1]))))
                return true;
        }
        var head = string.Concat(tokens.Take(Math.Max(1, queryWords)));
        return Levenshtein(compactQuery, head) <= 1 || Levenshtein(compactQuery, string.Concat(tokens)) <= 1;
    }

    private static IEnumerable<(PlaceCategory, string)> PopularFallback(string lang) =>
    [
        (PlaceCategory.Restaurant, L10n.IsMs(lang) ? "kedai makan" : "restaurant"),
        (PlaceCategory.Fuel, L10n.IsMs(lang) ? "stesen minyak" : "petrol station"),
        (PlaceCategory.Convenience, L10n.IsMs(lang) ? "kedai runcit" : "convenience store"),
    ];

    /// <summary>Places of these categories within 5 km, with near-duplicates (an OSM node and its building) counted once.</summary>
    private async Task<int> DistinctCountAsync(PlaceCategory[] cats, double lat, double lng, Guid? viewer, CancellationToken ct)
    {
        var body = SearchQueryBuilder.BuildCategoryCount(cats, lat, lng, 5_000, viewer);
        body["size"] = 1000;
        body["_source"] = new JsonArray("id", "name", "category", "location");
        var hits = await index.SearchAsync(body, ct);
        return SearchResults.Dedupe(hits, h => h.Name, h => (h.Lat, h.Lng), hits.Count).Count;
    }

    internal static int Levenshtein(string a, string b)
    {
        var d = new int[b.Length + 1];
        for (var j = 0; j <= b.Length; j++) d[j] = j;
        for (var i = 1; i <= a.Length; i++)
        {
            var prev = d[0]; d[0] = i;
            for (var j = 1; j <= b.Length; j++)
            {
                var tmp = d[j];
                d[j] = Math.Min(Math.Min(d[j] + 1, d[j - 1] + 1), prev + (a[i - 1] == b[j - 1] ? 0 : 1));
                prev = tmp;
            }
        }
        return d[b.Length];
    }

}

/// <summary>
/// "Finding places" (docs/api.md): coordinates / Plus Codes → <see cref="SearchPipeline"/> → never empty (suggestions) and
/// anonymous miss counting; plus reverse geocoding for "Pilih pada peta".
/// </summary>
public sealed class SearchService(PlaceSearchIndex index, AliasStore aliases, AreaDirectory areaDirectory, PlaceService places,
    GimanoDbContext db, SearchMissRecorder misses, GeoLookup geo, TimeProvider clock, ILogger<SearchService> logger)
{
    private readonly SearchPipeline pipeline = new(index, () => aliases.Current, clock);

    public async Task<SearchResponse> SearchAsync(SearchRequest r, CancellationToken ct)
    {
        var (oLat, oLng) = r.NearLat is not null && r.NearLng is not null ? (r.NearLat, r.NearLng) : (r.Lat, r.Lng);
        var (uLat, uLng) = r.Lat is not null ? (r.Lat, r.Lng) : (oLat, oLng);

        // Point results (coordinates, Plus Codes) don't need the index.
        var areas = await areaDirectory.GetAsync(ct);
        var parsed = QueryParser.Parse(r.Q, oLat, oLng, s => areas.Find(s, oLat, oLng) is { } a ? (a.Lat, a.Lng) : null);
        if (parsed is CoordinateQuery or PlusCodeQuery)
            return await PointAsync(parsed, r, uLat, uLng, ct);

        List<PlaceSummary> results;
        SearchInterpretation interpretation;
        string? correctedQuery;
        try
        {
            (results, interpretation, correctedQuery) = await pipeline.RunAsync(r, parsed, areas, ct);
        }
        catch (Exception ex) when (ex is SearchUnavailableException or HttpRequestException)
        {
            logger.LogWarning("OpenSearch unavailable ({Message}); falling back to Postgres search", ex.Message);
            var fallback = await places.SearchAsync(new SearchQuery(r.Q, oLat, oLng, r.Limit, r.Category, r.Country, r.Viewer), ct);
            return new SearchResponse(fallback, NoMatch: fallback.Count == 0);
        }
        if (results.Count > 0)
        {
            // Typo-only matches always say so (exact=true: only literal matches, otherwise nothing). A different single shop
            // more than 3 km away is not a correction.
            if (correctedQuery is null) return new SearchResponse(results, Interpretation: interpretation);
            var top = results[0];
            var farSingle = top.Brand is null && oLat is { } ta && oLng is { } tg
                && GeoMath.HaversineM(ta, tg, top.Lat, top.Lng) > SearchPipeline.MaxCorrectionDistanceM;
            if (!r.Exact && !farSingle) return new SearchResponse(results, correctedQuery, null, false, interpretation);
            results = [];
        }

        // Never empty: did-you-mean / nearby-category suggestions, and (unless exact=true) results for the corrected spelling.
        var suggestions = await pipeline.SuggestAsync(r, ct);
        await misses.RecordAsync(r.Q, oLat, oLng, ct);
        if (!r.Exact && suggestions.FirstOrDefault(s => s.PlaceId is not null)?.Query is { } corrected)
        {
            var (fixedResults, fixedInterp, _) = await pipeline.RunAsync(r with { Q = corrected }, null, areas, ct);
            if (fixedResults.Count > 0) return new SearchResponse(fixedResults, corrected, suggestions, false, fixedInterp);
        }
        return new SearchResponse(results, null, suggestions, true, interpretation);
    }

    private async Task<SearchResponse> PointAsync(ParsedQuery parsed, SearchRequest r, double? uLat, double? uLng, CancellationToken ct)
    {
        var (lat, lng, name, kind) = parsed switch
        {
            CoordinateQuery c => (c.Lat, c.Lng, string.Create(CultureInfo.InvariantCulture, $"{c.Lat:0.######}, {c.Lng:0.######}"), "coordinates"),
            PlusCodeQuery p => (p.Lat, p.Lng, p.Code, "plus_code"),
            _ => throw new InvalidOperationException(),
        };
        var rev = await ReverseAsync(new GeoPoint(lat, lng), r.Lang, r.Viewer, ct);
        var revLabel = rev.Label ?? rev.Address;
        var id = string.Create(CultureInfo.InvariantCulture, $"pt-{lat:0.######},{lng:0.######}");
        var summary = new PlaceSummary(id, name, PlaceCategory.Point, revLabel, lat, lng,
            uLat is { } a && uLng is { } b ? Math.Round(GeoMath.HaversineM(a, b, lat, lng)) : null, null);
        return new SearchResponse([summary], Interpretation: new SearchInterpretation(kind, TextNormalizer.Normalize(r.Q)));
    }

    public const double StreetLabelRadiusM = 30;

    private sealed record ContainingRow(string? City, string? AdminCity, string? Local, bool HasAdmin);

    /// <summary>
    /// City and neighbourhood from the admin boundaries that CONTAIN the point (smallest first), not merely the nearest place
    /// node: KLCC is in Kuala Lumpur, not Ampang Jaya across the border. City = the nearest city/town node inside the smallest
    /// containing boundary of level ≤ 6, else that boundary's name; area = the nearest suburb/neighbourhood inside the smallest
    /// containing boundary. Without boundary data, the nearest place nodes are used. (Columns are snake_case: EF maps
    /// SqlQueryRaw results through the naming convention.)
    /// </summary>
    private async Task<(string? Local, string? Town)> ContainingAreasAsync(GeoPoint p, CancellationToken ct)
    {
        const string sql = """
            WITH pt AS (SELECT ST_SetSRID(ST_MakePoint(@lng, @lat), 4326)::geography AS g),
            inside AS (SELECT a.name, a.admin_level, a.geom, ST_Area(a.geom) AS area FROM admin_areas a, pt WHERE ST_Covers(a.geom, pt.g)),
            city_area AS (SELECT name, geom FROM inside WHERE admin_level <= 6 ORDER BY admin_level DESC, area ASC LIMIT 1),
            local_area AS (SELECT geom FROM inside ORDER BY admin_level DESC, area ASC LIMIT 1)
            SELECT
              (SELECT pl.name FROM places pl, pt, city_area c
                 WHERE pl.category = 'Area' AND pl.tags->>'place' IN ('city', 'town') AND ST_DWithin(pl.location, pt.g, 25000)
                   AND ST_Covers(c.geom, pl.location)
                 ORDER BY pl.location <-> pt.g LIMIT 1) AS city,
              (SELECT name FROM city_area) AS admin_city,
              (SELECT pl.name FROM places pl, pt, local_area l
                 WHERE pl.category = 'Area' AND pl.tags->>'place' IN ('suburb', 'neighbourhood', 'quarter', 'village')
                   AND ST_DWithin(pl.location, pt.g, 2500) AND ST_Covers(l.geom, pl.location)
                 ORDER BY pl.location <-> pt.g LIMIT 1) AS local,
              EXISTS (SELECT 1 FROM inside) AS has_admin
            """;
        try
        {
            var row = await db.Database.SqlQueryRaw<ContainingRow>(sql,
                new Npgsql.NpgsqlParameter("lat", p.Lat), new Npgsql.NpgsqlParameter("lng", p.Lng)).FirstAsync(ct);
            if (row.HasAdmin) return (row.Local, row.City ?? row.AdminCity);
        }
        catch (Exception ex) when (ex is not OperationCanceledException) { logger.LogWarning("Admin-area lookup failed: {Message}", ex.Message); }
        var (local, town) = (await areaDirectory.GetAsync(ct)).Locate(p.Lat, p.Lng);
        return (local?.Name, town?.Name);
    }

    /// <summary>"Dekat 18, Jalan SS 2/24, SS2, Petaling Jaya" / "Near …"; "Pin di peta" / "Dropped pin" when nothing is known.</summary>
    public static string ReverseLabel(string? houseNumber, string? street, string? area, string? city, string lang)
    {
        var first = houseNumber is not null && street is not null ? $"{houseNumber}, {street}" : street;
        var parts = new[] { first, area, city }.Where(x => !string.IsNullOrWhiteSpace(x)).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        return parts.Count > 0 ? $"{L10n.Near(lang)} {string.Join(", ", parts)}" : L10n.DroppedPin(lang);
    }

    /// <summary>GET /geocode/reverse: "Dekat 18, Jalan SS 2/24, SS2, Petaling Jaya" + what is within 50 m ("Adakah ini FamilyMart?").</summary>
    public async Task<ReverseGeocodeResponse> ReverseAsync(GeoPoint p, string lang, Guid? viewer, CancellationToken ct)
    {
        var pt = p.ToPoint();
        var addr = await db.StreetAddresses.AsNoTracking().Where(a => a.Location.IsWithinDistance(pt, 40))
            .OrderBy(a => a.Location.Distance(pt)).FirstOrDefaultAsync(ct);
        var street = addr?.Street ?? await geo.StreetNameAsync(p, ct, 100);
        var postcode = addr?.Postcode ?? await db.StreetAddresses.AsNoTracking()
            .Where(a => a.Postcode != null && a.Location.IsWithinDistance(pt, 1000)).OrderBy(a => a.Location.Distance(pt))
            .Select(a => a.Postcode).FirstOrDefaultAsync(ct);
        var (localName, townName) = await ContainingAreasAsync(p, ct);

        var nearby = (await places.NearbyAsync(p, null, 50, 8, viewer, ct))
            .Where(s => s.Category is not (PlaceCategory.Area or PlaceCategory.Parking)).Take(5).ToList();

        // No house address and no street within ~30 m (e.g. inside KLCC): name the spot after the nearest named place.
        string? first = addr is not null ? $"{addr.HouseNumber}, {addr.Street}" : null;
        if (first is null && street is not null && await geo.StreetNameAsync(p, ct, StreetLabelRadiusM) is { } close) first = close;
        first ??= nearby.FirstOrDefault(n => SearchResults.ChainKey(n.Name, null).Length > 0 || n.Brand is not null)?.Name ?? street;
        var label = ReverseLabel(null, first, localName, townName ?? addr?.City, lang);
        var basic = await geo.ReverseAsync(p, ct);   // name/address as before (nearest place within 40 m, else street)
        var name = basic.Name == "Dropped pin" ? L10n.DroppedPin(lang) : basic.Name;
        return basic with
        {
            Name = name, Label = label, HouseNumber = addr?.HouseNumber, Street = street, Area = localName, City = townName ?? addr?.City,
            Postcode = postcode, Nearby = nearby,
        };
    }
}
