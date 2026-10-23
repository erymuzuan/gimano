using Gimano.Api.Auth;
using Gimano.Api.Services;
using Gimano.Core.Contracts;
using Gimano.Core.Geo;
using Microsoft.AspNetCore.Http.HttpResults;

namespace Gimano.Api.Endpoints;

public static class RouteEndpoints
{
    public static RouteGroupBuilder MapRoutes(this RouteGroupBuilder api)
    {
        var routes = api.MapGroup("/routes").WithTags("Routing").AllowAnonymous();
        routes.MapPost("", Route).WithSummary("Routes via Valhalla, decorated with alerts and live traffic. 503 when Valhalla is down.");
        routes.MapPost("/{id}/reroute", Reroute);
        routes.MapGet("/departure-times", DepartureTimes);
        return api;
    }

    private static async Task<Results<Ok<RoutesResponse>, ValidationProblem>> Route(
        RouteRequest req, HttpContext http, RouteService svc, CancellationToken ct)
    {
        if (req.Origin is null || req.Destination is null) return Validation.Problem("origin", "origin and destination are required.");
        if (Validation.LatLng(req.Origin.Lat, req.Origin.Lng, "origin.") is { } p1) return p1;
        if (Validation.LatLng(req.Destination.Lat, req.Destination.Lng, "destination.") is { } p2) return p2;
        if (req.Waypoints is { Count: > 5 }) return Validation.Problem("waypoints", "At most 5 waypoints.");
        if (req.Waypoints?.Any(w => w is null || w.Name is { Length: > 100 }) == true) return Validation.Problem("waypoints", "Waypoint names are max 100 chars.");
        if (req.Waypoints?.Any(w => !Validation.ValidLatLng(w.Lat, w.Lng)) == true) return Validation.Problem("waypoints", "Invalid waypoint.");
        if (req.Alternatives is < 0 or > 3) return Validation.Problem("alternatives", "alternatives must be 0–3.");

        var routes = await svc.RouteAsync(req, http.User.UserId(), ct, lang: http.Lang());
        return TypedResults.Ok(new RoutesResponse(routes));
    }

    private static async Task<Results<Ok<RoutesResponse>, ValidationProblem>> Reroute(
        string id, RerouteRequest req, HttpContext http, RouteService svc, CancellationToken ct)
    {
        if (Validation.LatLng(req.Lat, req.Lng) is { } p) return p;
        if (req.NextStopIndex is < 0 or > 5) return Validation.Problem("nextStopIndex", "nextStopIndex must be 0–5.");
        return TypedResults.Ok(new RoutesResponse(await svc.RerouteAsync(id, req, http.User.UserId(), ct, http.Lang())));
    }

    private static async Task<Results<Ok<DepartureTimesResponse>, ValidationProblem>> DepartureTimes(
        double originLat, double originLng, double destLat, double destLng, string? date, string? vehicle,
        RouteService svc, CancellationToken ct)
    {
        if (Validation.LatLng(originLat, originLng, "origin") is { } p1) return p1;
        if (Validation.LatLng(destLat, destLng, "dest") is { } p2) return p2;
        if (!RouteService.TryParseDate(date, out var d)) return Validation.Problem("date", "date must be yyyy-MM-dd.");
        var v = Vehicle.Car;
        if (!string.IsNullOrEmpty(vehicle) && !GimanoJson.TryParseWire(vehicle, out v))
            return Validation.Problem("vehicle", "vehicle must be car, motorcycle, bicycle or walk.");
        var slots = await svc.DepartureTimesAsync(new GeoPoint(originLat, originLng), new GeoPoint(destLat, destLng), d, v, ct);
        return TypedResults.Ok(new DepartureTimesResponse(slots));
    }
}
