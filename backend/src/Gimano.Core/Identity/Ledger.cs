using System.Security.Cryptography;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Core.Identity;

/// <summary>
/// Reputation ledger operations shared by the API (on each resolution) and the worker (nightly, expiry):
/// tier recompute, place resolution with vote unlinking, suspension.
/// </summary>
public static class Ledger
{
    public static async Task<ReputationCounters> CountersAsync(GimanoDbContext db, User u, DateTimeOffset now, CancellationToken ct)
    {
        var since90 = now.AddDays(-90);
        var since7 = now.AddDays(-7);
        var upheld = await db.PlaceDisputes.CountAsync(d => d.ContributorId == u.Id && d.Upheld == true && d.CreatedAt > since90, ct);
        var rejected7 = await db.CommunityPlaces.CountAsync(p => p.OwnerId == u.Id && p.Status == PlaceStatus.Hidden && p.ResolvedAt > since7, ct);
        return new ReputationCounters(u.PlacesAccepted, u.PlacesRejected, u.VerifierVotes, u.VerifierAgreed, u.JoinedAt, upheld, rejected7);
    }

    /// <summary>Recomputes tier (and suspension) for one user; caller saves.</summary>
    public static async Task RecomputeAsync(GimanoDbContext db, User u, DateTimeOffset now, CancellationToken ct)
    {
        var c = await CountersAsync(db, u, now, ct);
        u.Tier = Reputation.ComputeTier(c, now);
        if (Reputation.ShouldSuspend(c.RejectedPlaces7d) && (u.SuspendedUntil is null || u.SuspendedUntil < now))
            u.SuspendedUntil = now + Reputation.Suspension;
    }

    public static async Task<byte[]> SaltAsync(GimanoDbContext db, string placeId, CancellationToken ct)
    {
        var s = await db.PlaceSalts.FindAsync([placeId], ct);
        if (s is null) db.PlaceSalts.Add(s = new PlaceSalt { PlaceId = placeId, Salt = RandomNumberGenerator.GetBytes(32) });
        return s.Salt;
    }

    /// <summary>
    /// Applies an outcome: place status, contributor + verifier counters, unlinks every pending vote
    /// (VoterId → hmac(salt, voterId), time → day), marks the salt for deletion in 30 days, recomputes tiers.
    /// Caller saves and publishes events.
    /// </summary>
    public static async Task ResolveAsync(GimanoDbContext db, CommunityPlace place, PlaceStatus status, DateTimeOffset now, CancellationToken ct)
    {
        place.Status = status;
        place.ResolvedAt = now;
        place.AuditOpen = false;
        var outcome = status switch
        {
            PlaceStatus.Published => Reputation.Outcome.Published,
            PlaceStatus.Hidden => Reputation.Outcome.Hidden,
            _ => Reputation.Outcome.Pending, // expired: nobody agreed or disagreed
        };

        var touched = new Dictionary<Guid, User>();
        async Task<User?> UserAsync(Guid id)
        {
            if (touched.TryGetValue(id, out var u)) return u;
            u = await db.Users.FirstOrDefaultAsync(x => x.Id == id, ct);
            if (u is not null) touched[id] = u;
            return u;
        }

        if (place.OwnerId is { } ownerId && await UserAsync(ownerId) is { } owner)
        {
            if (status == PlaceStatus.Published) owner.PlacesAccepted++;
            if (status == PlaceStatus.Hidden) owner.PlacesRejected++;
        }

        var salt = await SaltAsync(db, place.Id, ct);
        var votes = await db.PlaceVotes.Where(v => v.PlaceId == place.Id && v.VoterId != null).ToListAsync(ct);
        foreach (var v in votes)
        {
            var voterId = v.VoterId!.Value;
            if (outcome != Reputation.Outcome.Pending && Reputation.Agreed(v.Answer, outcome) is { } agreed && await UserAsync(voterId) is { } voter)
            {
                voter.VerifierVotes++;
                if (agreed) voter.VerifierAgreed++;
            }
            v.VoterHash = Reputation.VoterHash(salt, voterId);
            v.VoterId = null;
            v.Day = DateOnly.FromDateTime((v.CreatedAt ?? now).UtcDateTime);
            v.CreatedAt = null;
        }
        if (await db.PlaceSalts.FindAsync([place.Id], ct) is { } s) s.ResolvedAt = now;
        await db.SaveChangesAsync(ct); // so the recompute below sees this resolution

        foreach (var u in touched.Values) await RecomputeAsync(db, u, now, ct);
    }
}
