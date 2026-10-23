using Gimano.Api.Auth;
using Gimano.Api.Services;
using Gimano.Core.Alerts;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Geo;
using Gimano.Core.Messaging;
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Api.Endpoints;

public static class LiveEndpoints
{
    public const int MaxPointsPerBatch = 12;
    private const double MaxBBoxDegrees = 2.0;

    public static RouteGroupBuilder MapLive(this RouteGroupBuilder api)
    {
        api.MapGet("/live", Live).WithTags("Live").AllowAnonymous()
            .WithSummary("Alerts, jams and anonymised drivers in a bbox (minLng,minLat,maxLng,maxLat).");

        var reports = api.MapGroup("/reports").WithTags("Reports").RequireAuthorization();
        reports.MapPost("", CreateReport);
        reports.MapPost("/{id:guid}/votes", Vote);
        reports.MapPost("/{id:guid}/thanks", Thank);

        api.MapPost("/locations", Locations).WithTags("Live").RequireAuthorization();
        return api;
    }

    private static async Task<Results<Ok<LiveResponse>, ValidationProblem>> Live(
        string? bbox, string? types, ReportService reports, GeoLookup geo, TimeProvider clock, CancellationToken ct)
    {
        if (!BBox.TryParse(bbox, out var b)) return Validation.Problem("bbox", "bbox must be minLng,minLat,maxLng,maxLat.");
        if (b.MaxLng - b.MinLng > MaxBBoxDegrees || b.MaxLat - b.MinLat > MaxBBoxDegrees)
            return Validation.Problem("bbox", $"bbox is too large (max {MaxBBoxDegrees}° per side).");

        var wanted = (types ?? "alerts,jams,drivers").Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
        IReadOnlyList<ReportDto> alerts = wanted.Contains("alerts") ? await reports.ActiveInBBoxAsync(b, ct) : [];
        IReadOnlyList<JamDto> jams = wanted.Contains("jams") ? (await geo.JamsInBBoxAsync(b, ct)).Select(j => j.ToDto()).ToList() : [];
        IReadOnlyList<DriverDto> drivers = wanted.Contains("drivers") ? await geo.DriversInBBoxAsync(b, ct) : [];
        return TypedResults.Ok(new LiveResponse(alerts, jams, drivers, clock.GetUtcNow()));
    }

    private static async Task<Results<Created<ReportDto>, ValidationProblem>> CreateReport(
        CreateReportRequest req, HttpContext http, ReportService svc, CancellationToken ct)
    {
        if (Validation.LatLng(req.Lat, req.Lng) is { } p) return p;
        if (AlertRules.ValidateSubtype(req.Type, req.Subtype) is { } err) return Validation.Problem("subtype", err);
        if (req.Comment is { Length: > 500 }) return Validation.Problem("comment", "comment max 500 chars.");
        if (req.Heading is < 0 or > 360) return Validation.Problem("heading", "heading must be 0–360.");

        var (report, _) = await svc.CreateAsync(http.User.RequiredUserId(), req, ct);
        return TypedResults.Created($"/api/v1/reports/{report.Id}", report);
    }

    private static async Task<Ok<ReportDto>> Vote(Guid id, VoteRequest req, HttpContext http, ReportService svc, CancellationToken ct) =>
        TypedResults.Ok(await svc.VoteAsync(id, http.User.RequiredUserId(), req.StillThere, ct));

    private static async Task<NoContent> Thank(Guid id, HttpContext http, ReportService svc, CancellationToken ct)
    {
        await svc.ThankAsync(id, http.User.RequiredUserId(), ct);
        return TypedResults.NoContent();
    }

    private static async Task<Results<Accepted, ValidationProblem>> Locations(
        LocationBatch batch, HttpContext http, GimanoDbContext db, IEventPublisher bus, TimeProvider clock, CancellationToken ct)
    {
        if (batch.Points is not { Count: > 0 }) return Validation.Problem("points", "points is required.");
        if (batch.Points.Count > MaxPointsPerBatch) return Validation.Problem("points", $"At most {MaxPointsPerBatch} points per request.");
        if (batch.Points.Any(p => !Validation.ValidLatLng(p.Lat, p.Lng))) return Validation.Problem("points", "Invalid coordinates.");

        var uid = http.User.RequiredUserId();
        var mood = await db.Users.Where(u => u.Id == uid).Select(u => u.Mood).FirstOrDefaultAsync(ct) ?? "happy";
        var now = clock.GetUtcNow();
        foreach (var p in batch.Points.OrderBy(p => p.Ts ?? now))
        {
            // Inaccurate fixes pollute the traffic model.
            if (p.AccuracyM is > 50) continue;
            var ts = p.Ts is { } t && t <= now.AddMinutes(1) && t > now.AddMinutes(-10) ? t : now;
            await bus.PublishAsync(RoutingKeys.LocationPing,
                new LocationPingEvent(uid, p.Lat, p.Lng, Math.Max(0, p.SpeedKmh ?? 0), p.Heading ?? -1, ts, mood), ct);
        }
        return TypedResults.Accepted((string?)null);
    }
}
