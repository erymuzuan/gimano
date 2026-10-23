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
        """);
    return 0;
}

var builder = Host.CreateApplicationBuilder(new HostApplicationBuilderSettings { Args = args, ContentRootPath = AppContext.BaseDirectory });
builder.Configuration.AddEnvironmentVariables();
builder.Services.AddGimanoData(builder.Configuration).AddGimanoSearch(builder.Configuration);
builder.Services.AddScoped<OsmImporter>();
using var host = builder.Build();

string? Arg(string name) => args.SkipWhile(a => a != name).Skip(1).FirstOrDefault();
var pbf = Arg("--pbf") ?? Environment.GetEnvironmentVariable("GIMANO_PBF") ?? FindDefaultPbf();

var options = new ImportOptions(
    pbf,
    Places: !args.Contains("--skip-places"),
    Roads: !args.Contains("--skip-roads"),
    Search: !args.Contains("--skip-search"),
    RecreateIndex: !args.Contains("--keep-index"),
    DryRun: args.Contains("--dry-run"));

using var cts = new CancellationTokenSource();
Console.CancelKeyPress += (_, e) => { e.Cancel = true; cts.Cancel(); };

await using var scope = host.Services.CreateAsyncScope();
await scope.ServiceProvider.GetRequiredService<OsmImporter>().RunAsync(options, cts.Token);
return 0;

static string FindDefaultPbf()
{
    const string rel = "data/osm/malaysia-singapore-brunei-latest.osm.pbf";
    for (var dir = new DirectoryInfo(Directory.GetCurrentDirectory()); dir is not null; dir = dir.Parent)
    {
        var candidate = Path.Combine(dir.FullName, rel);
        if (File.Exists(candidate)) return candidate;
    }
    return rel;
}
