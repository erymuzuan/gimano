using System.Diagnostics;
using System.Text.Json;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Gimano.Core.Geo;
using Gimano.Core.Places;
using Gimano.Core.Search;
using Gimano.Core.Tolls;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging;
using Npgsql;
using NpgsqlTypes;
using OsmSharp;
using OsmSharp.Streams;
using OsmSharp.Tags;

namespace Gimano.Importer;

public sealed record ImportOptions(string PbfPath, bool Places = true, bool Roads = true, bool Search = true, bool RecreateIndex = true, bool DryRun = false, string? TollsCsv = null);

/// <summary>
/// Two-pass PBF import:
///  1. ways   → road ways (traffic model) and POI ways (need node coordinates for a centroid);
///  2. nodes  → POI nodes, plus coordinates for every node referenced in pass 1.
/// Then: COPY road segments and upsert places into Postgres, enrich missing addresses, bulk index OpenSearch.
/// </summary>
public sealed class OsmImporter(GimanoDbContext db, NpgsqlDataSource dataSource, PlaceSearchIndex index, ILogger<OsmImporter> logger)
{
    private sealed record RoadWay(long Id, long[] Nodes, string Highway, string? Name, string? Ref, bool Oneway, double FreeFlowKmh);
    private sealed record PoiWay(long Id, long[] Nodes, PlaceCategory Category, Dictionary<string, string> Tags);

    /// <summary>Closed ways that are inland water (lakes, reservoirs, river areas): used to reject community places "in water".</summary>
    internal static bool IsWaterArea(IReadOnlyDictionary<string, string> tags) =>
        tags.GetValueOrDefault("natural") == "water" || tags.GetValueOrDefault("waterway") == "riverbank"
        || tags.GetValueOrDefault("landuse") is "reservoir" or "basin";

