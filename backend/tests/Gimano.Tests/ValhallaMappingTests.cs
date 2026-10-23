using System.Text.Json;
using System.Text.Json.Nodes;
using Gimano.Core.Contracts;
using Gimano.Core.Geo;
using Gimano.Core.Routing;
using Gimano.Core.Traffic;

namespace Gimano.Tests;

public class ValhallaMappingTests
{
    private static readonly GeoPoint[] Leg1 = [new(3.1300, 101.6200), new(3.1290, 101.6210), new(3.1280, 101.6230), new(3.1250, 101.6240)];
    private static readonly GeoPoint[] Leg2 = [new(3.1250, 101.6240), new(3.1230, 101.6200), new(3.1200, 101.6100)];

    internal static JsonNode SampleResponse(bool withAlternate = true, bool twoLegs = false)
    {
        var leg1 = $$"""
        {
          "shape": "{{Polyline.Encode(Leg1)}}",
          "summary": {"time": 120.5, "length": 0.75},
          "maneuvers": [
            {"type": 2, "instruction": "Drive southeast on Jalan SS 2/24.", "street_names": ["Jalan SS 2/24"],
             "time": 30.2, "length": 0.15, "begin_shape_index": 0, "end_shape_index": 1,
             "verbal_pre_transition_instruction": "Drive southeast on Jalan SS 2/24 for 150 meters."},
            {"type": 20, "instruction": "Take exit 1107 on the right toward SS2.", "street_names": ["E11", "Lebuhraya Damansara–Puchong"],
             "time": 60.0, "length": 0.5, "begin_shape_index": 1, "end_shape_index": 2, "toll": true,
             "sign": {"exit_number_elements": [{"text": "1107"}], "exit_toward_elements": [{"text": "SS2"}]},
             "verbal_post_transition_instruction": "Continue for 500 meters."},
            {"type": 26, "instruction": "Enter the roundabout and take the 2nd exit.", "begin_street_names": ["Jalan Harapan"],
             "time": 30.3, "length": 0.1, "begin_shape_index": 2, "end_shape_index": 3},
            {"type": 4, "instruction": "You have arrived at your destination.", "time": 0, "length": 0,
             "begin_shape_index": 3, "end_shape_index": 3}
          ]
        }
        """;
        var leg2 = $$"""
        {
          "shape": "{{Polyline.Encode(Leg2)}}",
          "summary": {"time": 80, "length": 1.6},
          "maneuvers": [
            {"type": 1, "instruction": "Drive west.", "street_names": ["FT2"], "time": 80, "length": 1.6, "begin_shape_index": 0, "end_shape_index": 2},
            {"type": 4, "instruction": "Arrive.", "time": 0, "length": 0, "begin_shape_index": 2, "end_shape_index": 2}
          ]
        }
        """;
        var legs = twoLegs ? $"[{leg1},{leg2}]" : $"[{leg1}]";
        var time = twoLegs ? 200.5 : 120.5;
        var length = twoLegs ? 2.35 : 0.75;
        var trip = $$$"""
            {"units":"kilometers","status":0,"legs":{{{legs}}},
             "summary":{"has_toll":false,"time":{{{time}}},"length":{{{length}}}}}
            """;
        var alt = """
            {"units":"kilometers","legs":[{"shape":"_c`|E_kenR~cB~cB","maneuvers":[
              {"type":1,"instruction":"Go","street_names":["Jalan Lain"],"time":90,"length":0.9,"begin_shape_index":0,"end_shape_index":1},
              {"type":4,"instruction":"Arrive","time":0,"length":0,"begin_shape_index":1,"end_shape_index":1}]}],
             "summary":{"has_toll":false,"time":100,"length":0.9}}
            """;
        var json = withAlternate ? $$"""{"trip":{{trip}},"alternates":[{"trip":{{alt}}}]}""" : $$"""{"trip":{{trip}}}""";
        return JsonNode.Parse(json)!;
    }

    [Fact]
    public void Maps_primary_trip_and_alternates()
    {
        var routes = ValhallaMapper.MapResponse(SampleResponse());
        Assert.Equal(2, routes.Count);
        Assert.Equal(120.5, routes[0].DurationS);
        Assert.Equal(750, routes[0].DistanceM);
        Assert.Equal(100, routes[1].DurationS);
    }

    [Fact]
    public void Maps_maneuver_fields_and_units()
    {
        var r = ValhallaMapper.MapResponse(SampleResponse(withAlternate: false))[0];
        var m = r.Legs[0].Maneuvers;
        Assert.Equal(4, m.Count);

        Assert.Equal(ManeuverType.Depart, m[0].Type);
        Assert.Equal("Jalan SS 2/24", m[0].StreetName);
        Assert.Equal(150, m[0].DistanceM);
        Assert.Equal(30.2, m[0].DurationS);
        Assert.StartsWith("Drive southeast", m[0].VerbalPre);

        Assert.Equal(ManeuverType.ExitRight, m[1].Type);
        Assert.Equal("1107", m[1].ExitNumber);
        Assert.Equal("E11", m[1].RoadRef);
        Assert.Equal("Lebuhraya Damansara–Puchong", m[1].StreetName);
        Assert.Equal(1, m[1].BeginShapeIndex);
        Assert.Equal(2, m[1].EndShapeIndex);
        Assert.Equal("Continue for 500 meters.", m[1].VerbalPost);

        Assert.Equal(ManeuverType.RoundaboutEnter, m[2].Type);
        Assert.Equal("Jalan Harapan", m[2].StreetName);
        Assert.Equal(ManeuverType.Arrive, m[3].Type);
    }

