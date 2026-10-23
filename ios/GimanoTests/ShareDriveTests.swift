import Foundation
import Sodium
import Testing
@testable import Gimano

@Suite("Share my drive (E2E encrypted)")
struct ShareDriveTests {
    private func payload(_ status: SharePayload.Status = .driving) -> SharePayload {
        SharePayload(lat: 3.152613, lng: 101.703718, heading: 210, speedKmh: 48, etaIso: "2026-10-23T02:13:00.000Z",
                     remainingM: 6400, destinationName: "Mid Valley Megamall", status: status, ts: "2026-10-23T02:01:00.000Z")
    }

    @Test("Link carries the shareId and a 256-bit base64url key in the fragment only")
    func link() throws {
        let key = ShareCrypto.newKey()
        #expect(key.count == 32)
        let url = ShareCrypto.link(shareId: "AbC_-123", key: key)
        #expect(url.absoluteString.hasPrefix("https://www.bukitbunga.com/gimano/s/#AbC_-123."))
        #expect(url.query == nil)
        let frag = try #require(url.fragment)
        #expect(!frag.contains("=") && !frag.contains("+") && !frag.contains("/"))
        let parsed = try #require(ShareCrypto.parse(url))
        #expect(parsed.shareId == "AbC_-123" && parsed.key == key)
    }

    @Test("XChaCha20-Poly1305 IETF round trip with AD = shareId; random nonce each time")
    func roundTrip() throws {
        let key = ShareCrypto.newKey()
        let a = try ShareCrypto.seal(payload(), key: key, shareId: "s1")
        let b = try ShareCrypto.seal(payload(), key: key, shareId: "s1")
        #expect(a.nonce != b.nonce)
        #expect(Data(base64Encoded: a.nonce)?.count == 24)
        #expect(ShareCrypto.open(a, key: key, shareId: "s1") == payload())
        // Ciphertext is small enough for the 1 KB limit.
        #expect((Data(base64Encoded: a.ciphertext)?.count ?? 9999) <= 1024)
    }

    @Test("Wrong key or a different shareId (AD) cannot decrypt; the server only sees ciphertext")
    func binding() throws {
        let key = ShareCrypto.newKey()
        let blob = try ShareCrypto.seal(payload(), key: key, shareId: "s1")
        #expect(ShareCrypto.open(blob, key: ShareCrypto.newKey(), shareId: "s1") == nil)
        #expect(ShareCrypto.open(blob, key: key, shareId: "s2") == nil)
        let raw = String(decoding: Data(base64Encoded: blob.ciphertext)!, as: UTF8.self)
        #expect(!raw.contains("Mid Valley") && !raw.contains("3.15"))
    }

    @Test("Payload JSON has exactly the contract fields")
    func payloadShape() throws {
        let obj = try JSONSerialization.jsonObject(with: JSONEncoder().encode(payload(.arrived))) as! [String: Any]
        #expect(Set(obj.keys) == ["lat", "lng", "heading", "speedKmh", "etaIso", "remainingM", "destinationName", "status", "ts"])
        #expect(obj["status"] as? String == "arrived")
    }

    @Test("Lifetime: remaining trip + 30 min, clamped to 15…720")
    func expiry() {
        #expect(ShareCrypto.expiresInMinutes(remainingS: 0) == 30)
        #expect(ShareCrypto.expiresInMinutes(remainingS: 25 * 60) == 55)
        #expect(ShareCrypto.expiresInMinutes(remainingS: 24 * 3600) == 720)
    }

    @Test("Session sends at most every 15 s and ends with a final status, then deletes the share")
    @MainActor
    func session() async throws {
        let repo = DemoRepository(latency: .zero)
        let s = try await DriveShareSession.start(destinationName: "Mid Valley Megamall", remainingS: 600, repo: repo)
        let key = try #require(ShareCrypto.parse(s.link)).key
        let fix = LocationFix(coordinate: LatLng(lat: 3.15, lng: 101.70), speedKmh: 40, course: 90, accuracyM: 5, timestamp: .now)
        let t0 = Date()
        #expect(s.shouldSend(now: t0))
        s.update(fix: fix, remainingS: 600, remainingM: 5000, now: t0)
        try await Task.sleep(for: .milliseconds(200))
        #expect(!s.shouldSend(now: t0.addingTimeInterval(10)))
        #expect(s.shouldSend(now: t0.addingTimeInterval(15)))
        let blob = try #require(await repo.sharedBlob(s.created.shareId))
        #expect(ShareCrypto.open(blob, key: key, shareId: s.created.shareId)?.status == .driving)
        await s.end(fix: fix, status: .arrived)
        #expect(s.ended && !s.shouldSend(now: t0.addingTimeInterval(60)))
        #expect(await repo.sharedBlob(s.created.shareId) == nil)
    }

    @Test("BM + EN message contains the link")
    func message() {
        let url = ShareCrypto.link(shareId: "x", key: ShareCrypto.newKey())
        let m = ShareCrypto.message(destination: "Kerja", eta: "8:30 AM", link: url)
        #expect(m.contains("Saya sedang memandu ke Kerja") && m.contains("I'm driving to Kerja"))
        #expect(m.components(separatedBy: url.absoluteString).count == 3)
    }
}
