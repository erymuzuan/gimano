using System.Globalization;
using System.Text;
using Gimano.Core.Contracts;
using Gimano.Core.Domain;
using Gimano.Core.Geo;

namespace Gimano.Core.Tolls;

/// <summary>Reads data/tolls/my-toll-rates.csv and matches rows to OSM toll plaza nodes.</summary>
public static class TollRateCsv
{
    public static readonly string[] Header =
        ["plaza_osm_id", "name", "highway", "lat", "lng", "system", "entry_plaza", "class0_myr", "class1_myr", "effective_date", "source_url", "notes"];

    public sealed record ParseResult(IReadOnlyList<TollRate> Rows, IReadOnlyList<string> Errors);

    public static ParseResult Parse(string csv)
    {
        var rows = new List<TollRate>();
        var errors = new List<string>();
        var records = ReadRecords(csv).ToList();
        if (records.Count == 0) return new ParseResult(rows, ["empty file"]);

        var header = records[0].Select(h => h.Trim().ToLowerInvariant()).ToList();
        int Col(string name) => header.IndexOf(name);
        foreach (var required in new[] { "name", "system" })
            if (Col(required) < 0) return new ParseResult(rows, [$"missing column '{required}'"]);

        for (var i = 1; i < records.Count; i++)
        {
            var r = records[i];
            if (r.All(string.IsNullOrWhiteSpace)) continue;
            string? F(string name) { var c = Col(name); return c >= 0 && c < r.Count && !string.IsNullOrWhiteSpace(r[c]) ? r[c].Trim() : null; }
            var line = i + 1;

            var name = F("name");
            if (name is null) { errors.Add($"line {line}: name is required"); continue; }
            if (!GimanoJson.TryParseWire<TollSystem>(F("system"), out var system)) { errors.Add($"line {line}: system must be open|closed_entry|closed_exit"); continue; }
            if (system == TollSystem.ClosedExit && F("entry_plaza") is null) { errors.Add($"line {line}: closed_exit needs entry_plaza"); continue; }

            if (!TryDecimal(F("class0_myr"), out var c0) || !TryDecimal(F("class1_myr"), out var c1)) { errors.Add($"line {line}: invalid fare"); continue; }
            if (!TryDouble(F("lat"), out var lat) || !TryDouble(F("lng"), out var lng)) { errors.Add($"line {line}: invalid lat/lng"); continue; }
            DateOnly? eff = null;
            if (F("effective_date") is { } d)
            {
                if (!DateOnly.TryParseExact(d, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var dd)) { errors.Add($"line {line}: effective_date must be yyyy-mm-dd"); continue; }
                eff = dd;
            }
            if (F("source_url") is null) { errors.Add($"line {line}: source_url is required (never add an unsourced fare)"); continue; }

            rows.Add(new TollRate
            {
                PlazaOsmId = F("plaza_osm_id"), Name = name, Highway = F("highway"), Lat = lat, Lng = lng,
                System = system, EntryPlaza = F("entry_plaza"), Class0Myr = c0, Class1Myr = c1,
                EffectiveDate = eff, SourceUrl = F("source_url"), Notes = F("notes"),
            });
        }
        return new ParseResult(rows, errors);
    }

    private static bool TryDecimal(string? s, out decimal? v)
    {
        v = null;
        if (s is null) return true;
        if (!decimal.TryParse(s.Replace("RM", "", StringComparison.OrdinalIgnoreCase).Trim(), NumberStyles.Number, CultureInfo.InvariantCulture, out var d) || d < 0) return false;
        v = d;
        return true;
    }

    private static bool TryDouble(string? s, out double? v)
    {
        v = null;
        if (s is null) return true;
        if (!double.TryParse(s, NumberStyles.Float, CultureInfo.InvariantCulture, out var d)) return false;
        v = d;
        return true;
    }