    public async Task RunAsync(ImportOptions o, CancellationToken ct)
    {
        var sw = Stopwatch.StartNew();
        if (!o.DryRun)
        {
            logger.LogInformation("Applying migrations…");
            await db.Database.MigrateAsync(ct);
        }

        if (o.Places || o.Roads)
        {
            if (!File.Exists(o.PbfPath)) throw new FileNotFoundException($"OSM extract not found: {o.PbfPath}. Run data/download-osm.sh first.");

            // ---- Pass 1: ways -------------------------------------------------
            var roads = new List<RoadWay>();
            var poiWays = new List<PoiWay>();
            var waterWays = new List<(long Id, long[] Nodes)>();
            var adminAreas = new List<(long Id, long LabelNode, Dictionary<string, string> Tags)>();
            var needed = new List<long>();
            await using (var fs = File.OpenRead(o.PbfPath))
            {
                foreach (var g in new PBFOsmStreamSource(fs))
                {
                    if (g.Type == OsmGeoType.Node) continue;
                    if (g.Type == OsmGeoType.Relation)
                    {
                        // Administrative areas (states, federal territories, districts) as searchable areas, placed at their
                        // label (or admin centre) node; full boundary geometry isn't needed for search.
                        if (o.Places && g.Tags is { Count: > 0 } && AdminArea((Relation)g) is { } a)
                        {
                            adminAreas.Add(a);
                            needed.Add(a.LabelNode);
                        }
                        continue;
                    }
                    var w = (Way)g;
                    if (w.Nodes is not { Length: >= 2 } || w.Tags is null || w.Tags.Count == 0) continue;
                    var tags = ToDict(w.Tags);

                    var highway = tags.GetValueOrDefault("highway");
                    if (o.Roads && RoadRules.IsRoad(highway) && tags.GetValueOrDefault("area") != "yes")
                    {
                        var (oneway, reversed) = RoadRules.Oneway(highway!, tags.GetValueOrDefault("oneway"), tags.GetValueOrDefault("junction"));
                        var nodes = reversed ? w.Nodes.Reverse().ToArray() : w.Nodes;
                        roads.Add(new RoadWay(w.Id!.Value, nodes, highway!, tags.GetValueOrDefault("name"), tags.GetValueOrDefault("ref"),
                            oneway, RoadRules.FreeFlowKmh(highway!, tags.GetValueOrDefault("maxspeed"))));
                        needed.AddRange(nodes);
                    }
                    if (o.Places && OsmCategoryMapper.Map(tags) is { } cat && OsmCategoryMapper.ShouldImport(cat, tags))
                    {
                        poiWays.Add(new PoiWay(w.Id!.Value, w.Nodes, cat, tags));
                        needed.AddRange(w.Nodes);
                    }
                    if (o.Places && w.Nodes.Length >= 4 && w.Nodes[0] == w.Nodes[^1] && IsWaterArea(tags))
                    {
                        waterWays.Add((w.Id!.Value, w.Nodes));
                        needed.AddRange(w.Nodes);
                    }
                }
            }
            var neededIds = needed.Distinct().Order().ToArray();
            needed = null!;
            logger.LogInformation("Pass 1: {Roads} road ways, {PoiWays} POI ways, {Nodes} referenced nodes ({Elapsed:n0}s)",
                roads.Count, poiWays.Count, neededIds.Length, sw.Elapsed.TotalSeconds);

            // ---- Pass 2: nodes ------------------------------------------------
            var lat = new double[neededIds.Length];
            var lng = new double[neededIds.Length];
            var places = new List<Place>();
            await using (var fs = File.OpenRead(o.PbfPath))
            {
                foreach (var g in new PBFOsmStreamSource(fs))
                {
                    if (g.Type != OsmGeoType.Node) break;
                    var n = (Node)g;
                    if (n.Latitude is not { } la || n.Longitude is not { } lo) continue;
                    var idx = Array.BinarySearch(neededIds, n.Id!.Value);
                    if (idx >= 0) { lat[idx] = la; lng[idx] = lo; }

                    if (o.Places && n.Tags is { Count: > 0 })
                    {
                        var tags = ToDict(n.Tags);
                        if (OsmCategoryMapper.Map(tags) is { } cat && OsmCategoryMapper.ShouldImport(cat, tags))
                            places.Add(ToPlace("n", n.Id.Value, cat, tags, la, lo));
                    }
                }
            }
            logger.LogInformation("Pass 2: {Places} POI nodes ({Elapsed:n0}s)", places.Count, sw.Elapsed.TotalSeconds);

            bool TryCoord(long id, out GeoPoint p)
            {
                var i = Array.BinarySearch(neededIds, id);
                p = i >= 0 && (lat[i] != 0 || lng[i] != 0) ? new GeoPoint(lat[i], lng[i]) : default;
                return i >= 0 && (lat[i] != 0 || lng[i] != 0);
            }

            foreach (var w in poiWays)
            {
                var pts = w.Nodes.Select(id => TryCoord(id, out var p) ? p : (GeoPoint?)null).Where(p => p is not null).Select(p => p!.Value).ToList();
                if (pts.Count == 0) continue;
                // Closed ways repeat the first node; drop it so the centroid isn't biased.
                if (pts.Count > 1 && pts[0] == pts[^1]) pts.RemoveAt(pts.Count - 1);
                places.Add(ToPlace("w", w.Id, w.Category, w.Tags, pts.Average(p => p.Lat), pts.Average(p => p.Lng)));
            }

            var areaNodes = places.Where(p => p.Category == PlaceCategory.Area).ToList();
            foreach (var a in adminAreas)
                if (TryCoord(a.LabelNode, out var p))
                {
                    var admin = ToPlace("r", a.Id, PlaceCategory.Area, a.Tags, p.Lat, p.Lng);
                    // "Putrajaya" the federal territory and "Putrajaya" the city are one search result: keep the place node
                    // (better centre) with the higher popularity of the two.
                    if (MergeAdminInto(areaNodes, admin) is { } same) { same.Popularity = Math.Max(same.Popularity, admin.Popularity); continue; }
                    places.Add(admin);
                }
            logger.LogInformation("Areas: {Areas} named places/admin areas", places.Count(p => p.Category == PlaceCategory.Area));

            if (o.DryRun)
            {
                var segments = roads.Sum(r => Chunk(r.Nodes, TryCoord).Count());
                logger.LogInformation("Dry run: {Places} places ({ByCountry}), {Segments} road segments. Top categories: {Cats}",
                    places.Count,
                    string.Join(", ", places.GroupBy(p => p.Country).Select(g => $"{g.Key}={g.Count()}")),
                    segments,
                    string.Join(", ", places.GroupBy(p => p.Category).OrderByDescending(g => g.Count()).Take(12).Select(g => $"{g.Key}={g.Count()}")));
                foreach (var p in places.Where(p => p.Name.Contains("Petronas", StringComparison.OrdinalIgnoreCase)).Take(5))
                    logger.LogInformation("  sample: {Id} {Name} [{Cat}] {Country} '{Address}' ({Lat:F2},{Lng:F2})", p.Id, p.Name, p.Category, p.Country, p.Address, p.Location.Y, p.Location.X);
                return;
            }
            if (o.Places) await WriteWaterAsync(waterWays, TryCoord, ct);
            if (o.Roads) await WriteRoadsAsync(roads, TryCoord, ct);
            if (o.Places) await UpsertPlacesAsync(places, ct);
            if (o.Places && o.Roads) await EnrichAddressesAsync(ct);
        }

        if (!o.DryRun && o.TollsCsv is not null) await ImportTollRatesAsync(o.TollsCsv, ct);
        if (o.Search) await IndexSearchAsync(o.RecreateIndex, ct);
        logger.LogInformation("Import finished in {Elapsed}", sw.Elapsed);
    }

