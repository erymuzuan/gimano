using Gimano.BackOffice.Components;
using Gimano.BackOffice.Components.Account;
using Gimano.BackOffice.Data;
using Gimano.Core.BackOffice;
using Gimano.Core.Data;
using Gimano.Core.Infrastructure;
using Microsoft.AspNetCore.Components.Authorization;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;

var builder = WebApplication.CreateBuilder(args);
var config = builder.Configuration;

builder.Services.AddRazorComponents()
    .AddInteractiveServerComponents();

builder.Services.AddSingleton(TimeProvider.System);

// Gimano data: the same Postgres as the API. The API owns and migrates the main schema.
builder.Services.AddGimanoData(config);
builder.Services.Configure<ContactOptions>(config.GetSection(ContactOptions.Section));
if (!string.IsNullOrWhiteSpace(config[$"{ContactOptions.Section}:EncryptionKey"])) builder.Services.AddSingleton<ContactProtector>();
builder.Services.AddScoped<TriageService>();

// Maintainer accounts: Identity with passkeys / TOTP, in the "backoffice" schema.
builder.Services.AddCascadingAuthenticationState();
builder.Services.AddScoped<IdentityRedirectManager>();
builder.Services.AddScoped<AuthenticationStateProvider, IdentityRevalidatingAuthenticationStateProvider>();
builder.Services.Configure<BackOfficeOptions>(config.GetSection(BackOfficeOptions.Section));
builder.Services.AddSingleton<MaintainerAllowlist>();

builder.Services.AddAuthentication(options =>
    {
        options.DefaultScheme = IdentityConstants.ApplicationScheme;
        options.DefaultSignInScheme = IdentityConstants.ExternalScheme;
    })
    .AddIdentityCookies(cookies => cookies.ApplicationCookie?.Configure(o =>
    {
        o.Cookie.Name = "gimano.backoffice";
        o.ExpireTimeSpan = TimeSpan.FromHours(12);
        o.SlidingExpiration = true;
        // Removing someone from BackOffice:Maintainers signs them out on their next request.
        o.Events.OnValidatePrincipal = async ctx =>
        {
            var allowlist = ctx.HttpContext.RequestServices.GetRequiredService<MaintainerAllowlist>();
            if (!allowlist.Allows(ctx.Principal?.Identity?.Name))
            {
                ctx.RejectPrincipal();
                await ctx.HttpContext.RequestServices.GetRequiredService<SignInManager<ApplicationUser>>().SignOutAsync();
            }
        };
    }));
builder.Services.AddAuthorization();

var connectionString = config.GetConnectionString(GimanoDb.ConnectionStringName) ?? GimanoDb.DefaultConnectionString;
builder.Services.AddDbContext<ApplicationDbContext>(o => ApplicationDbContext.Configure(o, connectionString));

builder.Services.AddIdentityCore<ApplicationUser>(options =>
    {
        // No email sender on a back office: the allowlist is the gate, not email confirmation.
        options.SignIn.RequireConfirmedAccount = false;
        options.Stores.SchemaVersion = IdentitySchemaVersions.Version3;
        options.Password.RequiredLength = 12;
        options.Lockout.MaxFailedAccessAttempts = 5;
    })
    .AddEntityFrameworkStores<ApplicationDbContext>()
    .AddSignInManager()
    .AddDefaultTokenProviders()
    .AddUserValidator<MaintainerUserValidator>();

builder.Services.AddSingleton<IEmailSender<ApplicationUser>, IdentityNoOpEmailSender>();

// Keep cookies valid across container restarts.
if (config["DataProtection:KeysPath"] is { Length: > 0 } keys)
    builder.Services.AddDataProtection().SetApplicationName("gimano-backoffice").PersistKeysToFileSystem(new DirectoryInfo(keys));

var app = builder.Build();

if (config.GetValue("Database:MigrateOnStartup", true))
{
    await using var scope = app.Services.CreateAsyncScope();
    var identity = scope.ServiceProvider.GetRequiredService<ApplicationDbContext>();
    var log = scope.ServiceProvider.GetRequiredService<ILogger<Program>>();
    for (var attempt = 1; ; attempt++)
    {
        try
        {
            await identity.Database.MigrateAsync();
            break;
        }
        catch (Exception ex) when (attempt < 60 && ex is Npgsql.NpgsqlException or System.Net.Sockets.SocketException or InvalidOperationException)
        {
            log.LogWarning("Database not ready ({Message}); retry {Attempt}/60", ex.Message, attempt);
            await Task.Delay(TimeSpan.FromSeconds(3));
        }
    }
    if (app.Services.GetRequiredService<Microsoft.Extensions.Options.IOptions<BackOfficeOptions>>().Value.Maintainers.Length == 0)
        log.LogWarning("BackOffice:Maintainers is empty: nobody can register or sign in.");
    if (app.Services.GetService<ContactProtector>() is null)
        log.LogWarning("Contact:EncryptionKey is not set: contact details cannot be revealed on this host.");
}

if (!app.Environment.IsDevelopment())
{
    app.UseExceptionHandler("/Error", createScopeForErrors: true);
}
app.UseStatusCodePagesWithReExecute("/not-found", createScopeForStatusCodePages: true);

app.UseAntiforgery();

app.MapStaticAssets();
app.MapRazorComponents<App>()
    .AddInteractiveServerRenderMode();

// Add additional endpoints required by the Identity /Account Razor components.
app.MapAdditionalIdentityEndpoints();

// Feedback screenshots for maintainers (never cached, never public).
app.MapGet("/screenshots/{id:guid}", async (Guid id, HttpContext http, TriageService triage, CancellationToken ct) =>
{
    if (await triage.GetScreenshotAsync(id, Actor.Maintainer(http.User.Identity!.Name!), ct) is not { } s) return Results.NotFound();
    http.Response.Headers.CacheControl = "no-store";
    return Results.File(s.Bytes, s.ContentType);
}).RequireAuthorization();

app.MapGet("/healthz", () => Results.Ok("ok")).AllowAnonymous();

app.Run();

public partial class Program;
