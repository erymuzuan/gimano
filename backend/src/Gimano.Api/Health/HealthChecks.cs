using System.Text.Json;
using Gimano.Api.Services;
using Gimano.Core.Data;
using Gimano.Core.Messaging;
using Gimano.Core.Search;
using Microsoft.Extensions.Diagnostics.HealthChecks;

namespace Gimano.Api.Health;

public sealed class PostgresHealthCheck(GimanoDbContext db) : IHealthCheck
{
    public async Task<HealthCheckResult> CheckHealthAsync(HealthCheckContext context, CancellationToken ct = default) =>
        await db.Database.CanConnectAsync(ct) ? HealthCheckResult.Healthy() : HealthCheckResult.Unhealthy("Cannot connect to PostgreSQL.");
}

public sealed class OpenSearchHealthCheck(PlaceSearchIndex index) : IHealthCheck
{
    public async Task<HealthCheckResult> CheckHealthAsync(HealthCheckContext context, CancellationToken ct = default) =>
        await index.PingAsync(ct) ? HealthCheckResult.Healthy() : new HealthCheckResult(context.Registration.FailureStatus, "OpenSearch ping failed (search falls back to Postgres).");
}

public sealed class RabbitMqHealthCheck(RabbitMqConnection connection) : IHealthCheck
{
    public async Task<HealthCheckResult> CheckHealthAsync(HealthCheckContext context, CancellationToken ct = default)
    {
        try
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(TimeSpan.FromSeconds(3));
            var c = await connection.GetAsync(cts.Token);
            return c.IsOpen ? HealthCheckResult.Healthy() : new HealthCheckResult(context.Registration.FailureStatus, "RabbitMQ connection closed.");
        }
        catch (Exception ex)
        {
            return new HealthCheckResult(context.Registration.FailureStatus, $"RabbitMQ unavailable: {ex.Message}");
        }
    }
}

public sealed class ValhallaHealthCheck(ValhallaClient valhalla) : IHealthCheck
{
    public async Task<HealthCheckResult> CheckHealthAsync(HealthCheckContext context, CancellationToken ct = default) =>
        await valhalla.IsUpAsync(ct) ? HealthCheckResult.Healthy() : new HealthCheckResult(context.Registration.FailureStatus, "Valhalla /status unreachable (routing returns 503).");
}

public static class HealthWriter
{
    public static Task WriteAsync(HttpContext ctx, HealthReport report)
    {
        ctx.Response.ContentType = "application/json";
        var body = new
        {
            status = report.Status.ToString().ToLowerInvariant(),
            durationMs = Math.Round(report.TotalDuration.TotalMilliseconds),
            checks = report.Entries.ToDictionary(e => e.Key, e => new
            {
                status = e.Value.Status.ToString().ToLowerInvariant(),
                description = e.Value.Description,
                durationMs = Math.Round(e.Value.Duration.TotalMilliseconds),
            }),
        };
        return ctx.Response.WriteAsync(JsonSerializer.Serialize(body, new JsonSerializerOptions(JsonSerializerDefaults.Web)));
    }
}