    private delegate bool CoordLookup(long id, out GeoPoint p);

    internal static Place ToPlace(string type, long id, PlaceCategory cat, IReadOnlyDictionary<string, string> tags, double lat, double lng) => new()
    {
        Id = type + id,
        Name = Truncate(OsmCategoryMapper.DisplayName(cat, tags), 200),
        Category = cat,
        Brand = OsmCategoryMapper.Brand(tags),
        Address = Truncate(cat == PlaceCategory.Area ? OsmCategoryMapper.AreaAddress(tags) : OsmCategoryMapper.BuildAddress(tags), 300),
        Location = new GeoPoint(lat, lng).ToPoint(),
        Country = CountryResolver.Resolve(lat, lng, tags.GetValueOrDefault("addr:country")),
        Phone = tags.GetValueOrDefault("phone") ?? tags.GetValueOrDefault("contact:phone"),
        Website = tags.GetValueOrDefault("website") ?? tags.GetValueOrDefault("contact:website"),
        OpeningHours = tags.GetValueOrDefault("opening_hours"),
        Amenities = OsmCategoryMapper.Amenities(tags).ToList(),
        Tags = OsmCategoryMapper.KeepTags(tags),
        OsmType = type switch { "n" => "node", "w" => "way", _ => "relation" },
        OsmId = id,
        Popularity = OsmCategoryMapper.Popularity(cat, tags),
    };

    internal static Place? MergeAdminInto(IEnumerable<Place> areaNodes, Place admin) =>
        areaNodes.FirstOrDefault(n => string.Equals(n.Name, admin.Name, StringComparison.OrdinalIgnoreCase)
            && GeoMath.HaversineM(n.Location.Y, n.Location.X, admin.Location.Y, admin.Location.X) <= 15_000);

    internal static (long Id, long LabelNode, Dictionary<string, string> Tags)? AdminArea(Relation r)
    {
        var tags = ToDict(r.Tags);
        if (tags.GetValueOrDefault("boundary") != "administrative" || !tags.TryGetValue("name", out var name) || name.Length == 0) return null;
        if (!int.TryParse(tags.GetValueOrDefault("admin_level"), out var level) || level is < 4 or > 6) return null;
        var members = r.Members ?? [];
        var label = members.FirstOrDefault(m => m.Type == OsmGeoType.Node && m.Role == "label")
                    ?? members.FirstOrDefault(m => m.Type == OsmGeoType.Node && m.Role == "admin_centre");
        if (label is null) return null;
        tags["place"] = "admin";
        tags["gimano:admin_level"] = level.ToString(System.Globalization.CultureInfo.InvariantCulture);
        return (r.Id!.Value, label.Id, tags);
    }

    private static string Truncate(string s, int max) => s.Length <= max ? s : s[..max];

