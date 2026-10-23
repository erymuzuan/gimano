using Gimano.Core.Community;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Gimano.Core.Geo;
using Gimano.Core.Messaging;
using Gimano.Core.Places;
using Gimano.Core.Routing;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Api.Services;

public sealed class ValidationException(string field, string message) : Exception(message)
{
    public string Field { get; } = field;
}

public sealed class RateLimitedException(string message) : Exception(message);
public sealed class ForbiddenException(string message) : Exception(message);

/// <summary>409 with the existing place, so the app can ask "Is it this one?".</summary>
public sealed class DuplicatePlaceException(PlaceSummary existing) : Exception($"A similar place already exists nearby: {existing.Name}.")
{
    public PlaceSummary Existing { get; } = existing;
}

/// <summary>Community places: add (always public), owner edit/delete, verification votes, suggested edits.</summary>
public sealed class CommunityService(GimanoDbContext db, IEventPublisher bus, TimeProvider clock)
{
    private static readonly TextFilter Filter = TextFilter.Default;

    public async Task<PlaceDto> CreateAsync(Guid userId, CreatePlaceRequest req, CancellationToken ct)
    {
        var user = await RequireUserAsync(userId, ct);
        var name = CheckName(req.Name);
        CheckOptional(req.Description, req.Phone, req.Website, req.OpeningHours, req.Address);
        var at = new GeoPoint(req.Lat, req.Lng);
        await CheckLocationAsync(at, ct);

        var now = clock.GetUtcNow();
        var today = await db.CommunityPlaces.CountAsync(p => p.OwnerId == userId && p.CreatedAt > now.AddDays(-1), ct);
        if (today >= CommunityRules.PlacesPerDay) throw new RateLimitedException($"At most {CommunityRules.PlacesPerDay} new places per day.");
        var hour = await db.CommunityPlaces.CountAsync(p => p.OwnerId == userId && p.CreatedAt > now.AddHours(-1), ct);
        if (hour >= CommunityRules.PublicPlacesPerHour) throw new RateLimitedException($"At most {CommunityRules.PublicPlacesPerHour} public places per hour.");

        if (await FindDuplicateAsync(name, at, null, ct) is { } dup) throw new DuplicatePlaceException(dup);

        var place = new CommunityPlace
        {
            Name = name, Category = req.Category ?? PlaceCategory.Other, Location = at.ToPoint(),
            Address = req.Address?.Trim() ?? "", Description = req.Description?.Trim(), Phone = req.Phone?.Trim(),
            Website = req.Website?.Trim(), OpeningHours = req.OpeningHours?.Trim(),
            Amenities = (req.Amenities ?? []).Select(a => a.Trim()).Where(a => a.Length is > 0 and <= 40).Distinct().Take(30).ToList(),
            Country = CountryResolver.Resolve(at.Lat, at.Lng), OwnerId = userId, Owner = user, CreatedAt = now, UpdatedAt = now,
        };
        user.Points += CommunityRules.Points.PlaceAdded;
        db.CommunityPlaces.Add(place);
        await db.SaveChangesAsync(ct);
        await bus.PublishAsync(RoutingKeys.PlaceChanged, new PlaceChangedEvent(place.Id), ct);
        return Dto(place);
    }

    public async Task<PlaceDto> PatchAsync(Guid userId, string id, PatchCommunityPlaceRequest req, CancellationToken ct)
    {
        var place = await OwnedAsync(userId, id, ct);
        if (req.Name is not null) place.Name = CheckName(req.Name);
        CheckOptional(req.Description, req.Phone, req.Website, req.OpeningHours, req.Address);
        if (req.Category is { } c) place.Category = c;
        if (req.Lat is not null || req.Lng is not null)
        {
            var at = new GeoPoint(req.Lat ?? place.Location.Y, req.Lng ?? place.Location.X);
            await CheckLocationAsync(at, ct);
            if (await FindDuplicateAsync(place.Name, at, place.Id, ct) is { } dup) throw new DuplicatePlaceException(dup);
            place.Location = at.ToPoint();
            place.Country = CountryResolver.Resolve(at.Lat, at.Lng);
        }
        if (req.Address is not null) place.Address = req.Address.Trim();
        if (req.Description is not null) place.Description = req.Description.Trim();
        if (req.Phone is not null) place.Phone = req.Phone.Trim();
        if (req.Website is not null) place.Website = req.Website.Trim();
        if (req.OpeningHours is not null) place.OpeningHours = req.OpeningHours.Trim();
        if (req.Amenities is not null) place.Amenities = req.Amenities.Select(a => a.Trim()).Where(a => a.Length is > 0 and <= 40).Distinct().Take(30).ToList();
        place.UpdatedAt = clock.GetUtcNow();
        await db.SaveChangesAsync(ct);
        await bus.PublishAsync(RoutingKeys.PlaceChanged, new PlaceChangedEvent(place.Id), ct);
        return Dto(place);
    }

