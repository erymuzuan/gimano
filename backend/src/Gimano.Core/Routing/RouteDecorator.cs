using Gimano.Core.Contracts;
using Gimano.Core.Geo;
using Gimano.Core.Tolls;
using Gimano.Core.Traffic;

namespace Gimano.Core.Routing;

/// <summary>Attaches alerts and live traffic to Valhalla routes, then re-ranks them (docs/architecture.md, step 4).</summary>
public static class RouteDecorator
{
    public const double AlertCorridorM = 40;
    public const double AlertHeadingToleranceDeg = 60;
    public const double TollBoothMatchM = 25;

    public static IReadOnlyList<RouteDto> Decorate(
        IReadOnlyList<RouteCandidate> candidates,
        IReadOnlyList<ReportDto> activeReports,
        IReadOnlyList<JamLine> jams,
        Func<string> newId,
        Vehicle vehicle = Vehicle.Car,
        IReadOnlyList<TollPlaza>? tollPlazas = null,
        IReadOnlyList<TollTariff>? tariffs = null)
    {
        // Live traffic only affects motor vehicles.
        var motorised = ValhallaMapper.IsMotorised(vehicle);
        if (!motorised) jams = [];
        var scored = candidates.Select(c =>
        {
            var levels = TrafficOverlay.LevelsPerVertex(c.Points, jams);
            var segments = TrafficOverlay.ToSegments(levels);
            var trafficDuration = TrafficOverlay.TrafficDuration(c.Points, segments, c.DurationS, c.DistanceM);
            return (Candidate: c, Segments: segments, TrafficDuration: Math.Round(trafficDuration, 1));
        })
        .OrderBy(x => x.TrafficDuration)
        .ToList();

        var result = new List<RouteDto>(scored.Count);
        for (var i = 0; i < scored.Count; i++)
        {
            var (c, segments, trafficDuration) = scored[i];
            var bbox = BBox.Of(c.Points);
            var tolls = TollCalculator.Compute(c.Points, tollPlazas ?? [], tariffs ?? [], vehicle);
            result.Add(new RouteDto(
                Id: newId(),
                Label: i == 0 ? RouteLabel.Best : RouteLabel.Alternative,
                DurationS: c.DurationS,
                DistanceM: c.DistanceM,
                TrafficDurationS: trafficDuration,
                Summary: c.Summary,
                TrafficDescription: motorised ? TrafficOverlay.Describe(c.DurationS, trafficDuration, jams.Count > 0) : "Typical traffic",
                Vehicle: vehicle,
                HasTolls: motorised && (c.HasTolls || tolls.Tolls.Count > 0),
                TollEstimateMyr: tolls.EstimateMyr,
                TollFareComplete: tolls.FareComplete,
                Tolls: tolls.Tolls,
                Polyline: c.Polyline,
                Bbox: bbox.ToArray(),
                Legs: c.Legs,
                TrafficSegments: segments,
                Alerts: AlertsOnRoute(c.Points, activeReports)));
        }
        return result;
    }

    /// <summary>
    /// Valhalla only reports tolls for ways tagged toll=yes, which Malaysian OSM rarely uses; toll plazas are
    /// mapped as barrier=toll_booth nodes (imported as toll_booth places). A route has tolls if it passes within 25 m of one.
    /// </summary>
    public static bool PassesTollBooth(IReadOnlyList<GeoPoint> route, IReadOnlyList<GeoPoint> tollBooths)
    {
        if (route.Count < 2 || tollBooths.Count == 0) return false;
        var bbox = BBox.Of(route).Expand(TollBoothMatchM);
        return tollBooths.Any(t => bbox.Contains(t.Lat, t.Lng) && GeoMath.ProjectOnLine(route, t).DistanceM <= TollBoothMatchM);
    }

    /// <summary>Reports within 40 m of the route whose heading (if known) matches the direction of travel, ordered along the route.</summary>
    public static IReadOnlyList<AlertOnRoute> AlertsOnRoute(IReadOnlyList<GeoPoint> route, IEnumerable<ReportDto> reports)
    {
        if (route.Count < 2) return [];
        var bbox = BBox.Of(route).Expand(AlertCorridorM);
        var list = new List<AlertOnRoute>();
        foreach (var r in reports)
        {
            if (!bbox.Contains(r.Lat, r.Lng)) continue;
            var proj = GeoMath.ProjectOnLine(route, new GeoPoint(r.Lat, r.Lng));
            if (proj.DistanceM > AlertCorridorM) continue;
            if (r.Heading is { } h && GeoMath.HeadingDelta(h, proj.SegmentBearing) > AlertHeadingToleranceDeg
                && r.Type is not (ReportType.Closure or ReportType.Weather)) continue;
            list.Add(AlertOnRoute.From(r, proj.AlongM));
        }
        return list.OrderBy(a => a.DistanceAlongRouteM).ToList();
    }
}