    private static Dictionary<string, string> ToDict(TagsCollectionBase tags)
    {
        var d = new Dictionary<string, string>(tags.Count);
        foreach (var t in tags) d[t.Key] = t.Value;
        return d;
    }

    private async Task WriteRoadsAsync(List<RoadWay> roads, CoordLookup coord, CancellationToken ct)
    {
        await using var conn = await dataSource.OpenConnectionAsync(ct);
        await using (var trunc = new NpgsqlCommand("TRUNCATE road_segments RESTART IDENTITY; TRUNCATE segment_speeds; TRUNCATE jams;", conn))
            await trunc.ExecuteNonQueryAsync(ct);

        long count = 0;
        await using (var writer = await conn.BeginBinaryImportAsync(
            "COPY road_segments (osm_way_id, seq, name, ref, highway, oneway, free_flow_kmh, length_m, from_node, to_node, geom) FROM STDIN (FORMAT BINARY)", ct))
        {
            foreach (var road in roads)
            {
                var seq = 0;
                foreach (var chunk in Chunk(road.Nodes, coord))
                {
                    await writer.StartRowAsync(ct);
                    await writer.WriteAsync(road.Id, NpgsqlDbType.Bigint, ct);
                    await writer.WriteAsync(seq++, NpgsqlDbType.Integer, ct);
                    await WriteNullableAsync(writer, road.Name, ct);
                    await WriteNullableAsync(writer, road.Ref, ct);
                    await writer.WriteAsync(road.Highway, NpgsqlDbType.Varchar, ct);
                    await writer.WriteAsync(road.Oneway, NpgsqlDbType.Boolean, ct);
                    await writer.WriteAsync(road.FreeFlowKmh, NpgsqlDbType.Double, ct);
                    await writer.WriteAsync(chunk.LengthM, NpgsqlDbType.Double, ct);
                    await writer.WriteAsync(chunk.FromNode, NpgsqlDbType.Bigint, ct);
                    await writer.WriteAsync(chunk.ToNode, NpgsqlDbType.Bigint, ct);
                    await writer.WriteAsync(GeoMath.ToLineString(chunk.Points), NpgsqlDbType.Geography, ct);
                    count++;
                }
            }
            await writer.CompleteAsync(ct);
        }
        await using (var analyze = new NpgsqlCommand("ANALYZE road_segments;", conn)) await analyze.ExecuteNonQueryAsync(ct);
        logger.LogInformation("Wrote {Count} road segments from {Ways} ways", count, roads.Count);
    }

    private async Task WriteWaterAsync(List<(long Id, long[] Nodes)> ways, CoordLookup coord, CancellationToken ct)
    {
        await using var conn = await dataSource.OpenConnectionAsync(ct);
        await using (var trunc = new NpgsqlCommand("TRUNCATE water_areas RESTART IDENTITY;", conn)) await trunc.ExecuteNonQueryAsync(ct);
        var count = 0;
        await using (var writer = await conn.BeginBinaryImportAsync("COPY water_areas (osm_way_id, geom) FROM STDIN (FORMAT BINARY)", ct))
        {
            foreach (var (id, nodes) in ways)
            {
                var pts = new List<NetTopologySuite.Geometries.Coordinate>(nodes.Length);
                foreach (var n in nodes) if (coord(n, out var p)) pts.Add(new(p.Lng, p.Lat));
                if (pts.Count < 4 || !pts[0].Equals2D(pts[^1])) continue;
                var poly = GeoMath.Factory.CreatePolygon(pts.ToArray());
                if (!poly.IsValid) continue; // self-intersecting outlines: skip rather than misclassify land
                if (!poly.Shell.IsCCW) poly = (NetTopologySuite.Geometries.Polygon)poly.Reverse();
                await writer.StartRowAsync(ct);
                await writer.WriteAsync(id, NpgsqlDbType.Bigint, ct);
                await writer.WriteAsync(poly, NpgsqlDbType.Geography, ct);
                count++;
            }
            await writer.CompleteAsync(ct);
        }
        logger.LogInformation("Wrote {Count} inland water areas", count);
    }