    [Fact]
    public void Toll_flag_from_maneuver_overrides_summary()
    {
        var r = ValhallaMapper.MapResponse(SampleResponse(withAlternate: false))[0];
        Assert.True(r.HasTolls);
    }

    [Fact]
    public void Polyline_is_reencoded_at_precision_6()
    {
        var r = ValhallaMapper.MapResponse(SampleResponse(withAlternate: false))[0];
        var pts = Polyline.Decode(r.Polyline);
        Assert.Equal(Leg1.Length, pts.Count);
        Assert.Equal(Leg1[^1].Lat, pts[^1].Lat, 6);
    }

    [Fact]
    public void Multi_leg_shapes_are_joined_and_shape_indexes_offset()
    {
        var r = ValhallaMapper.MapResponse(SampleResponse(withAlternate: false, twoLegs: true))[0];
        // 4 + 3 points, shared joint counted once.
        Assert.Equal(6, r.Points.Count);
        Assert.Equal(2, r.Legs.Count);
        var leg2First = r.Legs[1].Maneuvers[0];
        Assert.Equal(3, leg2First.BeginShapeIndex);
        Assert.Equal(5, leg2First.EndShapeIndex);
        Assert.Equal("FT2", leg2First.RoadRef);
        Assert.All(r.Legs.SelectMany(l => l.Maneuvers), m => Assert.InRange(m.EndShapeIndex, 0, r.Points.Count - 1));
    }

    [Theory]
    [InlineData(1, ManeuverType.Depart)]
    [InlineData(3, ManeuverType.Depart)]
    [InlineData(5, ManeuverType.Arrive)]
    [InlineData(8, ManeuverType.Straight)]
    [InlineData(9, ManeuverType.SlightRight)]
    [InlineData(10, ManeuverType.Right)]
    [InlineData(11, ManeuverType.SharpRight)]
    [InlineData(12, ManeuverType.UturnRight)]
    [InlineData(13, ManeuverType.UturnLeft)]
    [InlineData(14, ManeuverType.SharpLeft)]
    [InlineData(15, ManeuverType.Left)]
    [InlineData(16, ManeuverType.SlightLeft)]
    [InlineData(18, ManeuverType.RampRight)]
    [InlineData(19, ManeuverType.RampLeft)]
    [InlineData(20, ManeuverType.ExitRight)]
    [InlineData(21, ManeuverType.ExitLeft)]
    [InlineData(23, ManeuverType.KeepRight)]
    [InlineData(24, ManeuverType.KeepLeft)]
    [InlineData(25, ManeuverType.Merge)]
    [InlineData(26, ManeuverType.RoundaboutEnter)]
    [InlineData(27, ManeuverType.RoundaboutExit)]
    [InlineData(28, ManeuverType.Ferry)]
    [InlineData(38, ManeuverType.Merge)]
    [InlineData(999, ManeuverType.Straight)]
    public void Maps_valhalla_maneuver_types(int valhalla, ManeuverType expected) =>
        Assert.Equal(expected, ValhallaMapper.MapType(valhalla));

    [Theory]
    [InlineData("E11", true)]
    [InlineData("FT2", true)]
    [InlineData("FT 2", true)]
    [InlineData("B15", true)]
    [InlineData("AH2", true)]
    [InlineData("Jalan Ampang", false)]
    [InlineData("PIE", false)]
    [InlineData("Lebuhraya Utara-Selatan", false)]
    public void Recognises_road_refs(string s, bool expected) => Assert.Equal(expected, ValhallaMapper.IsRoadRef(s));

    [Fact]
    public void Summary_uses_the_longest_roads_in_travel_order()
    {
        var r = ValhallaMapper.MapResponse(SampleResponse(withAlternate: false))[0];
        Assert.Equal("Via Jalan SS 2/24, E11 Lebuhraya Damansara–Puchong", r.Summary);
    }

