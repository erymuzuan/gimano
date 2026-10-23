using System.Text.Json;
using Gimano.Api;
using Gimano.Api.Admin;
using Gimano.Api.Auth;
using Gimano.Api.Endpoints;
using Gimano.Api.Health;
using Gimano.Api.Live;
using Gimano.Api.Services;
using Gimano.Core.BackOffice;
using Gimano.Core.Community;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Infrastructure;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Diagnostics.HealthChecks;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Diagnostics.HealthChecks;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;
using Scalar.AspNetCore;

var builder = WebApplication.CreateBuilder(args);
var config = builder.Configuration;

builder.Services.AddSingleton(TimeProvider.System);
builder.Services.ConfigureHttpJsonOptions(o => GimanoJson.Configure(o.SerializerOptions));
builder.Services.AddProblemDetails(o => o.CustomizeProblemDetails = c =>
    c.ProblemDetails.Extensions["traceId"] = c.HttpContext.TraceIdentifier);
builder.Services.AddExceptionHandler<ApiExceptionHandler>();
builder.Services.AddOpenApi();
builder.Services.AddMemoryCache();
builder.Services.AddCors(o => o.AddDefaultPolicy(p => p.AllowAnyOrigin().AllowAnyHeader().AllowAnyMethod()));

// Infrastructure
builder.Services.AddGimanoData(config).AddGimanoSearch(config).AddGimanoMessaging(config);
builder.Services.AddHttpClient<ValhallaClient>(c =>
{
    c.BaseAddress = new Uri((config["Valhalla:Url"] ?? "http://localhost:8002").TrimEnd('/') + "/");
    c.Timeout = TimeSpan.FromSeconds(20);
});
builder.Services.AddScoped<GeoLookup>().AddScoped<ReportService>().AddScoped<RouteService>().AddScoped<PlaceService>()
    .AddScoped<CommunityService>().AddScoped<ReviewService>().AddScoped<PhotoService>();
builder.Services.AddGimanoMedia(config);

// Feedback, crash reports and the back office (docs/back-office.md). Without Contact:EncryptionKey, volunteered
// contact details are dropped rather than stored.
builder.Services.Configure<ContactOptions>(config.GetSection(ContactOptions.Section));
if (!string.IsNullOrWhiteSpace(config[$"{ContactOptions.Section}:EncryptionKey"])) builder.Services.AddSingleton<ContactProtector>();
builder.Services.AddScoped<ReportIntake>().AddScoped<TriageService>();
builder.Services.Configure<AdminOptions>(config.GetSection(AdminOptions.Section));
builder.Services.AddHttpContextAccessor();
builder.Services.AddMcpServer(o => o.ServerInfo = new() { Name = "gimano-backoffice", Version = "1.0.0" })
    .WithHttpTransport(o => o.Stateless = true)
    .WithTools<BackOfficeMcpTools>();

// Auth: anonymous device JWTs (HS256)
builder.Services.Configure<JwtOptions>(config.GetSection(JwtOptions.Section));
builder.Services.AddSingleton<JwtTokenService>();
builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme).AddJwtBearer()
    .AddScheme<AuthenticationSchemeOptions, AgentTokenAuthenticationHandler>(AgentTokenAuthenticationHandler.SchemeName, null);
builder.Services.AddOptions<JwtBearerOptions>(JwtBearerDefaults.AuthenticationScheme)
    .Configure<IOptions<JwtOptions>>((o, jwt) =>
    {
        var j = jwt.Value;
        if (j.SigningKey.Length < 32) throw new InvalidOperationException("Jwt:SigningKey must be at least 32 characters.");
        o.MapInboundClaims = false;
        o.TokenValidationParameters = new TokenValidationParameters
        {
            ValidIssuer = j.Issuer, ValidAudience = j.Audience, IssuerSigningKey = j.Key(),
            ValidateIssuerSigningKey = true, ClockSkew = TimeSpan.FromMinutes(2), NameClaimType = "name",
        };
        // SignalR WebSockets carry the token in the query string.
        o.Events = new JwtBearerEvents
        {
            OnMessageReceived = ctx =>
            {
                if (ctx.Request.Path.StartsWithSegments("/hubs") && ctx.Request.Query["access_token"] is { Count: > 0 } t)
                    ctx.Token = t;
                return Task.CompletedTask;
            },
        };
    });