    private static async Task WriteNullableAsync(NpgsqlBinaryImporter w, string? s, CancellationToken ct)
    {
        if (s is null) await w.WriteNullAsync(ct);
        else await w.WriteAsync(s, NpgsqlDbType.Text, ct);
    }

    internal readonly record struct RoadChunk(long FromNode, long ToNode, List<GeoPoint> Points, double LengthM);

    /// <summary>Splits a way into ≈300 m chunks. Chunks share their boundary node.</summary>
    private static IEnumerable<RoadChunk> Chunk(long[] nodes, CoordLookup coord)
    {
        var pts = new List<GeoPoint>();
        long from = 0;
        long last = 0;
        double len = 0;
        foreach (var id in nodes)
        {
            if (!coord(id, out var p)) continue;
            if (pts.Count == 0) { from = id; pts.Add(p); last = id; continue; }
            if (p == pts[^1]) continue;
            len += GeoMath.HaversineM(pts[^1], p);
            pts.Add(p);
            last = id;
            if (len >= RoadRules.ChunkLengthM)
            {
                yield return new RoadChunk(from, id, pts, len);
                pts = [p];
                from = id;
                len = 0;
            }
        }
        if (pts.Count >= 2) yield return new RoadChunk(from, last, pts, len);
    }

    private async Task UpsertPlacesAsync(List<Place> places, CancellationToken ct)
    {
        // De-duplicate ids (shouldn't happen, but a broken extract must not abort the import).
        places = places.GroupBy(p => p.Id).Select(g => g.First()).ToList();
        await using var conn = await dataSource.OpenConnectionAsync(ct);
        await using (var cmd = new NpgsqlCommand("CREATE TEMP TABLE places_import (LIKE places INCLUDING DEFAULTS) ON COMMIT DROP;", conn))
        {
            await using var tx = await conn.BeginTransactionAsync(ct);
            cmd.Transaction = tx;
            await cmd.ExecuteNonQueryAsync(ct);

            await using (var w = await conn.BeginBinaryImportAsync(
                "COPY places_import (id, name, category, brand, address, location, country, phone, website, opening_hours, amenities, tags, osm_type, osm_id, popularity, updated_at) FROM STDIN (FORMAT BINARY)", ct))
            {
                var now = DateTimeOffset.UtcNow;
                foreach (var p in places)
                {
                    await w.StartRowAsync(ct);
                    await w.WriteAsync(p.Id, NpgsqlDbType.Varchar, ct);
                    await w.WriteAsync(p.Name, NpgsqlDbType.Text, ct);
                    await w.WriteAsync(p.Category.ToString(), NpgsqlDbType.Varchar, ct);
                    await WriteNullableAsync(w, p.Brand, ct);
                    await w.WriteAsync(p.Address, NpgsqlDbType.Text, ct);
                    await w.WriteAsync(p.Location, NpgsqlDbType.Geography, ct);
                    await w.WriteAsync(p.Country, NpgsqlDbType.Varchar, ct);
                    await WriteNullableAsync(w, p.Phone, ct);
                    await WriteNullableAsync(w, p.Website, ct);
                    await WriteNullableAsync(w, p.OpeningHours, ct);
                    await w.WriteAsync(p.Amenities.ToArray(), NpgsqlDbType.Array | NpgsqlDbType.Text, ct);
                    await w.WriteAsync(JsonSerializer.Serialize(p.Tags), NpgsqlDbType.Jsonb, ct);
                    await w.WriteAsync(p.OsmType, NpgsqlDbType.Varchar, ct);
                    await w.WriteAsync(p.OsmId, NpgsqlDbType.Bigint, ct);
                    await w.WriteAsync(p.Popularity, NpgsqlDbType.Double, ct);
                    await w.WriteAsync(now, NpgsqlDbType.TimestampTz, ct);
                }
                await w.CompleteAsync(ct);
            }

            const string upsert = """
                INSERT INTO places AS p (id, name, category, brand, address, location, country, phone, website, opening_hours, amenities, tags, osm_type, osm_id, popularity, updated_at)
                SELECT id, name, category, brand, address, location, country, phone, website, opening_hours, amenities, tags, osm_type, osm_id, popularity, updated_at FROM places_import
                ON CONFLICT (id) DO UPDATE SET
                  name = EXCLUDED.name, category = EXCLUDED.category, brand = EXCLUDED.brand, address = EXCLUDED.address,
                  location = EXCLUDED.location, country = EXCLUDED.country, phone = EXCLUDED.phone, website = EXCLUDED.website,
                  opening_hours = EXCLUDED.opening_hours, amenities = EXCLUDED.amenities, tags = EXCLUDED.tags,
                  osm_type = EXCLUDED.osm_type, osm_id = EXCLUDED.osm_id, popularity = EXCLUDED.popularity, updated_at = EXCLUDED.updated_at;
                DELETE FROM places WHERE id NOT IN (SELECT id FROM places_import);
                """;
            await using (var up = new NpgsqlCommand(upsert, conn, tx)) await up.ExecuteNonQueryAsync(ct);
            await tx.CommitAsync(ct);
        }
        await using (var analyze = new NpgsqlCommand("ANALYZE places;", conn)) await analyze.ExecuteNonQueryAsync(ct);
        logger.LogInformation("Upserted {Count} places", places.Count);
    }

