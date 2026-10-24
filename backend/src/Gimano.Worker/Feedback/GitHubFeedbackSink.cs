using System.Net.Http.Headers;
using System.Net.Http.Json;
using Gimano.Core.Data;
using Gimano.Core.Support;
using Gimano.Core.Messaging;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Worker.Feedback;

public sealed class GitHubFeedbackOptions
{
    public const string Section = "Feedback";
    /// <summary>"owner/repo". Empty = sink disabled (default).</summary>
    public string? GitHubRepo { get; set; }
    public string? GitHubToken { get; set; }
    /// <summary>
    /// Feedback text is free text that may contain personal details, so the public sink only runs in Development unless this
    /// is explicitly true (and the privacy policy says feedback can be published).
    /// </summary>
    public bool AllowOutsideDevelopment { get; set; }
    public bool Enabled => !string.IsNullOrWhiteSpace(GitHubRepo) && !string.IsNullOrWhiteSpace(GitHubToken);

    public bool EnabledIn(string environmentName) => Enabled && (AllowOutsideDevelopment || environmentName == "Development");
}

/// <summary>Optional: opens a GitHub issue per feedback item (off unless Feedback:GitHubRepo and Feedback:GitHubToken are set).</summary>
public sealed class GitHubFeedbackSink(
    RabbitMqConnection connection, IServiceScopeFactory scopes, IHttpClientFactory http, IEventPublisher bus,
    Microsoft.Extensions.Options.IOptions<GitHubFeedbackOptions> options, ILogger<GitHubFeedbackSink> logger)
    : RabbitConsumer<FeedbackCreatedEvent>(connection, logger)
{
    protected override string QueueName => "gimano.worker.feedback";
    protected override IReadOnlyList<string> RoutingKeyBindings => [RoutingKeys.FeedbackCreated];
    protected override ushort Prefetch => 5;

    protected override async Task HandleAsync(FeedbackCreatedEvent message, string routingKey, CancellationToken ct)
    {
        await using var scope = scopes.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<GimanoDbContext>();
        var fb = await db.Feedback.FirstOrDefaultAsync(f => f.Id == message.FeedbackId, ct);
        if (fb is null || fb.ExternalUrl is not null) return;

        var o = options.Value;
        var (title, body, labels) = FeedbackIssueFormatter.Format(fb);
        var client = http.CreateClient("github");
        using var req = new HttpRequestMessage(HttpMethod.Post, $"https://api.github.com/repos/{o.GitHubRepo}/issues")
        {
            Content = JsonContent.Create(new { title, body, labels }),
        };
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", o.GitHubToken);
        req.Headers.UserAgent.ParseAdd("gimano-worker");
        req.Headers.Accept.ParseAdd("application/vnd.github+json");
        using var resp = await client.SendAsync(req, ct);
        if (!resp.IsSuccessStatusCode)
        {
            logger.LogWarning("GitHub issue for feedback {Id} failed: {Status}", fb.Id, (int)resp.StatusCode);
            return;
        }
        var created = await resp.Content.ReadFromJsonAsync<GitHubIssue>(ct);
        fb.ExternalUrl = created?.HtmlUrl;
        fb.Status = Core.Contracts.FeedbackStatus.Triaged;
        await db.SaveChangesAsync(ct);
        await bus.PublishAsync(RoutingKeys.FeedbackStatusChanged, new FeedbackStatusEvent(fb.Id, fb.Status), ct);
        logger.LogInformation("Feedback {Id} → {Url}", fb.Id, fb.ExternalUrl);
    }

    private sealed record GitHubIssue([property: System.Text.Json.Serialization.JsonPropertyName("html_url")] string HtmlUrl);
}
