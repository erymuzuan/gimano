import Foundation
import Testing
@testable import Gimano

@Suite("Vetting: proximity, prompt gating, votes")
struct VettingTests {
    @Test("Geohash-6 cells match docs §8")
    func geohash() {
        #expect(Geohash.encode(lat: 3.1180, lng: 101.6190) == "w2838r")
        #expect(Geohash.encode(lat: 3.1579, lng: 101.7116) == "w283fw")
        #expect(Geohash.encode(lat: 1.3521, lng: 103.8198) == "w21zdq")
        #expect(Geohash.encode(lat: 4.9031, lng: 114.9398) == "w8cezs")
    }

    @Test("Proximity is computed on the phone: 1 km in towns, 5 km rural; only the bucket and my cell are sent")
    func proximity() throws {
        let me = LatLng(lat: 3.1180, lng: 101.6190)
        let near = LatLng(lat: 3.1200, lng: 101.6250)          // ~700 m
        let far = LatLng(lat: 3.1400, lng: 101.6190)           // ~2.4 km
        let claim = try #require(Proximity.claim(me: me, place: near))
        #expect(claim == ProximityClaim(cell: "w2838r", withinM: 1000))
        #expect(Proximity.claim(me: me, place: far) == nil)
        #expect(Proximity.claim(me: me, place: far, rural: true)?.withinM == 5000)
    }

    @Test("Prompt only after 60 s parked (< 5 km/h), not navigating, in the foreground, at most every 30 min")
    func gate() {
        var g = VettingPromptGate()
        let t0 = Date(timeIntervalSince1970: 1_792_713_600)
        func step(_ g: inout VettingPromptGate, _ kmh: Double, _ dt: TimeInterval, nav: Bool = false, fg: Bool = true) -> Bool {
            g.shouldPrompt(speedKmh: kmh, at: t0 + dt, navigating: nav, foreground: fg)
        }
        var r = step(&g, 2, 0); #expect(!r)
        r = step(&g, 2, 30); #expect(!r)
        r = step(&g, 2, 61); #expect(r)
        r = step(&g, 30, 62); #expect(!r, "moving resets the timer")
        r = step(&g, 0, 63); #expect(!r)
        r = step(&g, 0, 130); #expect(r)
        g.didPrompt(at: t0 + 130)
        r = step(&g, 0, 130 + 610); #expect(!r)
        r = step(&g, 0, 130 + 670); #expect(!r, "≤ 1 per 30 min")
        _ = step(&g, 0, 130 + 1800)
        r = step(&g, 0, 130 + 1861); #expect(r)
        var n = VettingPromptGate()
        _ = step(&n, 0, 0, nav: true)
        r = step(&n, 0, 120, nav: true); #expect(!r, "never while navigating")
        r = step(&n, 0, 240, fg: false); #expect(!r, "never in the background")
    }

    @Test("Verify request has the §4 shape (no coordinates)")
    func requestShape() throws {
        let r = VerifyPlaceRequest(answer: .yes, nameCorrect: true, locationCorrect: true,
                                   proximity: ProximityClaim(cell: "w283f8", withinM: 420), attestation: AttestationClaim(token: "k.a"))
        let obj = try JSONSerialization.jsonObject(with: JSON.makeEncoder().encode(r)) as! [String: Any]
        #expect(Set(obj.keys) == ["answer", "nameCorrect", "locationCorrect", "proximity", "attestation"])
        #expect((obj["proximity"] as? [String: Any])?["cell"] as? String == "w283f8")
        #expect((obj["attestation"] as? [String: Any])?["platform"] as? String == "ios")
        let raw = String(decoding: try JSON.makeEncoder().encode(r), as: UTF8.self)
        #expect(!raw.contains("\"lat\"") && !raw.contains("\"lng\""))
    }

    @Test("The signature and the attestation share one ts (task-local signing clock)")
    func signingClock() async throws {
        let client = APIClient(baseURL: URL(string: "https://x/api/v1/")!, token: "t")
        await client.setSigner(IdentityKeys.generate())
        let req = await SigningClock.$fixedTs.withValue(1_792_713_600) {
            await client.preparedRequest(method: "POST", path: "places/p1/verify", body: Data("{}".utf8))
        }
        #expect(req.value(forHTTPHeaderField: "X-Gimano-Ts") == "1792713600")
    }

    @Test("Demo: nearby pending places exclude my own; one vote per place; too far is refused")
    func demoFlow() async throws {
        let repo = DemoRepository(latency: .zero)
        let me = LatLng(lat: 3.152613, lng: 101.703718)
        let id = await repo.seedPendingPlace(name: "Gerai Test", at: LatLng(lat: me.lat + 0.0018, lng: me.lng + 0.0012))
        let mine = try await repo.createPlace(NewPlace(name: "Kedai Saya", category: .convenience, lat: me.lat, lng: me.lng))
        let list = try await repo.pendingNearby(cell: Geohash.encode(me))
        #expect(list.contains { $0.id == id } && !list.contains { $0.id == mine.id })
        let claim = try #require(Proximity.claim(me: me, place: LatLng(lat: me.lat + 0.0018, lng: me.lng + 0.0012)))
        let r = try await repo.verifyPlace(id: id, VerifyPlaceRequest(answer: .yes, nameCorrect: true, locationCorrect: true, proximity: claim))
        #expect(r.confirmationsWeighted == 2 && r.required == 3 && r.status == .pending)
        await #expect(throws: APIError.self) {
            try await repo.verifyPlace(id: id, VerifyPlaceRequest(answer: .yes, proximity: claim))
        }
        await #expect(throws: APIError.self) {
            try await repo.verifyPlace(id: mine.id, VerifyPlaceRequest(answer: .yes, proximity: claim))
        }
        #expect(try await repo.pendingNearby(cell: Geohash.encode(me)).allSatisfy { $0.id != id }, "voted places aren't offered again")
    }
}