    /// <summary>Places without addr:* tags get the nearest named road (≤ 150 m) as their address.</summary>
    private async Task EnrichAddressesAsync(CancellationToken ct)
    {
        const string sql = """
            UPDATE places p SET address = sub.name
            FROM (
              SELECT p2.id,
                     (SELECT COALESCE(s.name, s.ref) FROM road_segments s
                      WHERE (s.name IS NOT NULL OR s.ref IS NOT NULL) AND ST_DWithin(s.geom, p2.location, 150)
                      ORDER BY s.geom <-> p2.location LIMIT 1) AS name
              FROM places p2
              WHERE p2.address = ''
            ) sub
            WHERE p.id = sub.id AND sub.name IS NOT NULL;
            """;
        await using var conn = await dataSource.OpenConnectionAsync(ct);
        await using var cmd = new NpgsqlCommand(sql, conn) { CommandTimeout = 1800 };
        var n = await cmd.ExecuteNonQueryAsync(ct);
        logger.LogInformation("Filled {Count} missing addresses from nearby roads", n);
    }

    /// <summary>Loads data/tolls/my-toll-rates.csv, matches rows to OSM toll plazas, replaces toll_rates and writes a match report.</summary>
    private async Task ImportTollRatesAsync(string csvPath, CancellationToken ct)
    {
        if (!File.Exists(csvPath))
        {
            logger.LogWarning("Toll tariff table not found at {Path}; skipping toll fares", csvPath);
            return;
        }
        var parsed = TollRateCsv.Parse(await File.ReadAllTextAsync(csvPath, ct));
        foreach (var e in parsed.Errors) logger.LogWarning("Toll CSV: {Error}", e);

        var plazas = (await db.Places.AsNoTracking().Where(p => p.Category == PlaceCategory.TollBooth)
                .Select(p => new { p.Id, p.Name, p.Location }).ToListAsync(ct))
            .Select(p => new TollPlaza(p.Id, p.Name, p.Location.Y, p.Location.X)).ToList();
        var outcomes = TollRateCsv.Match(parsed.Rows, plazas);
        foreach (var m in outcomes) m.Row.MatchedPlaceIds = [.. m.PlaceIds];

        await db.TollRates.ExecuteDeleteAsync(ct);
        db.TollRates.AddRange(outcomes.Select(m => m.Row));
        await db.SaveChangesAsync(ct);

        var matched = outcomes.Count(m => m.PlaceIds.Count > 0);
        logger.LogInformation("Toll rates: {Rows} rows, {Matched} matched to {Nodes} OSM plaza nodes, {Unmatched} unmatched, {Errors} invalid",
            outcomes.Count, matched, outcomes.Sum(m => m.PlaceIds.Count), outcomes.Count - matched, parsed.Errors.Count);

        var report = BuildTollReport(csvPath, outcomes, parsed.Errors, plazas.Count);
        var reportPath = Path.ChangeExtension(csvPath, ".match-report.md");
        try
        {
            await File.WriteAllTextAsync(reportPath, report, ct);
            logger.LogInformation("Toll match report written to {Path}", reportPath);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            logger.LogWarning("Could not write {Path} ({Message}); report follows:\n{Report}", reportPath, ex.Message, report);
        }
    }

