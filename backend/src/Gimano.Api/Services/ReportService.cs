using Gimano.Core.Alerts;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Gimano.Core.Geo;
using Gimano.Core.Messaging;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Api.Services;

public sealed class NotFoundException(string message) : Exception(message);
public sealed class ConflictException(string message) : Exception(message);

public sealed class ReportService(GimanoDbContext db, IEventPublisher bus, GeoLookup geo, TimeProvider clock)
{
    public async Task<(ReportDto Report, bool Merged)> CreateAsync(Guid userId, CreateReportRequest req, CancellationToken ct)
    {
        var now = clock.GetUtcNow();
        var loc = new GeoPoint(req.Lat, req.Lng);
        var user = await db.Users.FirstOrDefaultAsync(u => u.Id == userId, ct) ?? throw new UnauthorizedAccessException("This account was deleted.");

        // Duplicate suppression: same type within 150 m along the same heading.
        var pt = loc.ToPoint();
        var nearby = await db.Reports.Include(r => r.Reporter)
            .Where(r => r.Status == ReportStatus.Active && r.ExpiresAt > now && r.Type == req.Type
                        && r.Location.IsWithinDistance(pt, AlertRules.DuplicateRadiusM))
            .OrderBy(r => r.Location.Distance(pt))
            .ToListAsync(ct);
        var dup = nearby.FirstOrDefault(r => AlertRules.IsDuplicate(req.Type, loc, req.Heading, r.Type, GeoPoint.From(r.Location), r.Heading));
        if (dup is not null)
        {
            dup.Confirmations++;
            dup.ExpiresAt = AlertRules.ExtendOnStillThere(dup.Type, dup.CreatedAt, dup.ExpiresAt, now);
            if (dup.ReporterId != userId && dup.Reporter is not null) dup.Reporter.Points += AlertRules.Points.Confirmed;
            user.Points += AlertRules.Points.Vote;
            await db.SaveChangesAsync(ct);
            var merged = dup.ToDto();
            await bus.PublishAsync(RoutingKeys.ReportUpdated, merged, ct);
            return (merged, true);
        }

        var report = new Report
        {
            Type = req.Type,
            Subtype = string.IsNullOrWhiteSpace(req.Subtype) ? null : req.Subtype,
            Location = pt,
            EndLocation = req.EndLat is { } elat && req.EndLng is { } elng ? new GeoPoint(elat, elng).ToPoint() : null,
            Heading = req.Heading is { } h ? ((h % 360) + 360) % 360 : null,
            Comment = string.IsNullOrWhiteSpace(req.Comment) ? null : req.Comment.Trim(),
            Street = await geo.StreetNameAsync(loc, ct),
            ReporterId = userId,
            Reporter = user,
            CreatedAt = now,
            ExpiresAt = AlertRules.InitialExpiry(req.Type, now),
        };
        user.Points += AlertRules.Points.NewReport;
        db.Reports.Add(report);
        await db.SaveChangesAsync(ct);

        var dto = report.ToDto();
        await bus.PublishAsync(RoutingKeys.ReportCreated, dto, ct);
        return (dto, false);
    }

    public async Task<ReportDto> VoteAsync(Guid reportId, Guid userId, bool stillThere, CancellationToken ct)
    {
        var now = clock.GetUtcNow();
        var report = await db.Reports.Include(r => r.Reporter).FirstOrDefaultAsync(r => r.Id == reportId, ct)
                     ?? throw new NotFoundException($"Report {reportId} not found.");
        if (report.Status != ReportStatus.Active || report.ExpiresAt <= now)
            throw new ConflictException("Report has already expired.");

        var voter = await db.Users.FirstOrDefaultAsync(u => u.Id == userId, ct) ?? throw new UnauthorizedAccessException("This account was deleted.");
        var existing = await db.ReportVotes.FindAsync([reportId, userId], ct);
        if (existing is not null)
        {
            // Changing your mind is allowed, but a repeated identical vote does nothing.
            if (existing.StillThere == stillThere) return report.ToDto();
            existing.StillThere = stillThere;
            existing.CreatedAt = now;
        }
        else
        {
            db.ReportVotes.Add(new ReportVote
            {
                ReportId = reportId, UserId = userId, StillThere = stillThere,
                Weight = AlertRules.VoteWeight(voter.Reputation), CreatedAt = now,
            });
        }
        voter.Points += AlertRules.Points.Vote;

        var expired = false;
        if (stillThere)
        {
            report.Confirmations++;
            report.ExpiresAt = AlertRules.ExtendOnStillThere(report.Type, report.CreatedAt, report.ExpiresAt, now);
            if (report.Reporter is not null && report.ReporterId != userId) report.Reporter.Points += AlertRules.Points.Confirmed;
        }
        else
        {
            await db.SaveChangesAsync(ct);
            var weights = await db.ReportVotes.Where(v => v.ReportId == reportId && !v.StillThere && v.UserId != report.ReporterId)
                .Select(v => v.Weight).ToListAsync(ct);
            if (AlertRules.ShouldExpireOnNotThere(weights))
            {
                report.Status = ReportStatus.Expired;
                report.ExpiresAt = now;
                expired = true;
            }
        }
        await db.SaveChangesAsync(ct);

        var dto = report.ToDto();
        await bus.PublishAsync(RoutingKeys.ReportVoted, new ReportVotedEvent(reportId, stillThere, userId), ct);
        if (expired) await bus.PublishAsync(RoutingKeys.ReportExpired, new ReportExpiredEvent(reportId), ct);
        else await bus.PublishAsync(RoutingKeys.ReportUpdated, dto, ct);
        return dto;
    }

    public async Task ThankAsync(Guid reportId, Guid userId, CancellationToken ct)
    {
        var report = await db.Reports.Include(r => r.Reporter).FirstOrDefaultAsync(r => r.Id == reportId, ct)
                     ?? throw new NotFoundException($"Report {reportId} not found.");
        report.Thanks++;
        if (report.Reporter is not null && report.ReporterId != userId) report.Reporter.Points += AlertRules.Points.Thanked;
        await db.SaveChangesAsync(ct);
        await bus.PublishAsync(RoutingKeys.ReportUpdated, report.ToDto(), ct);
    }

    public async Task<IReadOnlyList<ReportDto>> ActiveInBBoxAsync(BBox bbox, CancellationToken ct)
    {
        var now = clock.GetUtcNow();
        var poly = bbox.ToPolygon();
        var list = await db.Reports.AsNoTracking().Include(r => r.Reporter)
            .Where(r => r.Status == ReportStatus.Active && r.ExpiresAt > now && r.Location.Intersects(poly))
            .OrderByDescending(r => r.CreatedAt)
            .Take(1000)
            .ToListAsync(ct);
        return list.Select(r => r.ToDto()).ToList();
    }
}
