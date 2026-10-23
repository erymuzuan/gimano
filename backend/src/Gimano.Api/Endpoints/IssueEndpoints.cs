using Gimano.Api.Auth;
using Gimano.Api.Identity;
using Gimano.Api.Services;
using Gimano.Core.Contracts;
using Gimano.Core.Geo;
using Microsoft.AspNetCore.Http.HttpResults;

namespace Gimano.Api.Endpoints;

/// <summary>Road and place complaints (docs/complaints.md, docs/api.md "Issues").</summary>
public static class IssueEndpoints
{
    public const double MaxBBoxDegrees = 1.0;

    public static RouteGroupBuilder MapIssues(this RouteGroupBuilder api)
    {
        var g = api.MapGroup("/issues").WithTags("Issues");
        g.MapPost("", Create).RequireAuthorization().RequireSignature()
            .WithSummary("Report a road or place problem. Road reports snap to the nearest segment + direction; a duplicate within 50 m is merged (merged: true).");
        g.MapGet("/nearby", Nearby).AllowAnonymous().WithSummary("Open issues (suspected/confirmed) in a bbox, for the map and \"Masih ditutup?\" prompts.");
        g.MapGet("/{id:guid}", Get).AllowAnonymous();
        g.MapPost("/{id:guid}/votes", Vote).RequireAuthorization().RequireSignature()
            .WithSummary("still | cleared | unsure. One per pseudonym (answering again replaces it); proximity required; 20 a day.");
        return api;
    }

    private static async Task<Created<IssueDto>> Create(CreateIssueRequest req, HttpContext http, IssueService issues, CancellationToken ct)
    {
        var dto = await issues.CreateAsync(http.User.RequiredUserId(), req, http.Lang(), ct);
        return TypedResults.Created($"/api/v1/issues/{dto.Id}", dto);
    }

    private static async Task<Results<Ok<IssuesResponse>, ValidationProblem>> Nearby(string? bbox, string? kinds, HttpContext http, IssueService issues, CancellationToken ct)
    {
        if (!BBox.TryParse(bbox, out var b)) return Validation.Problem("bbox", "bbox must be minLng,minLat,maxLng,maxLat.");
        if (b.MaxLng - b.MinLng > MaxBBoxDegrees || b.MaxLat - b.MinLat > MaxBBoxDegrees)
            return Validation.Problem("bbox", $"bbox is too large (max {MaxBBoxDegrees}° per side).");
        var list = new List<IssueKind>();
        foreach (var k in (kinds ?? "").Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
        {
            if (!GimanoJson.TryParseWire<IssueKind>(k, out var kind)) return Validation.Problem("kinds", $"Unknown kind '{k}'.");
            list.Add(kind);
        }
        return TypedResults.Ok(new IssuesResponse(await issues.NearbyAsync(b, list, http.User.UserId(), http.Lang(), ct)));
    }

    private static async Task<Results<Ok<IssueDto>, NotFound>> Get(Guid id, HttpContext http, IssueService issues, CancellationToken ct) =>
        await issues.GetAsync(id, http.User.UserId(), http.Lang(), ct) is { } dto ? TypedResults.Ok(dto) : TypedResults.NotFound();

    private static async Task<Ok<IssueVoteResult>> Vote(Guid id, IssueVoteRequest req, HttpContext http, IssueService issues, CancellationToken ct) =>
        TypedResults.Ok(await issues.VoteAsync(http.User.RequiredUserId(), id, req, ct));
}
