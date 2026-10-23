namespace Gimano.Core.Contracts;

// All enums are serialised as snake_case strings (see GimanoJson).

public enum Platform { Android, Ios }

public enum Vehicle { Car, Motorcycle, Bicycle, Walk }

public enum SavedPlaceKind { Home, Work, Favorite }

public enum ReportType { Traffic, Police, Accident, Hazard, Closure, BlockedLane, MapIssue, Weather, Animal }

public enum RouteLabel { Best, Alternative }

public enum PlaceCategory
{
    Fuel, EvCharging, RestArea, TollBooth, Parking, Restaurant, Cafe, FastFood, Mall, Supermarket,
    Convenience, Mosque, Surau, Temple, Church, Hospital, Clinic, Pharmacy, Police, School, University,
    Hotel, Atm, Bank, CarRepair, CarWash, Tourism, TransitStation, Airport, Government, Other,
    /// <summary>A city, town, suburb, village, neighbourhood or administrative area (searchable by name).</summary>
    Area,
    /// <summary>Public toilets (amenity=toilets).</summary>
    Toilets,
    /// <summary>Search-only result kinds: a named street, a house address, a postcode area, a coordinate / Plus Code point.</summary>
    Street, Address, Postcode, Point
}

public enum ManeuverType
{
    Depart, Arrive, Straight, SlightLeft, Left, SharpLeft, SlightRight, Right, SharpRight, UturnLeft, UturnRight,
    RoundaboutEnter, RoundaboutExit, Merge, RampLeft, RampRight, ExitLeft, ExitRight, KeepLeft, KeepRight, Ferry
}

public enum FavoriteIcon { Home, Work, Kampung, Mertua, Star, Heart, School, Surau, Food, Gym, Shop, Hospital }

public enum TollSystem { Open, ClosedEntry, ClosedExit }

public enum FeedbackCategory { Bug, Idea, MapData, Route, Search, Other }

public enum FeedbackScreen { Home, Search, Place, RoutePreview, Navigation, Report, Settings, Other }

public enum FeedbackStatus { Received, Triaged, Planned, Done, WontFix }

public enum PlaceSource { Osm, Community }

/// <summary>Community place lifecycle. Only <c>pending</c>/<c>published</c> appear in PlaceSummary.status.</summary>
public enum PlaceStatus { Pending, Published, Hidden, Closed, Expired }

public enum ReviewStatus { Published, Hidden }

public enum ReviewSort { Recent, Helpful }

public enum FlagReason { Spam, Offensive, OffTopic, ConflictOfInterest, Other }

public enum PhotoStatus { Processing, Published, Rejected }

public enum InboxKind { PlacePublished, PlaceRejected, ReportThanked, ReviewHelpful, EditAccepted, FeedbackStatus, Announcement, PlaceExpired }

/// <summary>Contributor trust tier (docs/identity-and-reputation.md).</summary>
public enum Tier { Baharu, Perak, Emas }
