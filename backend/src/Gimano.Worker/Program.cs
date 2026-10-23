using Gimano.Core.Community;
using Gimano.Core.Infrastructure;
using Gimano.Worker.Alerts;
using Gimano.Worker.Feedback;
using Gimano.Worker.Search;
using Gimano.Worker.Traffic;

var builder = Host.CreateApplicationBuilder(args);

builder.Services.AddSingleton(TimeProvider.System);
builder.Services.AddGimanoData(builder.Configuration).AddGimanoSearch(builder.Configuration).AddGimanoMessaging(builder.Configuration);

builder.Services.AddSingleton<SpeedWindow>();
builder.Services.AddSingleton<TrafficMetrics>();
builder.Services.AddSingleton<DriverStore>();
builder.Services.AddSingleton<RoadIndexHolder>();
builder.Services.AddHostedService(sp => sp.GetRequiredService<RoadIndexHolder>());
builder.Services.AddHostedService<LocationStreamConsumer>();
builder.Services.AddHostedService<DriverSnapshotPublisher>();
builder.Services.AddHostedService<JamPublisher>();
builder.Services.AddHostedService<AlertLifecycle>();
builder.Services.AddHostedService<AlertExpirySweeper>();
builder.Services.AddHostedService<SearchIndexer>();
builder.Services.AddGimanoMedia(builder.Configuration);
builder.Services.AddHostedService<Gimano.Worker.Photos.PhotoProcessor>();
builder.Services.AddHostedService<Gimano.Worker.Inbox.InboxGenerator>();
builder.Services.AddHostedService<Gimano.Worker.Inbox.InboxRetention>();

builder.Services.Configure<GitHubFeedbackOptions>(builder.Configuration.GetSection(GitHubFeedbackOptions.Section));
if (builder.Configuration.GetSection(GitHubFeedbackOptions.Section).Get<GitHubFeedbackOptions>() is { Enabled: true })
{
    builder.Services.AddHttpClient("github", c => c.Timeout = TimeSpan.FromSeconds(20));
    builder.Services.AddHostedService<GitHubFeedbackSink>();
}

builder.Build().Run();
