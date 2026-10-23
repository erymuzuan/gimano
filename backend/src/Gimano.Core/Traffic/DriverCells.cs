using Gimano.Core.Contracts;

namespace Gimano.Core.Traffic;

/// <summary>Anonymised driver dots: only cells (0.01° ≈ 1.1 km) with ≥ 3 drivers; positions rounded to 3 dp, heading to 15°.</summary>
public static class DriverCells
{
    public const int MinPerCell = 3;

    public static List<DriverDto> Anonymise(IEnumerable<(double Lat, double Lng, double Heading, string Mood)> drivers, int minPerCell = MinPerCell) =>
        drivers
            .GroupBy(d => (Math.Floor(d.Lat * 100), Math.Floor(d.Lng * 100)))
            .Where(g => g.Count() >= minPerCell)
            .SelectMany(g => g)
            .Select(d => new DriverDto(Math.Round(d.Lat, 3), Math.Round(d.Lng, 3), Math.Round(d.Heading / 15) * 15 % 360, d.Mood))
            .ToList();
}
