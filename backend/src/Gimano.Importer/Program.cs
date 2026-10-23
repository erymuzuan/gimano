using Gimano.Core.Infrastructure;
using Gimano.Importer;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

// Usage: Gimano.Importer [--pbf <file>] [--skip-places] [--skip-roads] [--skip-search] [--keep-index]
//   Config (env or appsettings.json): ConnectionStrings__Postgres, OpenSearch__Url
if (args.Contains("--help") || args.Contains("-h"))
{
    Console.WriteLine("""
        Gimano OSM importer
          --pbf <file>     OSM PBF extract (default: data/osm/malaysia-singapore-brunei-latest.osm.pbf or $GIMANO_PBF)
          --skip-places    don't import POIs
          --skip-roads     don't import road segments
          --skip-search    don't (re)index OpenSearch
          --keep-index     don't drop/recreate the 'places' index first
          --dry-run        parse the extract and print counts only (no database / OpenSearch needed)
          --tolls <csv>    toll tariff table (default data/tolls/my-toll-rates.csv or $GIMANO_TOLLS)
          --skip-tolls     don't (re)load toll tariffs
          --tolls-only     only (re)load toll tariffs and match them to the already-imported plazas
        """);
    return 0;
}

var builder = Host.CreateApplicationBuilder(new HostApplicationBuilderSettings { Args = args, ContentRootPath = AppContext.BaseDirectory });
builder.Configuration.AddEnvironmentVariables();
builder.Services.AddGimanoData(builder.Configuration).AddGimanoSearch(builder.Configuration);
builder.Services.AddScoped<OsmImporter>();
using var host = builder.Build();

string? Arg(string name) => args.SkipWhile(a => a != name).Skip(1).FirstOrDefault();
var tollsOnly = args.Contains("--tolls-only");
var pbf = Arg("--pbf") ?? Environment.GetEnvironmentVariable("GIMANO_PBF") ?? FindDefault("data/osm/malaysia-singapore-brunei-latest.osm.pbf");

var options = new ImportOptions(
    pbf,
    Places: !tollsOnly && !args.Contains("--skip-places"),
    Roads: !tollsOnly && !args.Contains("--skip-roads"),
    Search: !tollsOnly && !args.Contains("--skip-search"),
    RecreateIndex: !args.Contains("--keep-index"),
    DryRun: args.Contains("--dry-run"),
    TollsCsv: args.Contains("--skip-tolls") ? null : Arg("--tolls") ?? Environment.GetEnvironmentVariable("GIMANO_TOLLS") ?? FindDefault("data/tolls/my-toll-rates.csv"));

using var cts = new CancellationTokenSource();
Console.CancelKeyPress += (_, e) => { e.Cancel = true; cts.Cancel(); };

await using var scope = host.Services.CreateAsyncScope();
await scope.ServiceProvider.GetRequiredService<OsmImporter>().RunAsync(options, cts.Token);
return 0;

static string FindDefault(string rel)
{
    for (var dir = new DirectoryInfo(Directory.GetCurrentDirectory()); dir is not null; dir = dir.Parent)
    {
        var candidate = Path.Combine(dir.FullName, rel);
        if (File.Exists(candidate)) return candidate;
    }
    return rel;
}
