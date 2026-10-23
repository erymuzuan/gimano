using Gimano.Api.Auth;
using Gimano.Api.Services;
using Gimano.Core.Community;
using Gimano.Core.Contracts;
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.AspNetCore.Mvc;

namespace Gimano.Api.Endpoints;

/// <summary>Community places, reviews, photos and the public media endpoint (docs/api.md).</summary>
public static class CommunityEndpoints
{
    public const long MaxUploadBytes = ImageProcessor.MaxBytes + 512 * 1024; // file + multipart overhead

    public static RouteGroupBuilder MapCommunity(this RouteGroupBuilder api)
    {
        var places = api.MapGroup("/places").WithTags("Community places").RequireAuthorization();
        places.MapPost("", CreatePlace).WithSummary("Add a missing place (always public: pending → published after 3 confirmations). 409 if a similar place exists within 100 m.");
        places.MapPatch("/{id}", PatchPlace).WithSummary("Owner only, community places only.");
        places.MapDelete("/{id}", DeletePlace).WithSummary("Owner only; published places become closed.");
        places.MapPost("/{id}/suggest-edit", SuggestEdit);
        places.MapPost("/{id}/verify", Verify);
        places.MapGet("/{id}/reviews", ListReviews).AllowAnonymous();
        places.MapPost("/{id}/reviews", CreateReview);
        places.MapGet("/{id}/photos", ListPhotos).AllowAnonymous();

        api.MapGet("/me/places/contributed", Contributed).WithTags("Community places").RequireAuthorization();

        var reviews = api.MapGroup("/reviews").WithTags("Reviews").RequireAuthorization();
        reviews.MapPatch("/{id:guid}", PatchReview);
        reviews.MapDelete("/{id:guid}", DeleteReview);
        reviews.MapPost("/{id:guid}/helpful", Helpful);
        reviews.MapPost("/{id:guid}/flag", FlagReview);

        var photos = api.MapGroup("/photos").WithTags("Photos").RequireAuthorization();
        photos.MapPost("", Upload).DisableAntiforgery().WithMetadata(new RequestSizeLimitAttribute(MaxUploadBytes))
            .WithSummary("multipart/form-data: file (JPEG/PNG/WebP, ≤ 15 MB), placeId, caption?. 415 for HEIC/undecodable.");
        photos.MapGet("/{id:guid}", GetPhoto).AllowAnonymous();
        photos.MapDelete("/{id:guid}", DeletePhoto);
        photos.MapPost("/{id:guid}/flag", FlagPhoto);
        return api;
    }

    /// <summary>GET /media/{key}.webp — public, immutable (content-addressed).</summary>
    public static void MapMedia(this IEndpointRouteBuilder app) =>
        app.MapGet("/media/{file}", async (string file, IMediaStore media, HttpContext http, CancellationToken ct) =>
        {
            if (!file.EndsWith(".webp", StringComparison.Ordinal) || !MediaKeys.IsValidHash(file[..^5])) return Results.NotFound();
            var bytes = await media.GetAsync(file, ct);
            if (bytes is null) return Results.NotFound();
            http.Response.Headers.CacheControl = "public, max-age=31536000, immutable";
            http.Response.Headers.ETag = $"\"{file[..^5]}\"";
            return Results.File(bytes, "image/webp");
        }).WithTags("Photos").AllowAnonymous().ExcludeFromDescription();

    private static async Task<Created<PlaceDto>> CreatePlace(CreatePlaceRequest req, HttpContext http, CommunityService svc, CancellationToken ct)
    {
        if (!Validation.ValidLatLng(req.Lat, req.Lng)) throw new ValidationException("lat", "lat/lng must be valid WGS84 coordinates.");
        var p = await svc.CreateAsync(http.User.RequiredUserId(), req, ct);
        return TypedResults.Created($"/api/v1/places/{p.Id}", p);
    }

    private static async Task<Ok<PlaceDto>> PatchPlace(string id, PatchCommunityPlaceRequest req, HttpContext http, CommunityService svc, CancellationToken ct) =>
        TypedResults.Ok(await svc.PatchAsync(http.User.RequiredUserId(), id, req, ct));

    private static async Task<NoContent> DeletePlace(string id, HttpContext http, CommunityService svc, CancellationToken ct)
    {
        await svc.DeleteAsync(http.User.RequiredUserId(), id, ct);
        return TypedResults.NoContent();
    }

