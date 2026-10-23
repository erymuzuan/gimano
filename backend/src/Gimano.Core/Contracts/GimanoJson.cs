using System.Text.Json;
using System.Text.Json.Serialization;

namespace Gimano.Core.Contracts;

public static class GimanoJson
{
    /// <summary>Applies the API wire conventions: camelCase properties, snake_case enum strings.</summary>
    public static JsonSerializerOptions Configure(JsonSerializerOptions o)
    {
        o.PropertyNamingPolicy = JsonNamingPolicy.CamelCase;
        o.DictionaryKeyPolicy = null;
        o.Converters.Add(new JsonStringEnumConverter(JsonNamingPolicy.SnakeCaseLower, allowIntegerValues: false));
        o.NumberHandling = JsonNumberHandling.AllowReadingFromString;
        return o;
    }

    public static readonly JsonSerializerOptions Options = Configure(new JsonSerializerOptions(JsonSerializerDefaults.Web));

    public static string ToWire<T>(T value) where T : struct, Enum => JsonNamingPolicy.SnakeCaseLower.ConvertName(value.ToString());

    public static bool TryParseWire<T>(string? s, out T value) where T : struct, Enum
    {
        value = default;
        if (string.IsNullOrWhiteSpace(s)) return false;
        var pascal = string.Concat(s.Split('_', StringSplitOptions.RemoveEmptyEntries)
            .Select(p => char.ToUpperInvariant(p[0]) + p[1..]));
        return Enum.TryParse(pascal, ignoreCase: true, out value) && Enum.IsDefined(value);
    }
}
