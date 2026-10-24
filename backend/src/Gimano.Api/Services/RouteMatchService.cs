using Gimano.Core.Contracts;
using Gimano.Core.Routing;

namespace Gimano.Api.Services;

/// <summary>
/// POST /routes/match (docs/events.md §6): snaps a drawn line to roads. Stateless by design: no cache, no database,
/// and the points are never logged (privacy rule). 422 when nothing matched, 503 when Valhalla is down.
/// </summary>
public sealed class RouteMatchService(ValhallaClient valhalla)
{
    /// <remarks>Expects a request already validated by <c>RouteEndpoints.ValidateMatch</c>.</remarks>
    public async Task<RouteMatchResponse> MatchAsync(RouteMatchRequest req, CancellationToken ct)
    {
        var points = req.Points!.Select(p => p!).ToList();
        var response = await valhalla.TraceAttributesAsync(ValhallaMapper.BuildTraceRequest(points, req.Vehicle ?? Vehicle.Car), ct);
        return ValhallaMapper.MapTrace(response, points.Count)
            ?? throw new NoRouteException("The line could not be matched to any road.");
    }
}