    /// <summary>RFC 4180-ish: quoted fields, doubled quotes, CRLF/LF.</summary>
    public static IEnumerable<List<string>> ReadRecords(string csv)
    {
        var field = new StringBuilder();
        var record = new List<string>();
        var inQuotes = false;
        for (var i = 0; i < csv.Length; i++)
        {
            var c = csv[i];
            if (inQuotes)
            {
                if (c == '"' && i + 1 < csv.Length && csv[i + 1] == '"') { field.Append('"'); i++; }
                else if (c == '"') inQuotes = false;
                else field.Append(c);
                continue;
            }
            switch (c)
            {
                case '"': inQuotes = true; break;
                case ',': record.Add(field.ToString()); field.Clear(); break;
                case '\r': break;
                case '\n':
                    record.Add(field.ToString()); field.Clear();
                    yield return record;
                    record = [];
                    break;
                default: field.Append(c); break;
            }
        }
        if (field.Length > 0 || record.Count > 0) { record.Add(field.ToString()); yield return record; }
    }

    public sealed record MatchOutcome(TollRate Row, IReadOnlyList<string> PlaceIds, string Method);

    public const double SiblingRadiusM = 1000;
    public const double NameMatchRadiusM = 3000;

    /// <summary>
    /// Matches each row to OSM plaza nodes. A plaza is several booth nodes; all nodes sharing the normalised name
    /// within 1 km of the seed are included. Seed = <c>plaza_osm_id</c> if present, else the nodes whose normalised name
    /// equals the row's within 3 km of its lat/lng (or anywhere, if the name is unique in the country and no lat/lng is given).
    /// </summary>
    public static IReadOnlyList<MatchOutcome> Match(IReadOnlyList<TollRate> rows, IReadOnlyList<TollPlaza> plazas)
    {
        var byId = plazas.ToDictionary(p => p.Id);
        var byName = plazas.GroupBy(p => TollCalculator.NormalizeName(p.Name)).ToDictionary(g => g.Key, g => g.ToList());
        var result = new List<MatchOutcome>(rows.Count);

        foreach (var row in rows)
        {
            List<TollPlaza> seeds = [];
            var method = "unmatched";
            if (row.PlazaOsmId is { } oid && byId.TryGetValue(oid, out var seed))
            {
                seeds.Add(seed);
                method = "osm_id";
            }
            else
            {
                var key = TollCalculator.NormalizeName(row.Name);
                if (byName.TryGetValue(key, out var same))
                {
                    if (row.Lat is { } la && row.Lng is { } lo)
                    {
                        seeds = same.Where(p => GeoMath.HaversineM(la, lo, p.Lat, p.Lng) <= NameMatchRadiusM).ToList();
                        method = seeds.Count > 0 ? "name+location" : "unmatched (name found, but > 3 km from lat/lng)";
                    }
                    else if (Spread(same) <= SiblingRadiusM * 2)
                    {
                        seeds = same;
                        method = "name (unique)";
                    }
                    else method = "unmatched (ambiguous name, add lat/lng or plaza_osm_id)";
                }
                else if (row.Lat is { } la2 && row.Lng is { } lo2)
                {
                    // Fall back to the nearest plaza within 300 m whatever its name.
                    var near = plazas.Select(p => (p, d: GeoMath.HaversineM(la2, lo2, p.Lat, p.Lng))).Where(x => x.d <= 300).OrderBy(x => x.d).Select(x => x.p).FirstOrDefault();
                    if (near is not null) { seeds.Add(near); method = "location (≤ 300 m)"; }
                }
            }

            var ids = new HashSet<string>();
            foreach (var s in seeds)
            {
                ids.Add(s.Id);
                var sKey = TollCalculator.NormalizeName(s.Name);
                if (byName.TryGetValue(sKey, out var sibs))
                    foreach (var sib in sibs.Where(x => GeoMath.HaversineM(s.Lat, s.Lng, x.Lat, x.Lng) <= SiblingRadiusM)) ids.Add(sib.Id);
            }
            result.Add(new MatchOutcome(row, ids.Order().ToList(), ids.Count > 0 ? method : method.StartsWith("unmatched") ? method : "unmatched"));
        }
        return result;
    }

    private static double Spread(List<TollPlaza> ps)
    {
        double max = 0;
        for (var i = 0; i < ps.Count; i++)
            for (var j = i + 1; j < ps.Count; j++)
                max = Math.Max(max, GeoMath.HaversineM(ps[i].Lat, ps[i].Lng, ps[j].Lat, ps[j].Lng));
        return max;
    }

    public static TollTariff ToTariff(TollRate r) =>
        new(r.Name, r.Highway, r.System, r.EntryPlaza, r.Class0Myr, r.Class1Myr, r.SourceUrl, r.MatchedPlaceIds);
}
