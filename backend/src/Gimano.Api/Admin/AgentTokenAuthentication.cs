using System.Security.Claims;
using System.Text.Encodings.Web;
using Gimano.Core.BackOffice;
using Microsoft.AspNetCore.Authentication;
using Microsoft.Extensions.Options;

namespace Gimano.Api.Admin;

/// <summary>
/// "Authorization: Bearer agt_…" for AI agents (admin API and MCP). Tokens are minted by a maintainer in the back office,
/// live at most 8 hours and carry scopes; only their sha256 is stored.
/// </summary>
public sealed class AgentTokenAuthenticationHandler(
    IOptionsMonitor<AuthenticationSchemeOptions> options, ILoggerFactory logger, UrlEncoder encoder, TriageService triage)
    : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
{
    public const string SchemeName = "AgentToken";
    public const string Policy = "Agent";
    public const string ScopeClaim = "scope";
    public const string AgentClaim = "gimano:agent";

    protected override async Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        var header = Request.Headers.Authorization.ToString();
        if (!header.StartsWith("Bearer " + AgentTokenFormat.Prefix, StringComparison.Ordinal)) return AuthenticateResult.NoResult();
        var actor = await triage.AuthenticateAgentAsync(header["Bearer ".Length..].Trim(), Context.RequestAborted);
        if (actor is null) return AuthenticateResult.Fail("Unknown, expired or revoked agent token.");
        var claims = new List<Claim> { new(ClaimTypes.Name, actor.Name), new(AgentClaim, "true") };
        claims.AddRange(actor.Scopes.Select(s => new Claim(ScopeClaim, s)));
        var principal = new ClaimsPrincipal(new ClaimsIdentity(claims, SchemeName));
        return AuthenticateResult.Success(new AuthenticationTicket(principal, SchemeName));
    }
}

public static class AgentPrincipal
{
    public static Actor ToActor(this ClaimsPrincipal user)
    {
        if (user.FindFirst(AgentTokenAuthenticationHandler.AgentClaim) is null || user.Identity?.Name is not { } name)
            throw new UnauthorizedAccessException("An agent token is required.");
        return Actor.Agent(name, user.FindAll(AgentTokenAuthenticationHandler.ScopeClaim).Select(c => c.Value));
    }
}
