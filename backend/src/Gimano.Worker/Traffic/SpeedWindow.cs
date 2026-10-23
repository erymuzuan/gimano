using System.Collections.Concurrent;
using Gimano.Core.Traffic;

namespace Gimano.Worker.Traffic;

/// <summary>In-memory 5-minute rolling window of snapped speed samples per road segment.</summary>
public sealed class SpeedWindow(TimeProvider clock)
{
    private readonly ConcurrentDictionary<long, List<SpeedSample>> _samples = new();

    public void Add(long segmentId, SpeedSample sample)
    {
        var list = _samples.GetOrAdd(segmentId, _ => []);
        lock (list)
        {
            // One sample per user per segment per 10 s keeps a single fast pinger from dominating.
            var dup = list.FindLastIndex(s => s.UserId == sample.UserId);
            if (dup >= 0 && (sample.Ts - list[dup].Ts).Duration() < TimeSpan.FromSeconds(10)) list[dup] = sample;
            else list.Add(sample);
        }
    }

    /// <summary>Drops expired samples and returns current per-segment stats.</summary>
    public IReadOnlyList<SegmentStats> Snapshot()
    {
        var now = clock.GetUtcNow();
        var cutoff = now - JamDetector.Window;
        var result = new List<SegmentStats>();
        foreach (var (id, list) in _samples)
        {
            SegmentStats? stats;
            lock (list)
            {
                list.RemoveAll(s => s.Ts < cutoff);
                stats = list.Count == 0 ? null : JamDetector.Aggregate(id, list, now);
            }
            if (stats is null) _samples.TryRemove(id, out _);
            else result.Add(stats);
        }
        return result;
    }
}
