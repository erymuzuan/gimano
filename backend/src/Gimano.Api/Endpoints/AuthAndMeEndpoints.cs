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
        me.MapPatch("/places/{id:guid}", PatchPlace);
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

    public const int MaxFavourites = 50;
    public const int MaxFavouriteNameLength = 40;

    private static async Task<Ok<List<SavedPlaceDto>>> GetPlaces(HttpContext http, GimanoDbContext db, CancellationToken ct)
    {
        var uid = http.User.RequiredUserId();
        var rows = await db.SavedPlaces.AsNoTracking().Where(p => p.UserId == uid).ToListAsync(ct);
        return TypedResults.Ok(Ordered(rows).Select(p => p.ToDto()).ToList());
    }

    /// <summary>Home, work first; then favourites by sortOrder (ties: oldest first).</summary>
    internal static IEnumerable<SavedPlace> Ordered(IEnumerable<SavedPlace> places) =>
        places.OrderBy(p => p.Kind switch { SavedPlaceKind.Home => 0, SavedPlaceKind.Work => 1, _ => 2 })
            .ThenBy(p => p.SortOrder).ThenBy(p => p.CreatedAt);

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
        if (Validation.FavouriteName(req.Name) is { } nameProblem) return nameProblem;
        var uid = http.User.RequiredUserId();
        var favourites = await db.SavedPlaces.Where(p => p.UserId == uid && p.Kind == SavedPlaceKind.Favorite)
            .Select(p => p.SortOrder).ToListAsync(ct);
        if (favourites.Count >= MaxFavourites)
            return Validation.Problem("favorites", $"At most {MaxFavourites} favourites; delete one first.");
        var place = new SavedPlace
        {
            UserId = uid, Kind = SavedPlaceKind.Favorite, Name = req.Name.Trim(), Icon = req.Icon ?? FavoriteIcon.Star,
            Location = new GeoPoint(req.Lat, req.Lng).ToPoint(), Address = req.Address ?? "", PlaceId = req.PlaceId,
            SortOrder = favourites.Count == 0 ? 0 : favourites.Max() + 1,
        };
        db.SavedPlaces.Add(place);
        await db.SaveChangesAsync(ct);
        return TypedResults.Created($"/api/v1/me/places/{place.Id}", place.ToDto());
    }

    private static async Task<Results<Ok<SavedPlaceDto>, NotFound, ValidationProblem>> PatchPlace(
        Guid id, PatchPlaceRequest req, HttpContext http, GimanoDbContext db, CancellationToken ct)
    {
        var uid = http.User.RequiredUserId();
        var place = await db.SavedPlaces.FirstOrDefaultAsync(p => p.Id == id && p.UserId == uid, ct);
        if (place is null) return TypedResults.NotFound();

        if (req.Name is not null)
        {
            var problem = place.Kind == SavedPlaceKind.Favorite
                ? Validation.FavouriteName(req.Name)
                : string.IsNullOrWhiteSpace(req.Name) || req.Name.Length > 200 ? Validation.Problem("name", "name is required (max 200 chars).") : null;
            if (problem is not null) return problem;
            place.Name = req.Name.Trim();
        }
        if (req.Icon is { } icon)
        {
            if (place.Kind != SavedPlaceKind.Favorite && icon != place.EffectiveIcon)
                return Validation.Problem("icon", "Home and work always use the home/work icon.");
            place.Icon = icon;
        }
        if (req.Lat is not null || req.Lng is not null)
        {
            var lat = req.Lat ?? place.Location.Y;
            var lng = req.Lng ?? place.Location.X;
            if (Validation.LatLng(lat, lng) is { } p) return p;
            place.Location = new GeoPoint(lat, lng).ToPoint();
            place.PlaceId = null; // moved: no longer the same POI
        }
        if (req.Address is not null) place.Address = req.Address;
        if (req.SortOrder is { } order)
        {
            if (order is < 0 or > 10_000) return Validation.Problem("sortOrder", "sortOrder must be 0–10000.");
            place.SortOrder = order;
        }
        await db.SaveChangesAsync(ct);
        return TypedResults.Ok(place.ToDto());
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
