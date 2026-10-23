using System.Diagnostics;
using Gimano.Core.Community;
using Gimano.Core.Contracts;
using Gimano.Core.Data;
using Gimano.Core.Domain;
using Gimano.Core.Messaging;
using Microsoft.EntityFrameworkCore;

namespace Gimano.Worker.Photos;

/// <summary>Consumes <c>photo.uploaded</c> (durable): orientation → strip metadata → WebP 1600/400 → content-hash keys → published.</summary>
public sealed class PhotoProcessor(
    RabbitMqConnection connection, IServiceScopeFactory scopes, IMediaStore media, IEventPublisher bus, ILogger<PhotoProcessor> logger)
    : RabbitConsumer<PhotoUploadedEvent>(connection, logger)
{
    protected override string QueueName => "gimano.worker.photos";
    protected override IReadOnlyList<string> RoutingKeyBindings => [RoutingKeys.PhotoUploaded];
    protected override ushort Prefetch => 4;

    protected override async Task HandleAsync(PhotoUploadedEvent message, string routingKey, CancellationToken ct)
    {
        await using var scope = scopes.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<GimanoDbContext>();
        var photo = await db.Photos.Include(p => p.User).FirstOrDefaultAsync(p => p.Id == message.PhotoId, ct);
        if (photo is null || photo.Status != PhotoStatus.Processing || photo.UploadKey is null) return;

        var original = await media.GetAsync(photo.UploadKey, ct);
        if (original is null)
        {
            Reject(photo, "Upload missing.");
        }
        else
        {
            var sw = Stopwatch.StartNew();
            try
            {
                var p = await Task.Run(() => ImageProcessor.Process(original), ct);
                await media.PutAsync(MediaKeys.Webp(p.Key), p.Webp, "image/webp", ct);
                await media.PutAsync(MediaKeys.Webp(p.ThumbKey), p.Thumb, "image/webp", ct);
                photo.Key = p.Key;
                photo.ThumbKey = p.ThumbKey;
                photo.Width = p.Width;
                photo.Height = p.Height;
                photo.Status = PhotoStatus.Published;
                if (photo.User is not null) photo.User.Points += CommunityRules.Points.Photo;
                logger.LogInformation("Photo {Id}: {In:n0} B → {Out:n0} B WebP {W}×{H} + {Thumb:n0} B thumb in {Ms:n0} ms",
                    photo.Id, original.Length, p.Webp.Length, p.Width, p.Height, p.Thumb.Length, sw.Elapsed.TotalMilliseconds);
            }
            catch (ImageRejectedException ex)
            {
                Reject(photo, ex.Message);
            }
        }
        var uploadKey = photo.UploadKey;
        photo.UploadKey = null;
        await db.SaveChangesAsync(ct);
        await media.DeleteAsync(uploadKey, ct); // the original (with its EXIF/GPS) is never kept
        await bus.PublishAsync(RoutingKeys.PlaceChanged, new PlaceChangedEvent(photo.PlaceId), ct);
    }

    private void Reject(Photo photo, string reason)
    {
        photo.Status = PhotoStatus.Rejected;
        photo.RejectReason = reason;
        logger.LogInformation("Photo {Id} rejected: {Reason}", photo.Id, reason);
    }
}
