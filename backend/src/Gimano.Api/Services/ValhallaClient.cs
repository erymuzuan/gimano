using System.Net;
using System.Text;
using System.Text.Json.Nodes;
using Gimano.Core.Routing;

namespace Gimano.Api.Services;

public sealed class RoutingUnavailableException(string message, Exception? inner = null) : Exception(message, inner);
public sealed class NoRouteException(string message) : Exception(message);

/// <summary>HTTP client for the Valhalla routing service (POST /route, GET /status).</summary>
public sealed class ValhallaClient(HttpClient http, ILogger<ValhallaClient> logger)
{
    public async Task<JsonNode> RouteAsync(JsonObject request, CancellationToken ct)
    {
        HttpResponseMessage resp;
        try
        {
            using var content = new StringContent(request.ToJsonString(), Encoding.UTF8, "application/json");
            resp = await http.PostAsync("route", content, ct);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException && !ct.IsCancellationRequested)
        {
            logger.LogWarning("Valhalla unreachable at {Base}: {Message}", http.BaseAddress, ex.Message);
            throw new RoutingUnavailableException($"Routing engine (Valhalla) is unreachable at {http.BaseAddress}.", ex);
        }

        using (resp)
        {
            var body = await resp.Content.ReadAsStringAsync(ct);
            if (resp.IsSuccessStatusCode) return JsonNode.Parse(body)!;

            var err = ValhallaMapper.ParseError(body);
            if (resp.StatusCode is HttpStatusCode.BadRequest or HttpStatusCode.NotFound && err is not null)
                throw new NoRouteException(err.Value.Message);
            logger.LogWarning("Valhalla returned {Status}: {Body}", (int)resp.StatusCode, body.Length > 500 ? body[..500] : body);
            throw new RoutingUnavailableException($"Routing engine returned HTTP {(int)resp.StatusCode}.");
        }
    }

    public async Task<bool> IsUpAsync(CancellationToken ct)
    {
        try
        {
            using var resp = await http.GetAsync("status", ct);
            return resp.IsSuccessStatusCode;
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        {
            return false;
        }
    }
}
