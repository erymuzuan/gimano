using Gimano.Core.Geo;

namespace Gimano.Core.Traffic;

/// <summary>
/// In-memory spatial index of road segments for snapping GPS pings without touching the database.
/// Struct-of-arrays with float32 coordinates (≈ 1 m precision) and a uniform grid (0.002° ≈ 220 m cells).
/// Snapping rules are identical to <see cref="SegmentSnapper"/> (≤ 25 m, heading-compatible, one-way aware).
/// </summary>
public sealed class RoadIndex
{
    public const double CellDeg = 0.002;

    private readonly long[] _ids;
    private readonly bool[] _oneway;
    private readonly int[] _start;     // index into _coords (pairs) of segment i; _start[n] = total
    private readonly float[] _lat;
    private readonly float[] _lng;
    private readonly Dictionary<long, int[]> _grid;

    public int Count => _ids.Length;
    public int Vertices => _lat.Length;
    public int Cells => _grid.Count;

    private RoadIndex(long[] ids, bool[] oneway, int[] start, float[] lat, float[] lng, Dictionary<long, int[]> grid)
        => (_ids, _oneway, _start, _lat, _lng, _grid) = (ids, oneway, start, lat, lng, grid);

    public long SegmentId(int index) => _ids[index];

    /// <summary>Approximate managed memory of the index in bytes.</summary>
    public long EstimatedBytes =>
        _ids.Length * (8L + 1 + 4) + _lat.Length * 8L + _grid.Sum(kv => 8L + 24 + kv.Value.Length * 4L) * 3 / 2;

    public sealed class Builder
    {
        private readonly List<long> _ids = [];
        private readonly List<bool> _oneway = [];
        private readonly List<int> _start = [];
        private readonly List<float> _lat = [];
        private readonly List<float> _lng = [];

        public Builder(int capacity = 0)
        {
            _ids.Capacity = capacity; _oneway.Capacity = capacity; _start.Capacity = capacity + 1;
            _lat.Capacity = capacity * 5; _lng.Capacity = capacity * 5;
        }

        public void Add(long id, bool oneway, IReadOnlyList<GeoPoint> geometry)
        {
            if (geometry.Count < 2) return;
            _ids.Add(id);
            _oneway.Add(oneway);
            _start.Add(_lat.Count);
            foreach (var p in geometry) { _lat.Add((float)p.Lat); _lng.Add((float)p.Lng); }
        }

        public void Add(long id, bool oneway, ReadOnlySpan<double> lngLatPairs)
        {
            if (lngLatPairs.Length < 4) return;
            _ids.Add(id);
            _oneway.Add(oneway);
            _start.Add(_lat.Count);
            for (var i = 0; i + 1 < lngLatPairs.Length; i += 2) { _lng.Add((float)lngLatPairs[i]); _lat.Add((float)lngLatPairs[i + 1]); }
        }

        public RoadIndex Build()
        {
            var start = new int[_start.Count + 1];
            _start.CopyTo(start);
            start[^1] = _lat.Count;
            var lat = _lat.ToArray();
            var lng = _lng.ToArray();

            var cells = new Dictionary<long, List<int>>();
            for (var s = 0; s < _ids.Count; s++)
            {
                float minLat = float.MaxValue, maxLat = float.MinValue, minLng = float.MaxValue, maxLng = float.MinValue;
                for (var k = start[s]; k < start[s + 1]; k++)
                {
                    minLat = Math.Min(minLat, lat[k]); maxLat = Math.Max(maxLat, lat[k]);
                    minLng = Math.Min(minLng, lng[k]); maxLng = Math.Max(maxLng, lng[k]);
                }
                for (var cy = Cell(minLat); cy <= Cell(maxLat); cy++)
                    for (var cx = Cell(minLng); cx <= Cell(maxLng); cx++)
                    {
                        var key = Key(cy, cx);
                        if (!cells.TryGetValue(key, out var l)) cells[key] = l = new List<int>(4);
                        l.Add(s);
                    }
            }
            var grid = new Dictionary<long, int[]>(cells.Count);
            foreach (var (k, v) in cells) grid[k] = [.. v];
            return new RoadIndex([.. _ids], [.. _oneway], start, lat, lng, grid);
        }
    }

    private static int Cell(double deg) => (int)Math.Floor(deg / CellDeg);
    private static long Key(int cy, int cx) => ((long)cy << 32) ^ (uint)cx;

    /// <summary>Returns the snapped segment id, or null.</summary>
    public long? Snap(double lat, double lng, double? heading, double? speedKmh = null)
    {
        var useHeading = heading is >= 0 and <= 360 && (speedKmh ?? 10) >= 5;
        var cy = Cell(lat); var cx = Cell(lng);
        var cosLat = Math.Cos(GeoMath.ToRad(lat));
        const double mPerDeg = Math.PI / 180 * GeoMath.EarthRadiusM;

        var best = -1;
        var bestDist = double.MaxValue;
        Span<int> seen = stackalloc int[64];
        var seenCount = 0;

        for (var dy = -1; dy <= 1; dy++)
            for (var dx = -1; dx <= 1; dx++)
            {
                if (!_grid.TryGetValue(Key(cy + dy, cx + dx), out var segs)) continue;
                foreach (var s in segs)
                {
                    if (seen[..seenCount].Contains(s)) continue;
                    if (seenCount < seen.Length) seen[seenCount++] = s;

                    // Project onto the polyline (same maths as GeoMath.ProjectOnLine).
                    var segBest = double.MaxValue;
                    var segBearing = 0.0;
                    for (var k = _start[s] + 1; k < _start[s + 1]; k++)
                    {
                        double aLat = _lat[k - 1], aLng = _lng[k - 1], bLat = _lat[k], bLng = _lng[k];
                        var ax = (aLng - lng) * cosLat * mPerDeg; var ay = (aLat - lat) * mPerDeg;
                        var bx = (bLng - lng) * cosLat * mPerDeg; var by = (bLat - lat) * mPerDeg;
                        var ddx = bx - ax; var ddy = by - ay;
                        var len2 = ddx * ddx + ddy * ddy;
                        var t = len2 == 0 ? 0 : Math.Clamp(-(ax * ddx + ay * ddy) / len2, 0, 1);
                        var px = ax + t * ddx; var py = ay + t * ddy;
                        var d = Math.Sqrt(px * px + py * py);
                        if (d < segBest)
                        {
                            segBest = d;
                            segBearing = GeoMath.Bearing(new GeoPoint(aLat, aLng), new GeoPoint(bLat, bLng));
                        }
                    }
                    if (segBest > SegmentSnapper.MaxSnapDistanceM || segBest >= bestDist) continue;
                    if (useHeading)
                    {
                        var h = heading!.Value;
                        var forward = GeoMath.HeadingDelta(h, segBearing) <= SegmentSnapper.HeadingToleranceDeg;
                        var backward = GeoMath.HeadingDelta(h, (segBearing + 180) % 360) <= SegmentSnapper.HeadingToleranceDeg;
                        if (!(forward || (!_oneway[s] && backward))) continue;
                    }
                    best = s;
                    bestDist = segBest;
                }
            }
        return best < 0 ? null : _ids[best];
    }
}
