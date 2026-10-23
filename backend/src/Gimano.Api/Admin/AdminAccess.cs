using System.Net;

namespace Gimano.Api.Admin;

public sealed class AdminOptions
{
    public const string Section = "Admin";
    /// <summary>Turn the admin API and MCP endpoint off entirely on a host.</summary>
    public bool Enabled { get; set; } = true;
    /// <summary>
    /// The public HTTPS edge (Cloudflare) adds CF-Connecting-IP. Admin requests that came through it are refused unless
    /// this is true, so agents reach /admin and /mcp only over Tailscale or the LAN.
    /// </summary>
    public bool AllowPublic { get; set; }
    /// <summary>Optional CIDR allowlist for the connection's remote address (e.g. 100.64.0.0/10 for Tailscale). Empty = no check.</summary>
    public string[] AllowedNetworks { get; set; } = [];
}

/// <summary>Guards /admin and /mcp before authentication runs.</summary>
public sealed class AdminAccessMiddleware(RequestDelegate next, Microsoft.Extensions.Options.IOptionsMonitor<AdminOptions> options)
{
    public static bool IsAdminPath(PathString path) => path.StartsWithSegments("/admin") || path.StartsWithSegments("/mcp");

    public async Task InvokeAsync(HttpContext http)
    {
        if (IsAdminPath(http.Request.Path) && Refusal(http, options.CurrentValue) is { } reason)
        {
            http.Response.StatusCode = StatusCodes.Status404NotFound;   // don't advertise the endpoint
            http.Response.Headers["X-Gimano-Refused"] = reason;
            return;
        }
        await next(http);
    }

    internal static string? Refusal(HttpContext http, AdminOptions o)
    {
        if (!o.Enabled) return "disabled";
        if (!o.AllowPublic && (http.Request.Headers.ContainsKey("CF-Connecting-IP") || http.Request.Headers.ContainsKey("CF-Ray"))) return "public-edge";
        if (o.AllowedNetworks.Length > 0)
        {
            var ip = http.Connection.RemoteIpAddress;
            if (ip is null) return "network";
            if (ip.IsIPv4MappedToIPv6) ip = ip.MapToIPv4();
            if (!o.AllowedNetworks.Any(n => IPNetwork.TryParse(n, out var net) && net.Contains(ip))) return "network";
        }
        return null;
    }
}
