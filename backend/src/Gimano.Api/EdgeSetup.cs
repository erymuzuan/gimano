using System.Net;
using System.Threading.RateLimiting;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.AspNetCore.RateLimiting;

namespace Gimano.Api;

/// <summary>
/// Running behind Cloudflare → IIS ARR (172.30.30.13) → Docker: forwarded headers from trusted proxies only,
/// per-client-IP rate limiting (CF-Connecting-IP when the request came through a trusted proxy), CORS, size limits.
/// </summary>
public static class EdgeSetup
{
    public const string SharesCorsPolicy = "shares";
    private const string PeerItem = "gimano.peer";

    public static IReadOnlyList<IPAddress> TrustedProxies(IConfiguration config) =>
        (config["Proxy:TrustedProxies"] ?? "")
            .Split([',', ';', ' '], StringSplitOptions.RemoveEmptyEntries)
            .Select(s => IPAddress.TryParse(s, out var ip) ? ip : null)
            .Where(ip => ip is not null).Cast<IPAddress>().ToList();

    public static IServiceCollection AddGimanoEdge(this IServiceCollection services, IConfiguration config, IHostEnvironment env)
    {
        var proxies = TrustedProxies(config);
        services.Configure<ForwardedHeadersOptions>(o =>
        {
            o.ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto | ForwardedHeaders.XForwardedHost;
            o.KnownProxies.Clear();
            o.KnownIPNetworks.Clear(); // only the configured proxies are trusted (not the default loopback range)
            foreach (var p in proxies) o.KnownProxies.Add(p);
            o.ForwardLimit = 1;
        });

        services.AddCors(o =>
        {
            // Native apps don't need CORS. The only browser client is the "Share my drive" viewer (GET /shares/{id}).
            var origins = (config["Cors:ShareViewerOrigins"] ?? "https://www.bukitbunga.com")
                .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
            o.AddPolicy(SharesCorsPolicy, p => p.WithOrigins(origins).WithMethods("GET").AllowAnyHeader());
            if (env.IsDevelopment()) o.AddDefaultPolicy(p => p.AllowAnyOrigin().AllowAnyHeader().AllowAnyMethod());
        });

        var perMinute = config.GetValue("RateLimit:PermitsPerMinute", 600);
        services.AddRateLimiter(o =>
        {
            o.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
            o.GlobalLimiter = PartitionedRateLimiter.Create<HttpContext, string>(ctx =>
                ctx.Request.Path.StartsWithSegments("/healthz") || ctx.Request.Path.StartsWithSegments("/readyz")
                    ? RateLimitPartition.GetNoLimiter("health")
                    : RateLimitPartition.GetTokenBucketLimiter(ClientKey(ctx, proxies), _ => new TokenBucketRateLimiterOptions
                    {
                        TokenLimit = Math.Max(20, perMinute / 4),
                        TokensPerPeriod = Math.Max(1, perMinute / 6),
                        ReplenishmentPeriod = TimeSpan.FromSeconds(10),
                        QueueLimit = 0,
                        AutoReplenishment = true,
                    }));
            o.OnRejected = (c, _) =>
            {
                c.HttpContext.Response.Headers.RetryAfter = "10";
                return ValueTask.CompletedTask;
            };
        });
        return services;
    }

    /// <summary>Remembers the TCP peer before UseForwardedHeaders rewrites RemoteIpAddress.</summary>
    public static IApplicationBuilder UseGimanoEdge(this IApplicationBuilder app)
    {
        app.Use((ctx, next) =>
        {
            ctx.Items[PeerItem] = ctx.Connection.RemoteIpAddress;
            return next(ctx);
        });
        app.UseForwardedHeaders();
        return app;
    }

    /// <summary>
    /// Rate-limit key: CF-Connecting-IP only if the TCP peer is a trusted proxy (so clients can't spoof it),
    /// else the (forwarded) remote IP.
    /// </summary>
    internal static string ClientKey(HttpContext ctx, IReadOnlyList<IPAddress> proxies)
    {
        var peer = ctx.Items[PeerItem] as IPAddress ?? ctx.Connection.RemoteIpAddress;
        if (peer is not null && proxies.Any(p => p.Equals(peer.IsIPv4MappedToIPv6 ? peer.MapToIPv4() : peer))
            && ctx.Request.Headers["CF-Connecting-IP"].ToString() is { Length: > 0 } cf && IPAddress.TryParse(cf, out var cfIp))
            return cfIp.ToString();
        return ctx.Connection.RemoteIpAddress?.ToString() ?? "unknown";
    }
}