builder.Services.AddAuthorization(o => o.AddPolicy(AgentTokenAuthenticationHandler.Policy, p => p
    .AddAuthenticationSchemes(AgentTokenAuthenticationHandler.SchemeName).RequireAuthenticatedUser().RequireClaim(AgentTokenAuthenticationHandler.AgentClaim)));

// Live
builder.Services.AddSignalR().AddJsonProtocol(o => GimanoJson.Configure(o.PayloadSerializerOptions));
builder.Services.AddSingleton<LiveSubscriptions>();
builder.Services.AddHostedService<LiveFanoutConsumer>();
builder.Services.AddSingleton<MoodCache>();
builder.Services.AddSingleton<PingSessions>();
builder.Services.AddSingleton<DriverSnapshotStore>();
builder.Services.AddHostedService<DriverSnapshotConsumer>();
builder.Services.AddSingleton<LocationStreamPublisher>();
builder.Services.AddHostedService(sp => sp.GetRequiredService<LocationStreamPublisher>());

// Health
builder.Services.AddHealthChecks()
    .AddCheck<PostgresHealthCheck>("postgres", HealthStatus.Unhealthy, ["ready"])
    .AddCheck<OpenSearchHealthCheck>("opensearch", HealthStatus.Degraded, ["ready"])
    .AddCheck<RabbitMqHealthCheck>("rabbitmq", HealthStatus.Degraded, ["ready"])
    .AddCheck<ValhallaHealthCheck>("valhalla", HealthStatus.Degraded, ["ready"]);

var app = builder.Build();

if (config.GetValue("Database:MigrateOnStartup", true))
{
    await using var scope = app.Services.CreateAsyncScope();
    var db = scope.ServiceProvider.GetRequiredService<GimanoDbContext>();
    var log = scope.ServiceProvider.GetRequiredService<ILogger<Program>>();
    for (var attempt = 1; ; attempt++)
    {
        try
        {
            await db.Database.MigrateAsync();
            break;
        }
        catch (Exception ex) when (attempt < 60 && ex is Npgsql.NpgsqlException or System.Net.Sockets.SocketException or InvalidOperationException)
        {
            log.LogWarning("Database not ready ({Message}); retry {Attempt}/60", ex.Message, attempt);
            await Task.Delay(TimeSpan.FromSeconds(3));
        }
    }
}

app.UseExceptionHandler();
app.UseStatusCodePages();
app.UseCors();
app.UseMiddleware<AdminAccessMiddleware>();
app.UseAuthentication();
app.UseAuthorization();

app.MapOpenApi();
app.MapScalarApiReference();

app.MapHealthChecks("/healthz", new HealthCheckOptions { Predicate = _ => false, ResponseWriter = HealthWriter.WriteAsync });
app.MapHealthChecks("/readyz", new HealthCheckOptions { Predicate = c => c.Tags.Contains("ready"), ResponseWriter = HealthWriter.WriteAsync });

var api = app.MapGroup("/api/v1");
api.MapAuthAndMe().MapPlaces().MapRoutes().MapLive().MapFeedback().MapCrashReports().MapInbox().MapPrivacy().MapCommunity();
app.MapMedia();
app.MapAdmin();
app.MapMcp("/mcp").RequireAuthorization(AgentTokenAuthenticationHandler.Policy);

app.MapHub<LiveHub>("/hubs/live");
app.MapGet("/", () => Results.Redirect("/scalar/v1")).ExcludeFromDescription();

app.Run();

public partial class Program;
