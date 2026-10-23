using System.Buffers;
using System.Text.Json;

namespace Gimano.Core.Messaging;

/// <summary>
/// Transient, lossy fast path for GPS pings (docs/architecture.md "Messaging").
/// Separate from the durable <c>gimano.events</c> exchange: non-durable exchange and queue, transient messages,
/// no publisher confirms, auto-ack consumer; stale pings expire after 30 s and the queue drops its head under load.
/// </summary>
public static class LocationStream
{
    public const string Exchange = "gimano.locations";
    public const string RoutingKey = RoutingKeys.LocationPing;
    public const string TrafficQueue = "gimano.locations.traffic";
    public const int MessageTtlMs = 30_000;
    public const int MaxQueueLength = 200_000;

    /// <summary>Worker → API: anonymised driver cells for /live (same transient exchange).</summary>
    public const string DriversSnapshotKey = "drivers.snapshot";

    public static Dictionary<string, object?> QueueArguments() => new()
    {
        ["x-message-ttl"] = MessageTtlMs,
        ["x-max-length"] = MaxQueueLength,
        ["x-overflow"] = "drop-head",
    };
}

/// <summary>One POST /locations request (≤ 12 points). <c>SessionId</c> is a rotating pseudonym, never the user id.</summary>
public readonly record struct LocationBatchMessage(Guid SessionId, string Mood, LocationSample[] Points);

public readonly record struct LocationSample(double Lat, double Lng, float SpeedKmh, float Heading, long TsUnixMs);

/// <summary>
/// Compact wire format: one AMQP message carries many batches (coalesced by the publisher):
/// <c>[{"s":"session","m":"mood","p":[[lat,lng,speed,heading,tsMs],…]},…]</c>. Heading −1 = unknown.
/// </summary>
public static class LocationWire
{
    public static byte[] Encode(IReadOnlyList<LocationBatchMessage> batches)
    {
        var buffer = new ArrayBufferWriter<byte>(64 + batches.Count * 160);
        using (var w = new Utf8JsonWriter(buffer))
        {
            w.WriteStartArray();
            foreach (var b in batches)
            {
                w.WriteStartObject();
                w.WriteString("s", b.SessionId);
                w.WriteString("m", b.Mood);
                w.WriteStartArray("p");
                foreach (var p in b.Points)
                {
                    w.WriteStartArray();
                    w.WriteNumberValue(Math.Round(p.Lat, 6));
                    w.WriteNumberValue(Math.Round(p.Lng, 6));
                    w.WriteNumberValue(MathF.Round(p.SpeedKmh, 1));
                    w.WriteNumberValue(MathF.Round(p.Heading));
                    w.WriteNumberValue(p.TsUnixMs);
                    w.WriteEndArray();
                }
                w.WriteEndArray();
                w.WriteEndObject();
            }
            w.WriteEndArray();
        }
        return buffer.WrittenSpan.ToArray();
    }

    public static List<LocationBatchMessage> Decode(ReadOnlySpan<byte> json)
    {
        var result = new List<LocationBatchMessage>();
        var r = new Utf8JsonReader(json);
        Expect(ref r, JsonTokenType.StartArray);
        while (r.Read() && r.TokenType == JsonTokenType.StartObject)
        {
            Guid user = default;
            var mood = "happy";
            var pts = new List<LocationSample>(12);
            while (r.Read() && r.TokenType == JsonTokenType.PropertyName)
            {
                var name = r.GetString();
                r.Read();
                switch (name)
                {
                    case "s": user = r.GetGuid(); break;
                    case "m": mood = r.GetString() ?? "happy"; break;
                    case "p":
                        while (r.Read() && r.TokenType == JsonTokenType.StartArray)
                        {
                            r.Read(); var lat = r.GetDouble();
                            r.Read(); var lng = r.GetDouble();
                            r.Read(); var speed = r.GetSingle();
                            r.Read(); var heading = r.GetSingle();
                            r.Read(); var ts = r.GetInt64();
                            r.Read(); // EndArray
                            pts.Add(new LocationSample(lat, lng, speed, heading, ts));
                        }
                        break;
                    default: r.Skip(); break;
                }
            }
            result.Add(new LocationBatchMessage(user, mood, [.. pts]));
        }
        return result;
    }

    private static void Expect(ref Utf8JsonReader r, JsonTokenType t)
    {
        if (!r.Read() || r.TokenType != t) throw new JsonException($"Expected {t}.");
    }
}

/// <summary>Anonymised driver cells published by the worker every 5 s (no ids of any kind).</summary>
public sealed record DriversSnapshot(DateTimeOffset At, IReadOnlyList<Contracts.DriverDto> Drivers);