    private static async Task<Accepted<SuggestionAccepted>> SuggestEdit(string id, SuggestEditRequest req, HttpContext http, CommunityService svc, CancellationToken ct) =>
        TypedResults.Accepted((string?)null, new SuggestionAccepted(await svc.SuggestEditAsync(http.User.RequiredUserId(), id, req, ct)));

    private static async Task<Ok<PlaceDto>> Verify(string id, VerifyPlaceRequest req, HttpContext http, CommunityService svc, CancellationToken ct) =>
        TypedResults.Ok(await svc.VerifyAsync(http.User.RequiredUserId(), id, req, ct));

    private static async Task<Ok<List<PlaceSummary>>> Contributed(HttpContext http, CommunityService svc, CancellationToken ct) =>
        TypedResults.Ok((await svc.ContributedAsync(http.User.RequiredUserId(), ct)).ToList());

    private static async Task<Ok<ReviewsPage>> ListReviews(string id, string? sort, string? cursor, ReviewService svc, CancellationToken ct)
    {
        var s = GimanoJson.TryParseWire<ReviewSort>(sort, out var parsed) ? parsed : ReviewSort.Recent;
        return TypedResults.Ok(await svc.ListAsync(id, s, cursor, ct));
    }

    private static async Task<Created<ReviewDto>> CreateReview(string id, CreateReviewRequest req, HttpContext http, ReviewService svc, CancellationToken ct)
    {
        var r = await svc.CreateAsync(http.User.RequiredUserId(), id, req, ct);
        return TypedResults.Created($"/api/v1/reviews/{r.Id}", r);
    }

    private static async Task<Ok<ReviewDto>> PatchReview(Guid id, PatchReviewRequest req, HttpContext http, ReviewService svc, CancellationToken ct) =>
        TypedResults.Ok(await svc.PatchAsync(http.User.RequiredUserId(), id, req, ct));

    private static async Task<NoContent> DeleteReview(Guid id, HttpContext http, ReviewService svc, CancellationToken ct)
    {
        await svc.DeleteAsync(http.User.RequiredUserId(), id, ct);
        return TypedResults.NoContent();
    }

    private static async Task<NoContent> Helpful(Guid id, HttpContext http, ReviewService svc, CancellationToken ct)
    {
        await svc.ToggleHelpfulAsync(http.User.RequiredUserId(), id, ct);
        return TypedResults.NoContent();
    }

    private static async Task<Accepted> FlagReview(Guid id, FlagRequest req, HttpContext http, ReviewService svc, CancellationToken ct)
    {
        await svc.FlagAsync(http.User.RequiredUserId(), id, req.Reason, ct);
        return TypedResults.Accepted((string?)null);
    }

    private static async Task<Created<PhotoDto>> Upload(HttpContext http, PhotoService svc, CancellationToken ct)
    {
        if (!http.Request.HasFormContentType) throw new ValidationException("file", "Use multipart/form-data with file, placeId and optional caption.");
        var form = await http.Request.ReadFormAsync(ct);
        var file = form.Files.GetFile("file") ?? throw new ValidationException("file", "file is required.");
        var placeId = form["placeId"].ToString();
        if (string.IsNullOrWhiteSpace(placeId)) throw new ValidationException("placeId", "placeId is required.");
        var caption = form["caption"].ToString();
        var photo = await svc.UploadAsync(http.User.RequiredUserId(), placeId, file, string.IsNullOrWhiteSpace(caption) ? null : caption, ct);
        return TypedResults.Created($"/api/v1/photos/{photo.Id}", photo);
    }

    private static async Task<Ok<PhotosPage>> ListPhotos(string id, string? cursor, PhotoService svc, CancellationToken ct) =>
        TypedResults.Ok(await svc.ListAsync(id, cursor, ct));

    private static async Task<Ok<PhotoDto>> GetPhoto(Guid id, PhotoService svc, CancellationToken ct) => TypedResults.Ok(await svc.GetAsync(id, ct));

    private static async Task<NoContent> DeletePhoto(Guid id, HttpContext http, PhotoService svc, CancellationToken ct)
    {
        await svc.DeleteAsync(http.User.RequiredUserId(), id, ct);
        return TypedResults.NoContent();
    }

    private static async Task<Accepted> FlagPhoto(Guid id, FlagRequest req, HttpContext http, PhotoService svc, CancellationToken ct)
    {
        await svc.FlagAsync(http.User.RequiredUserId(), id, req.Reason, ct);
        return TypedResults.Accepted((string?)null);
    }
}
