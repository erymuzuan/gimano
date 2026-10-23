namespace Gimano.Core.Contracts;

// All enums are serialised as snake_case strings (see GimanoJson).

public enum Platform { Android, Ios }

public enum Vehicle { Car, Motorcycle }

public enum SavedPlaceKind { Home, Work, Favorite }

public enum ReportType { Traffic, Police, Accident, Hazard, Closure, BlockedLane, MapIssue, Weather, Animal }

public enum RouteLabel { Best, Alternative }

public enum PlaceCategory
{
    Fuel, EvCharging, RestArea, TollBooth, Parking, Restaurant, Cafe, FastFood, Mall, Supermarket,
    Convenience, Mosque, Surau, Temple, Church, Hospital, Clinic, Pharmacy, Police, School, University,
    Hotel, Atm, Bank, CarRepair, CarWash, Tourism, TransitStation, Airport, Government, Other
}

public enum ManeuverType
{
    Depart, Arrive, Straight, SlightLeft, Left, SharpLeft, SlightRight, Right, SharpRight, UturnLeft, UturnRight,
    RoundaboutEnter, RoundaboutExit, Merge, RampLeft, RampRight, ExitLeft, ExitRight, KeepLeft, KeepRight, Ferry
}