    /// <summary>Owner delete: published places become "closed" (others may have reviewed them); others are removed.</summary>
    public async Task DeleteAsync(Guid userId, string id, CancellationToken ct)
    {
        var place = await OwnedAsync(userId, id, ct);
        if (place.Status == PlaceStatus.Published) place.Status = PlaceStatus.Closed;
        else db.CommunityPlaces.Remove(place);
        await db.SaveChangesAsync(ct);
        await bus.PublishAsync(RoutingKeys.PlaceChanged, new PlaceChangedEvent(id), ct);
    }

    public async Task<PlaceDto> VerifyAsync(Guid userId, string id, VerifyPlaceRequest req, CancellationToken ct)
    {
        await RequireUserAsync(userId, ct);
        var place = await db.CommunityPlaces.Include(p => p.Owner).FirstOrDefaultAsync(p => p.Id == id, ct)
                    ?? throw new NotFoundException("Only community places can be verified.");
        if (place.OwnerId == userId) throw new ForbiddenException("You can't verify your own place.");
        if (place.Status is PlaceStatus.Closed) throw new ConflictException("This place is closed.");

        var vote = await db.PlaceVerifications.FindAsync([id, userId], ct);
        if (vote is null) db.PlaceVerifications.Add(vote = new PlaceVerification { PlaceId = id, UserId = userId });
        vote.Exists = req.Exists;
        vote.CorrectLocation = req.CorrectLocation;
        vote.CreatedAt = clock.GetUtcNow();
        await db.SaveChangesAsync(ct);

        var votes = await db.PlaceVerifications.Where(v => v.PlaceId == id).ToListAsync(ct);
        var positives = votes.Count(v => v.Positive);
        var negatives = votes.Count - positives;
        var before = place.Status;
        place.VerifiedCount = positives;
        place.RejectedCount = negatives;
        place.Status = CommunityRules.AfterVerification(place.Status, positives, negatives);
        if (before != PlaceStatus.Published && place.Status == PlaceStatus.Published && place.Owner is not null)
            place.Owner.Points += CommunityRules.Points.PlacePublished;
        await db.SaveChangesAsync(ct);
        await bus.PublishAsync(RoutingKeys.PlaceChanged, new PlaceChangedEvent(id), ct);
        if (place.Status != before) await bus.PublishAsync(RoutingKeys.PlaceVerified, new PlaceVerifiedEvent(id, place.Status), ct);
        return Dto(place);
    }

    /// <summary>Any user, any place (OSM too). Owner suggestions on their own community place apply directly.</summary>
    public async Task<Guid> SuggestEditAsync(Guid userId, string id, SuggestEditRequest req, CancellationToken ct)
    {
        await RequireUserAsync(userId, ct);
        var isCommunity = CommunityPlace.IsCommunityId(id);
        var exists = isCommunity ? await db.CommunityPlaces.AnyAsync(p => p.Id == id, ct) : await db.Places.AnyAsync(p => p.Id == id, ct);
        if (!exists) throw new NotFoundException($"Place {id} not found.");
        if (req.Name is not null) CheckName(req.Name);
        CheckOptional(req.Description, req.Phone, req.Website, req.OpeningHours, req.Address);
        if (Filter.Check(req.Comment, rejectLinks: false) != TextVerdict.Ok) throw new ValidationException("comment", "Please keep it friendly.");
        if (req.Lat is { } la && req.Lng is { } lo && !CommunityRules.InServiceArea(la, lo)) throw new ValidationException("lat", "Location must be in Malaysia, Singapore or Brunei.");

        var suggestion = new PlaceSuggestion { PlaceId = id, UserId = userId, Changes = req, CreatedAt = clock.GetUtcNow() };
        db.PlaceSuggestions.Add(suggestion);
        if (isCommunity && await db.CommunityPlaces.AnyAsync(p => p.Id == id && p.OwnerId == userId, ct))
        {
            await db.SaveChangesAsync(ct);
            await PatchAsync(userId, id, new PatchCommunityPlaceRequest(req.Name, req.Category, req.Lat, req.Lng, req.Address, req.Description,
                req.Phone, req.Website, req.OpeningHours, null), ct);
            suggestion.Status = SuggestionStatus.Applied;
        }
        await db.SaveChangesAsync(ct);
        return suggestion.Id;
    }

    public async Task<IReadOnlyList<PlaceSummary>> ContributedAsync(Guid userId, CancellationToken ct)
    {
        var rows = await db.CommunityPlaces.AsNoTracking().Where(p => p.OwnerId == userId).OrderByDescending(p => p.CreatedAt).Take(200).ToListAsync(ct);
        var agg = await Core.Search.PlaceDocuments.AggregatesAsync(db, rows.Select(r => r.Id).ToList(), ct);
        // Owners see their own status, including hidden/closed.
        return rows.Select(p => PlaceService.ToSummary(p, null, null, agg.GetValueOrDefault(p.Id)) with { Status = p.Status }).ToList();
    }

    // ---- checks -------------------------------------------------------------------------------