    internal static string BuildTollReport(string csvPath, IReadOnlyList<TollRateCsv.MatchOutcome> outcomes, IReadOnlyList<string> errors, int plazaNodes)
    {
        var sb = new System.Text.StringBuilder();
        sb.AppendLine("# Toll tariff ↔ OSM plaza match report").AppendLine();
        sb.AppendLine($"Generated by `Gimano.Importer` from `{Path.GetFileName(csvPath)}` against {plazaNodes} OSM toll plaza nodes.").AppendLine();
        sb.AppendLine($"- rows: {outcomes.Count}, matched: {outcomes.Count(o => o.PlaceIds.Count > 0)}, unmatched: {outcomes.Count(o => o.PlaceIds.Count == 0)}, invalid: {errors.Count}").AppendLine();
        var unmatched = outcomes.Where(o => o.PlaceIds.Count == 0).ToList();
        if (unmatched.Count > 0)
        {
            sb.AppendLine("## Unmatched rows").AppendLine().AppendLine("| name | highway | system | entry | reason |").AppendLine("|---|---|---|---|---|");
            foreach (var o in unmatched) sb.AppendLine($"| {o.Row.Name} | {o.Row.Highway} | {GimanoJson.ToWire(o.Row.System)} | {o.Row.EntryPlaza} | {o.Method} |");
            sb.AppendLine();
        }
        if (errors.Count > 0)
        {
            sb.AppendLine("## Invalid rows").AppendLine();
            foreach (var e in errors) sb.AppendLine($"- {e}");
            sb.AppendLine();
        }
        sb.AppendLine("## Matched rows").AppendLine().AppendLine("| name | highway | system | class 1 | class 0 | method | OSM nodes |").AppendLine("|---|---|---|---|---|---|---|");
        foreach (var o in outcomes.Where(o => o.PlaceIds.Count > 0))
            sb.AppendLine($"| {o.Row.Name} | {o.Row.Highway} | {GimanoJson.ToWire(o.Row.System)} | {o.Row.Class1Myr} | {o.Row.Class0Myr} | {o.Method} | {string.Join(" ", o.PlaceIds)} |");
        return sb.ToString();
    }

    private async Task IndexSearchAsync(bool recreate, CancellationToken ct)
    {
        // A rebuild fills a new physical index and swaps the alias at the end, so search keeps working meanwhile.
        string? target = null;
        if (recreate) target = await index.BeginRebuildAsync(ct);
        else await index.EnsureIndexAsync(ct: ct);
        // Reviews/photos aggregates and community places must survive a re-import.
        var aggregates = await PlaceDocuments.AggregatesAsync(db, null, ct);
        const int batch = 2000;
        var total = 0;
        string? lastId = null;
        while (true)
        {
            var q = db.Places.AsNoTracking().OrderBy(p => p.Id).AsQueryable();
            if (lastId is not null) q = q.Where(p => string.Compare(p.Id, lastId) > 0);
            var rows = await q.Take(batch).ToListAsync(ct);
            if (rows.Count == 0) break;
            total += await index.BulkIndexAsync(rows.Select(p => SearchQueryBuilder.ToDocument(p, aggregates.GetValueOrDefault(p.Id))), target, ct);
            lastId = rows[^1].Id;
            if (total % 20000 < batch) logger.LogInformation("Indexed {Total} places…", total);
        }
        var community = await db.CommunityPlaces.AsNoTracking().ToListAsync(ct);
        total += await index.BulkIndexAsync(community.Select(p => SearchQueryBuilder.ToDocument(p, aggregates.GetValueOrDefault(p.Id))), target, ct);
        if (target is not null) await index.CompleteRebuildAsync(target, ct);
        else await index.RefreshAsync(ct);
        logger.LogInformation("OpenSearch '{Index}' now has {Total} documents ({Community} community)", index.Index, total, community.Count);
    }
}
