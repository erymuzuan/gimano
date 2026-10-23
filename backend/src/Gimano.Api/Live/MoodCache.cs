using System.Collections.Concurrent;
using System.Security.Claims;

namespace Gimano.Api.Live;

/// <summary>
/// userId → mood for the location hot path, so POST /locations never touches the database.
/// Filled on sign-in and PATCH /me; falls back to the token's <c>mood</c> claim, then "happy".
/// </summary>
public sealed class MoodCache
{
    public const string Claim = "mood";
    private readonly ConcurrentDictionary<Guid, string> _moods = new();

    public void Set(Guid userId, string mood) => _moods[userId] = mood;

    public string Get(Guid userId, ClaimsPrincipal? user = null) =>
        _moods.TryGetValue(userId, out var m) ? m : user?.FindFirst(Claim)?.Value is { Length: > 0 } c ? c : "happy";
}
