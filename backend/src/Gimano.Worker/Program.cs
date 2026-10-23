using Gimano.Core.Infrastructure;
using Gimano.Worker.Alerts;
using Gimano.Worker.Search;
using Gimano.Worker.Traffic;

var builder = Host.CreateApplicationBuilder(args);

builder.Services.AddSingleton(TimeProvider.System);
builder.Services.AddGimanoData(builder.Configuration).AddGimanoSearch(builder.Configuration).AddGimanoMessaging(builder.Configuration);

builder.Services.AddSingleton<SpeedWindow>();
builder.Services.AddHostedService<TrafficAggregator>();
builder.Services.AddHostedService<JamPublisher>();
builder.Services.AddHostedService<AlertLifecycle>();
builder.Services.AddHostedService<AlertExpirySweeper>();
builder.Services.AddHostedService<SearchIndexer>();

builder.Build().Run();
