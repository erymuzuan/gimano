using Gimano.Api.Auth;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Gimano.Core.Geo;
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Api.Endpoints;

public static class AuthAndMeEndpoints
{
    private static readonly string[] Animals =
        ["Kancil", "Harimau", "Tapir", "Kenyalang", "Penyu", "Badak", "Gajah", "Helang", "Memerang", "Pelanduk", "Beruang", "Rusa"];

    private static readonly HashSet<string> Moods =
        ["happy", "chill", "sleepy", "hungry", "in_a_hurry", "cool", "zen", "excited", "grumpy", "ninja", "party", "baby"];

    public static RouteGroupBuilder MapAuthAndMe(this RouteGroupBuilder api)
    {
        api.MapPost("/auth/device", DeviceAuth).WithTags("Auth").AllowAnonymous()
            .WithSummary("Anonymous device sign-in; returns a JWT.");

        var me = api.MapGroup("/me").WithTags("Me").RequireAuthorization();
        me.MapGet("", GetMe);
        me.MapPatch("", PatchMe);
        me.MapGet("/places", GetPlaces);
        me.MapPut("/places/{kind}", PutHomeOrWork);
        me.MapPost("/places", AddFavourite);
        me.MapDelete("/places/{id:guid}", DeletePlace);
        me.MapGet("/recents", GetRecents);
        return api;
    }

    private static async Task<Results<Ok<DeviceAuthResponse>, ValidationProblem>> DeviceAuth(
        DeviceAuthRequest req, GimanoDbContext db, JwtTokenService jwt, TimeProvider clock, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(req.DeviceId) || req.DeviceId.Length > 200)
            return Validation.Problem("deviceId", "deviceId is required (max 200 chars).");

        var user = await db.Users.FirstOrDefaultAsync(u => u.DeviceId == req.DeviceId, ct);
        if (user is null)
        {
            user = new User
            {
                DeviceId = req.DeviceId,
                Platform = req.Platform,
                Locale = string.IsNullOrWhiteSpace(req.Locale) ? "ms-MY" : req.Locale,
                DisplayName = $"{Animals[Random.Shared.Next(Animals.Length)]} {Random.Shared.Next(1000, 9999)}",
            };
            db.Users.Add(user);
        }
        user.LastSeenAt = clock.GetUtcNow();
        if (!string.IsNullOrWhiteSpace(req.Locale)) user.Locale = req.Locale;
        await db.SaveChangesAsync(ct);
        return TypedResults.Ok(new DeviceAuthResponse(jwt.Issue(user.Id, user.DisplayName), user.Id, user.DisplayName));
    }

    private static async Task<Results<Ok<UserDto>, NotFound>> GetMe(HttpContext http, GimanoDbContext db, CancellationToken ct)
    {
        var user = await db.Users.AsNoTracking().FirstOrDefaultAsync(u => u.Id == http.User.RequiredUserId(), ct);
        return user is null ? TypedResults.NotFound() : TypedResults.Ok(user.ToDto());
    }

    private static async Task<Results<Ok<UserDto>, NotFound, ValidationProblem>> PatchMe(
        PatchMeRequest req, HttpContext http, GimanoDbContext db, CancellationToken ct)
    {
        var user = await db.Users.FirstOrDefaultAsync(u => u.Id == http.User.RequiredUserId(), ct);
        if (user is null) return TypedResults.NotFound();
        if (req.DisplayName is { } name)
        {
            name = name.Trim();
            if (name.Length is < 2 or > 40) return Validation.Problem("displayName", "displayName must be 2–40 characters.");
            user.DisplayName = name;
        }
        if (req.Mood is { } mood)
        {
            if (!Moods.Contains(mood)) return Validation.Problem("mood", $"mood must be one of: {string.Join(", ", Moods)}.");
            user.Mood = mood;
        }
        if (req.Vehicle is { } v) user.Vehicle = v;
        if (!string.IsNullOrWhiteSpace(req.Locale)) user.Locale = req.Locale;
        await db.SaveChangesAsync(ct);
        return TypedResults.Ok(user.ToDto());
    }

    private static async Task<Ok<List<SavedPlaceDto>>> GetPlaces(HttpContext http, GimanoDbContext db, CancellationToken ct)
    {
        var uid = http.User.RequiredUserId();
        var rows = await db.SavedPlaces.AsNoTracking().Where(p => p.UserId == uid)
            .OrderBy(p => p.Kind).ThenBy(p => p.CreatedAt).ToListAsync(ct);
        return TypedResults.Ok(rows.Select(p => p.ToDto()).ToList());
    }

    private static async Task<Results<Ok<SavedPlaceDto>, ValidationProblem>> PutHomeOrWork(
        string kind, SavePlaceRequest req, HttpContext http, GimanoDbContext db, CancellationToken ct)
    {
        if (!GimanoJson.TryParseWire<SavedPlaceKind>(kind, out var k) || k == SavedPlaceKind.Favorite)
            return Validation.Problem("kind", "kind must be 'home' or 'work'.");
        if (Validation.SavedPlace(req) is { } problem) return problem;

        var uid = http.User.RequiredUserId();
        var place = await db.SavedPlaces.FirstOrDefaultAsync(p => p.UserId == uid && p.Kind == k, ct);
        if (place is null)
        {
            place = new SavedPlace { UserId = uid, Kind = k, Name = req.Name, Location = new GeoPoint(req.Lat, req.Lng).ToPoint() };
            db.SavedPlaces.Add(place);
        }
        place.Name = req.Name.Trim();
        place.Location = new GeoPoint(req.Lat, req.Lng).ToPoint();
        place.Address = req.Address ?? "";
        place.PlaceId = req.PlaceId;
        await db.SaveChangesAsync(ct);
        return TypedResults.Ok(place.ToDto());
    }

    private static async Task<Results<Created<SavedPlaceDto>, ValidationProblem>> AddFavourite(
        SavePlaceRequest req, HttpContext http, GimanoDbContext db, CancellationToken ct)
    {
        if (Validation.SavedPlace(req) is { } problem) return problem;
        var place = new SavedPlace
        {
            UserId = http.User.RequiredUserId(), Kind = SavedPlaceKind.Favorite, Name = req.Name.Trim(),
            Location = new GeoPoint(req.Lat, req.Lng).ToPoint(), Address = req.Address ?? "", PlaceId = req.PlaceId,
        };
        db.SavedPlaces.Add(place);
        await db.SaveChangesAsync(ct);
        return TypedResults.Created($"/api/v1/me/places/{place.Id}", place.ToDto());
    }

    private static async Task<Results<NoContent, NotFound>> DeletePlace(Guid id, HttpContext http, GimanoDbContext db, CancellationToken ct)
    {
        var uid = http.User.RequiredUserId();
        var n = await db.SavedPlaces.Where(p => p.Id == id && p.UserId == uid).ExecuteDeleteAsync(ct);
        return n == 0 ? TypedResults.NotFound() : TypedResults.NoContent();
    }

    private static async Task<Ok<List<RecentDto>>> GetRecents(HttpContext http, GimanoDbContext db, CancellationToken ct)
    {
        var uid = http.User.RequiredUserId();
        var rows = await db.Recents.AsNoTracking().Where(r => r.UserId == uid)
            .OrderByDescending(r => r.LastUsedAt).Take(20).ToListAsync(ct);
        return TypedResults.Ok(rows.Select(r => r.ToDto()).ToList());
    }
}