    internal static string CheckName(string? name)
    {
        var n = name?.Trim() ?? "";
        if (n.Length is < 2 or > CommunityRules.MaxNameLength) throw new ValidationException("name", $"name must be 2–{CommunityRules.MaxNameLength} characters.");
        switch (Filter.Check(n))
        {
            case TextVerdict.Link: throw new ValidationException("name", "Names can't contain links.");
            case TextVerdict.Profanity: throw new ValidationException("name", "Please choose a different name.");
        }
        return n;
    }

    internal static void CheckOptional(string? description, string? phone, string? website, string? openingHours, string? address)
    {
        if (description is { Length: > CommunityRules.MaxDescriptionLength }) throw new ValidationException("description", $"description max {CommunityRules.MaxDescriptionLength} chars.");
        if (Filter.Check(description, rejectLinks: true) != TextVerdict.Ok) throw new ValidationException("description", "Please keep the description friendly and without links.");
        if (address is { Length: > 300 }) throw new ValidationException("address", "address max 300 chars.");
        if (Filter.Check(address, rejectLinks: true) != TextVerdict.Ok) throw new ValidationException("address", "Invalid address.");
        if (phone is not null && (phone.Length > 30 || phone.Any(ch => !(char.IsDigit(ch) || ch is ' ' or '+' or '-' or '(' or ')'))))
            throw new ValidationException("phone", "phone must contain digits, spaces, + - ( ) only.");
        if (website is not null && (website.Length > 200 || !Uri.TryCreate(website, UriKind.Absolute, out var u) || u.Scheme is not ("http" or "https")))
            throw new ValidationException("website", "website must be an http(s) URL.");
        if (openingHours is { Length: > 200 }) throw new ValidationException("openingHours", "openingHours max 200 chars.");
    }

    /// <summary>Inside MY/SG/BN (near a road from our extract) and not inside an inland water polygon.</summary>
    private async Task CheckLocationAsync(GeoPoint at, CancellationToken ct)
    {
        if (!CommunityRules.InServiceArea(at.Lat, at.Lng)) throw new ValidationException("lat", "Location must be in Malaysia, Singapore or Brunei.");
        var pt = at.ToPoint();
        if (!await db.RoadSegments.AnyAsync(s => s.Geom.IsWithinDistance(pt, 2000), ct))
            throw new ValidationException("lat", "This location is too far from any road (in the sea, or outside Malaysia, Singapore and Brunei).");
        if (await db.WaterAreas.AnyAsync(w => w.Geom.Intersects(pt), ct))
            throw new ValidationException("lat", "This location is in water. Move the pin onto land.");
    }

    private async Task<PlaceSummary?> FindDuplicateAsync(string name, GeoPoint at, string? exceptId, CancellationToken ct)
    {
        var pt = at.ToPoint();
        var osm = await db.Places.AsNoTracking().Where(p => p.Location.IsWithinDistance(pt, CommunityRules.DuplicateRadiusM)).Take(200).ToListAsync(ct);
        var community = await db.CommunityPlaces.AsNoTracking()
            .Where(p => p.Id != exceptId && p.Status != PlaceStatus.Hidden && p.Status != PlaceStatus.Closed && p.Location.IsWithinDistance(pt, CommunityRules.DuplicateRadiusM))
            .Take(200).ToListAsync(ct);
        var dup = CommunityRules.FindDuplicate(name, at,
            osm.Select(p => new CommunityRules.NearbyName(p.Id, p.Name, p.Location.Y, p.Location.X))
                .Concat(community.Select(p => new CommunityRules.NearbyName(p.Id, p.Name, p.Location.Y, p.Location.X))));
        if (dup is null) return null;
        return osm.FirstOrDefault(p => p.Id == dup.Id) is { } o
            ? PlaceService.ToSummary(o, at.Lat, at.Lng)
            : PlaceService.ToSummary(community.First(p => p.Id == dup.Id), at.Lat, at.Lng);
    }

    private async Task<CommunityPlace> OwnedAsync(Guid userId, string id, CancellationToken ct)
    {
        if (!CommunityPlace.IsCommunityId(id)) throw new ForbiddenException("Only community places can be edited; use suggest-edit for map places.");
        var place = await db.CommunityPlaces.Include(p => p.Owner).FirstOrDefaultAsync(p => p.Id == id, ct) ?? throw new NotFoundException($"Place {id} not found.");
        if (place.OwnerId != userId) throw new ForbiddenException("Only the person who added this place can change it; use suggest-edit.");
        return place;
    }

    private async Task<User> RequireUserAsync(Guid userId, CancellationToken ct) =>
        await db.Users.FirstOrDefaultAsync(u => u.Id == userId, ct) ?? throw new UnauthorizedAccessException("This account was deleted.");

    private PlaceDto Dto(CommunityPlace p) =>
        PlaceService.ToDto(p, null, null, null, [], TimeZoneInfo.ConvertTime(clock.GetUtcNow(), ValhallaMapper.MalaysiaTime).DateTime);
}
