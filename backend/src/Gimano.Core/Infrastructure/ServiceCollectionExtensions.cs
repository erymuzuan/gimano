using Gimano.Core.Data;
using Gimano.Core.Messaging;
using Gimano.Core.Search;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using OpenSearch.Net;

namespace Gimano.Core.Infrastructure;

public static class ServiceCollectionExtensions
{
    public static IServiceCollection AddGimanoData(this IServiceCollection services, IConfiguration config)
    {
        var cs = config.GetConnectionString(GimanoDb.ConnectionStringName)
                 ?? throw new InvalidOperationException("ConnectionStrings:Postgres is not configured (set ConnectionStrings__Postgres).");
        // The password is never committed: it comes from ConnectionStrings__Postgres, user-secrets, or Postgres:Password.
        if (config["Postgres:Password"] is { Length: > 0 } pw && !cs.Contains("Password=", StringComparison.OrdinalIgnoreCase))
            cs = new Npgsql.NpgsqlConnectionStringBuilder(cs) { Password = pw }.ConnectionString;
        var ds = GimanoDb.BuildDataSource(cs);
        services.AddSingleton(ds);
        services.AddDbContext<GimanoDbContext>(o => GimanoDb.Configure(o, ds));
        return services;
    }

    public static IServiceCollection AddGimanoSearch(this IServiceCollection services, IConfiguration config)
    {
        var url = config["OpenSearch:Url"] ?? "http://localhost:9200";
        services.AddSingleton<IOpenSearchLowLevelClient>(_ =>
        {
            var settings = new ConnectionConfiguration(new Uri(url))
                .RequestTimeout(TimeSpan.FromSeconds(config.GetValue("OpenSearch:TimeoutSeconds", 15)))
                .PingTimeout(TimeSpan.FromSeconds(2))
                .ThrowExceptions(false);
            var user = config["OpenSearch:User"];
            if (!string.IsNullOrEmpty(user)) settings = settings.BasicAuthentication(user, config["OpenSearch:Password"]);
            // Production runs OpenSearch with its security plugin (self-signed TLS) on an internal Docker network only.
            if (config.GetValue("OpenSearch:SkipCertificateValidation", false))
                settings = settings.ServerCertificateValidationCallback(CertificateValidations.AllowAll);
            return new OpenSearchLowLevelClient(settings);
        });
        services.AddSingleton<PlaceSearchIndex>();
        return services;
    }

    public static IServiceCollection AddGimanoMessaging(this IServiceCollection services, IConfiguration config)
    {
        services.Configure<RabbitMqOptions>(config.GetSection(RabbitMqOptions.Section));
        services.AddSingleton<RabbitMqConnection>();
        services.AddSingleton<LocationStreamConnection>();
        services.AddSingleton<IEventPublisher, RabbitEventPublisher>();
        return services;
    }
}
