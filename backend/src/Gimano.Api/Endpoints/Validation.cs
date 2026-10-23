using Gimano.Core.Contracts;
using Microsoft.AspNetCore.Http.HttpResults;

namespace Gimano.Api.Endpoints;

internal static class Validation
{
    public static ValidationProblem Problem(string field, string message) =>
        TypedResults.ValidationProblem(new Dictionary<string, string[]> { [field] = [message] });

    public static bool ValidLatLng(double lat, double lng) => lat is >= -90 and <= 90 && lng is >= -180 and <= 180 && !(lat == 0 && lng == 0);

    public static ValidationProblem? LatLng(double lat, double lng, string prefix = "")
        => ValidLatLng(lat, lng) ? null : Problem(prefix + "lat", "lat/lng must be valid WGS84 coordinates.");

    public static ValidationProblem? SavedPlace(SavePlaceRequest req)
    {
        if (string.IsNullOrWhiteSpace(req.Name) || req.Name.Length > 200) return Problem("name", "name is required (max 200 chars).");
        return LatLng(req.Lat, req.Lng);
    }
}