    [Fact]
    public void Builds_request_with_costing_avoid_and_alternates()
    {
        var req = new RouteRequest(new LatLng(3.13, 101.62), new LatLng(3.12, 101.61), [], Vehicle.Motorcycle,
            new AvoidOptions(Tolls: true, Ferries: true), new DateTimeOffset(2026, 10, 23, 0, 30, 0, TimeSpan.Zero), 2);
        var body = ValhallaMapper.BuildRouteRequest(req, originHeading: 370);

        Assert.Equal("motorcycle", body["costing"]!.GetValue<string>());
        var opts = body["costing_options"]!["motorcycle"]!;
        Assert.Equal(0.0, opts["use_tolls"]!.GetValue<double>());
        Assert.Equal(0.0, opts["use_ferry"]!.GetValue<double>());
        Assert.Equal(2, body["alternates"]!.GetValue<int>());
        Assert.Equal(10, body["locations"]![0]!["heading"]!.GetValue<int>());
        // 00:30 UTC = 08:30 MYT
        Assert.Equal("2026-10-23T08:30", body["date_time"]!["value"]!.GetValue<string>());
        Assert.Equal(1, body["date_time"]!["type"]!.GetValue<int>());
    }

    [Fact]
    public void No_alternates_requested_when_there_are_waypoints()
    {
        var req = new RouteRequest(new LatLng(3.13, 101.62), new LatLng(3.12, 101.61), [new LatLng(3.125, 101.615)], null, null, null, 2);
        var body = ValhallaMapper.BuildRouteRequest(req);
        Assert.Null(body["alternates"]);
        Assert.Equal(3, body["locations"]!.AsArray().Count);
        Assert.Equal("through", body["locations"]![1]!["type"]!.GetValue<string>());
        Assert.Equal("auto", body["costing"]!.GetValue<string>());
    }

    [Fact]
    public void Parses_valhalla_error_body()
    {
        var e = ValhallaMapper.ParseError("""{"error_code":442,"error":"No path could be found for input","status_code":400}""");
        Assert.Equal(442, e!.Value.Code);
        Assert.Null(ValhallaMapper.ParseError("<html>bad gateway</html>"));
    }

    [Fact]
    public void Decorator_ranks_by_traffic_duration_and_labels_best()
    {
        var candidates = ValhallaMapper.MapResponse(SampleResponse());
        var routes = RouteDecorator.Decorate(candidates, [], [], () => Guid.NewGuid().ToString("N"));
        Assert.Equal(RouteLabel.Best, routes[0].Label);
        Assert.Equal(100, routes[0].DurationS); // the alternate is faster
        Assert.Equal(RouteLabel.Alternative, routes[1].Label);
        Assert.Equal(4, routes[0].Bbox.Length);
    }

    [Fact]
    public void Decorator_applies_jams_to_traffic_duration_and_segments()
    {
        var candidates = ValhallaMapper.MapResponse(SampleResponse(withAlternate: false));
        var jam = new JamLine(3, 10, [Leg1[1], Leg1[2]]);
        var r = RouteDecorator.Decorate(candidates, [], [jam], () => "x")[0];
        var seg = Assert.Single(r.TrafficSegments);
        Assert.Equal(1, seg.FromIndex);
        Assert.Equal(2, seg.ToIndex);
        Assert.Equal(3, seg.Level);
        Assert.True(r.TrafficDurationS > r.DurationS);
    }

    [Fact]
    public void Alerts_on_route_are_filtered_by_corridor_and_heading_and_sorted()
    {
        var route = new[] { new GeoPoint(3.0000, 101.0000), new GeoPoint(3.0000, 101.0100), new GeoPoint(3.0000, 101.0200) }; // heading east (90°)
        ReportDto R(double lat, double lng, double? heading, ReportType type = ReportType.Police) =>
            new(Guid.NewGuid(), type, null, lat, lng, heading, null, new ReportedBy("x", "happy"), DateTimeOffset.UtcNow, DateTimeOffset.UtcNow.AddHours(1), 0, 0);

        var far = R(3.0010, 101.0050, null);           // ~111 m off route
        var later = R(3.0001, 101.0150, 85);           // on route, same direction, ~1.66 km along
        var sooner = R(3.0000, 101.0020, null);        // on route, ~222 m along
        var opposite = R(3.0000, 101.0100, 270);       // other carriageway direction
        var closure = R(3.0000, 101.0120, 270, ReportType.Closure); // closures apply both ways

        var alerts = RouteDecorator.AlertsOnRoute(route, [far, later, sooner, opposite, closure]);
        Assert.Equal([sooner.Id, closure.Id, later.Id], alerts.Select(a => a.Id));
        Assert.InRange(alerts[0].DistanceAlongRouteM, 200, 245);
    }

    [Fact]
    public void Route_json_uses_contract_casing()
    {
        var candidates = ValhallaMapper.MapResponse(SampleResponse(withAlternate: false));
        var r = RouteDecorator.Decorate(candidates, [], [], () => "abc")[0];
        var json = JsonSerializer.Serialize(r, GimanoJson.Options);
        Assert.Contains("\"label\":\"best\"", json);
        Assert.Contains("\"type\":\"exit_right\"", json);
        Assert.Contains("\"type\":\"roundabout_enter\"", json);
        Assert.Contains("\"trafficDurationS\":", json);
        Assert.Contains("\"beginShapeIndex\":", json);
    }
}
