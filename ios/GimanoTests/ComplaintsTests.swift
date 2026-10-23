import Foundation
import Testing
@testable import Gimano

@Suite("Complaints (docs/complaints.md v1)")
struct ComplaintsTests {
    let p = LatLng(lat: 3.1178, lng: 101.6213)

    @Test("Issues and route issues decode; unknown kinds/states don't break")
    func decode() throws {
        let json = #"{"id":"i1","kind":"road_closed_temp","subtype":"flood","state":"confirmed","lat":3.1,"lng":101.6,"placeId":null,"roadName":"Jalan SS 2/24","summary":null,"confirmations":2,"required":2,"createdAt":"2026-10-23T03:00:00Z","expiresAt":"2026-10-23T09:00:00Z"}"#
        let i = try JSON.makeDecoder().decode(Gimano.Issue.self, from: Data(json.utf8))
        #expect(i.kind == .roadClosedTemp && i.state == .confirmed && i.roadName == "Jalan SS 2/24" && i.state.isOpen)
        let weird = try JSON.makeDecoder().decode(Gimano.Issue.self, from: Data(#"{"id":"x","kind":"bridge_out","state":"pondering","lat":1,"lng":2}"#.utf8))
        #expect(weird.kind == .unknown && weird.state == .unknown && !weird.state.isOpen)
        let r = try JSON.makeDecoder().decode(RouteIssue.self, from: Data(#"{"id":"i1","kind":"road_closed_temp","state":"confirmed","avoided":true,"roadName":"Jalan Kuching","subtype":"flood","confirmations":3}"#.utf8))
        #expect(IssueText.avoidLine(r).contains("Jalan Kuching") && IssueText.avoidLine(r).contains("3"))
    }

    @Test("Route request sends ignoreIssues only for \"Drive through anyway\"")
    func ignore() throws {
        var req = RouteRequest(origin: RouteEndpoint(lat: 3.1, lng: 101.6), destination: RouteEndpoint(lat: 3.2, lng: 101.7))
        var obj = try JSONSerialization.jsonObject(with: JSON.makeEncoder().encode(req)) as! [String: Any]
        #expect(obj["ignoreIssues"] == nil)
        req.ignoreIssues = ["i1"]
        obj = try JSONSerialization.jsonObject(with: JSON.makeEncoder().encode(req)) as! [String: Any]
        #expect(obj["ignoreIssues"] as? [String] == ["i1"])
    }

    @Test("\"Why didn't you follow the route?\": never while driving, ≤ 1/day, never after opting out")
    func askPolicy() {
        let now = Date()
        #expect(DeviationAskPolicy.shouldAsk(lastAsked: nil, optedOut: false, speedKmh: 0, navigating: false, now: now))
        #expect(!DeviationAskPolicy.shouldAsk(lastAsked: nil, optedOut: false, speedKmh: 30, navigating: false, now: now))
        #expect(!DeviationAskPolicy.shouldAsk(lastAsked: nil, optedOut: false, speedKmh: 0, navigating: true, now: now))
        #expect(!DeviationAskPolicy.shouldAsk(lastAsked: now - 3600, optedOut: false, speedKmh: 0, navigating: false, now: now))
        #expect(DeviationAskPolicy.shouldAsk(lastAsked: now - 25 * 3600, optedOut: false, speedKmh: 0, navigating: false, now: now))
        #expect(!DeviationAskPolicy.shouldAsk(lastAsked: nil, optedOut: true, speedKmh: 0, navigating: false, now: now))
        #expect(DeviationReason.jam.issueKind == nil && DeviationReason.myChoice.issueKind == nil, "jams and own choices send nothing")
        #expect(DeviationReason.closed.issueKind == .roadClosedTemp && DeviationReason.noTurn.issueKind == .turnNotAllowed)
    }

    @Test("\"Still closed?\" within 300 m of an open road issue, once per issue")
    func stillClosed() {
        func issue(_ id: String, _ dLat: Double, _ kind: IssueKind = .roadClosedTemp, _ state: IssueState = .suspected) -> Gimano.Issue {
            Gimano.Issue(id: id, kind: kind, state: state, lat: p.lat + dLat, lng: p.lng)
        }
        let list = [issue("far", 0.01), issue("near", 0.002), issue("closed-place", 0.0005, .placeClosedPerm), issue("old", 0.001, .roadClosedTemp, .refuted)]
        #expect(StillClosedPolicy.nearest(list, to: p, asked: [])?.id == "near")
        #expect(StillClosedPolicy.nearest(list, to: p, asked: ["near"]) == nil)
    }

    @Test("Issue endpoints are signed like other contributions")
    func signed() {
        #expect(SignedEndpoints.requiresSignature(method: "POST", path: "issues"))
        #expect(SignedEndpoints.requiresSignature(method: "POST", path: "issues/i1/votes"))
        #expect(!SignedEndpoints.requiresSignature(method: "GET", path: "issues/nearby"))
    }

    @Test("Demo: reports dedupe within 50 m; 2 confirmations confirm; \"open now\" refutes")
    func demo() async throws {
        let repo = DemoRepository(latency: .zero)
        let a = try await repo.createIssue(NewIssue(kind: .roadClosedTemp, lat: p.lat, lng: p.lng, source: .report))
        #expect(a.state == .suspected)
        let b = try await repo.createIssue(NewIssue(kind: .roadClosedTemp, lat: p.lat + 0.0002, lng: p.lng, source: .report))
        #expect(b.id == a.id && b.state == .confirmed)
        let box = BBox(minLng: p.lng, minLat: p.lat, maxLng: p.lng, maxLat: p.lat).expanded(byM: 300)
        #expect(try await repo.issuesNearby(bbox: box, kinds: [.roadClosedTemp]).count == 1)
        let v = try await repo.voteIssue(id: a.id, IssueVote(answer: .cleared))
        #expect(v.state == .refuted)
        #expect(try await repo.issuesNearby(bbox: box, kinds: nil).isEmpty)
    }
}
