using Gimano.Core.BackOffice;
using Gimano.Core.Contracts;
using Gimano.Core.Domain;
using Microsoft.AspNetCore.Http.HttpResults;

namespace Gimano.Api.Admin;

public sealed record StartRunRequest(string Title, string TargetType, string TargetId);
public sealed record RunStepRequest(string Title, string? Detail);
public sealed record DraftReplyRequest(string Message, FeedbackStatus? NewStatus);

/// <summary>Every read under /admin/v1 needs the token's <c>read</c> scope (writes check their own scope in TriageService).</summary>
public sealed class RequireReadScopeFilter : IEndpointFilter
{
    public ValueTask<object?> InvokeAsync(EndpointFilterInvocationContext context, EndpointFilterDelegate next)
    {
        context.HttpContext.User.ToActor().Require(AgentScopes.Read);
        return next(context);
    }
}

/// <summary>
/// /admin/v1: the REST twin of the gimano-backoffice MCP tools, for agents holding an agt_ token (docs/back-office.md).
/// Same views as maintainers, minus contact details (no endpoint decrypts them) and unshared screenshots.
/// Not to be confused with the X-Admin-Key maintainer endpoints under /api/v1/admin (Endpoints/AdminEndpoints.cs).
/// </summary>
public static class AgentAdminEndpoints
{
    public static IEndpointRouteBuilder MapAgentAdmin(this IEndpointRouteBuilder app)
    {
        var admin = app.MapGroup("/admin/v1").WithTags("Admin (agents)").RequireAuthorization(AgentTokenAuthenticationHandler.Policy);
        var read = admin.MapGroup("").AddEndpointFilter<RequireReadScopeFilter>();

        read.MapGet("/stats", (TriageService t, CancellationToken ct) => t.StatsAsync(ct));
        read.MapGet("/queue", (string? type, int? take, TriageService t, CancellationToken ct) => t.QueueAsync(type, take ?? 100, ct))
            .WithSummary("Open feedback and crash groups in one list (type = feedback | crash).");

        read.MapGet("/feedback", (FeedbackStatus? status, FeedbackCategory? category, FeedbackScreen? screen, string? platform, string? text,
                DateTimeOffset? since, bool? openOnly, int? take, TriageService t, CancellationToken ct) =>
            t.SearchFeedbackAsync(new FeedbackQuery(status, category, screen, platform, text, since, openOnly ?? false, take ?? 50), ct));
        read.MapGet("/feedback/{id:guid}", async Task<Results<Ok<FeedbackView>, NotFound>> (Guid id, TriageService t, CancellationToken ct) =>
            await t.GetFeedbackAsync(id, ct) is { } f ? TypedResults.Ok(f) : TypedResults.NotFound());
        read.MapGet("/feedback/{id:guid}/screenshot", async Task<Results<FileContentHttpResult, NotFound>> (Guid id, HttpContext http, TriageService t, CancellationToken ct) =>
            await t.GetScreenshotAsync(id, http.User.ToActor(), ct) is { } s ? TypedResults.File(s.Bytes, s.ContentType) : TypedResults.NotFound())
            .WithSummary("Only when a maintainer shared this screenshot with agents (403 otherwise).");
        admin.MapPatch("/feedback/{id:guid}", (Guid id, FeedbackTriage body, HttpContext http, TriageService t, CancellationToken ct) =>
            t.TriageFeedbackAsync(id, body, http.User.ToActor(), ct)).WithSummary("Scope triage:write. Links must be github.com URLs.");
        admin.MapPost("/feedback/{id:guid}/replies", (Guid id, DraftReplyRequest body, HttpContext http, TriageService t, CancellationToken ct) =>
            t.DraftReplyAsync(id, body.Message, body.NewStatus, http.User.ToActor(), ct)).WithSummary("Scope replies:draft. A maintainer approves before it is sent.");

        read.MapGet("/crash-groups", (CrashGroupStatus? status, string? platform, string? text, bool? openOnly, int? take, TriageService t, CancellationToken ct) =>
            t.CrashGroupsAsync(new CrashQuery(status, platform, text, openOnly ?? false, take ?? 50), ct));
        read.MapGet("/crash-groups/{signature}", async Task<Results<Ok<CrashGroupView>, NotFound>> (string signature, TriageService t, CancellationToken ct) =>
            await t.GetCrashGroupAsync(signature, ct) is { } g ? TypedResults.Ok(g) : TypedResults.NotFound());
        read.MapGet("/crash-groups/{signature}/events", (string signature, int? take, TriageService t, CancellationToken ct) =>
            t.CrashEventsAsync(signature, take ?? 20, ct));
        read.MapGet("/crash-groups/{signature}/feedback", (string signature, TriageService t, CancellationToken ct) => t.LinkedFeedbackAsync(signature, ct));
        admin.MapPatch("/crash-groups/{signature}", (string signature, CrashTriage body, HttpContext http, TriageService t, CancellationToken ct) =>
            t.TriageCrashAsync(signature, body, http.User.ToActor(), ct)).WithSummary("Scope triage:write. status=done needs fixedInBuild.");

        read.MapGet("/builds", (string? platform, TriageService t, CancellationToken ct) => t.BuildsAsync(platform, ct));
        read.MapGet("/builds/resolve", async Task<Results<Ok<AppBuildView>, NotFound>> (string platform, int? build, string? version, TriageService t, CancellationToken ct) =>
            await t.ResolveBuildAsync(platform, build, version, ct) is { } b ? TypedResults.Ok(b) : TypedResults.NotFound())
            .WithSummary("Maps an app version/build to its git commit and symbol files.");

        read.MapGet("/runs", (string? targetType, string? targetId, TriageService t, CancellationToken ct) => t.RunsAsync(targetType, targetId, 50, ct));
        read.MapGet("/runs/{id:int}", async Task<Results<Ok<AgentRunView>, NotFound>> (int id, TriageService t, CancellationToken ct) =>
            await t.GetRunAsync(id, ct) is { } r ? TypedResults.Ok(r) : TypedResults.NotFound());
        admin.MapPost("/runs", (StartRunRequest body, HttpContext http, TriageService t, CancellationToken ct) =>
            t.StartRunAsync(body.Title, body.TargetType, body.TargetId, http.User.ToActor(), ct)).WithSummary("Scope runs:write.");
        admin.MapPost("/runs/{id:int}/steps", (int id, RunStepRequest body, HttpContext http, TriageService t, CancellationToken ct) =>
            t.LogRunStepAsync(id, body.Title, body.Detail, http.User.ToActor(), ct));
        admin.MapPatch("/runs/{id:int}", (int id, AgentRunUpdate body, HttpContext http, TriageService t, CancellationToken ct) =>
            t.UpdateRunAsync(id, body, http.User.ToActor(), ct)).WithSummary("Branch must start with agent/; an agent cannot mark a run done.");

        return app;
    }
}
