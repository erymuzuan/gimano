import Foundation
import Testing
@testable import Gimano

@Suite("Crash reports and feedback privacy")
struct DiagnosticsTests {
    @Test("Scrubber removes coordinates, emails, phones, tokens, URLs with queries and data paths")
    func scrubber() {
        let input = """
        Crash near 3.152613, 101.703718 {"lat": 3.1180, "lng":101.6190} latitude=2.95
        user ali.baba@example.com called +60 12-345 6789 and 012-345 6789 or 03-2141 1234
        Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJl
        {"writeToken":"k2PIOYhTRK6AJL7veH90PXKgyOO5yHJ27si4b3dI_kc","sig":"CPm5nQ7W"} token=abc123
        GET https://gimano-api.bukitbunga.com/api/v1/search?q=rumah+mak&lat=3.1 failed
        open /var/mobile/Containers/Data/Application/0B1C2D3E-AAAA/Library/gimano.sqlite failed
        file:///private/var/mobile/Containers/Shared/AppGroup/x/y.json
        0 Gimano 0x0000000104a3c000 NavigationSession.applyFix(_:) + 128
        """
        let out = Scrubber.scrub(input)
        for leaked in ["3.152613", "101.703718", "3.1180", "101.6190", "2.95", "ali.baba@example.com", "12-345 6789", "012-345 6789",
                       "03-2141 1234", "eyJhbGciOiJIUzI1NiJ9", "k2PIOYhTRK6AJL7veH90PXKgyOO5yHJ27si4b3dI_kc", "CPm5nQ7W", "abc123",
                       "rumah+mak", "/var/mobile/Containers/Data", "0B1C2D3E", "AppGroup"] {
            #expect(!out.contains(leaked), "leaked: \(leaked)\n\(out)")
        }
        // Useful frames survive.
        #expect(out.contains("NavigationSession.applyFix(_:) + 128"))
        #expect(out.contains("0x0000000104a3c000"))
        #expect(out.contains("\"lat\": [redacted]") || out.contains("\"lat\":[redacted]"))
    }

    @Test("Crash report is scrubbed and size-limited before it leaves the phone")
    func crashReport() throws {
        let big = String(repeating: "frame at 3.1526, 101.7037\n", count: 3000)
        let r = CrashReport(occurredAt: Date(timeIntervalSince1970: 1_792_713_600), kind: "crash", exception: "EXC_BAD_ACCESS",
                            message: String(repeating: "x", count: 900) + " ali@example.com", stack: big).scrubbed()
        #expect((r.message?.utf8.count ?? 0) <= 500)
        #expect((r.stack?.utf8.count ?? 0) <= 32 * 1024)
        #expect(r.stack?.contains("101.7037") == false)
        let obj = try JSONSerialization.jsonObject(with: JSON.makeEncoder().encode(r)) as! [String: Any]
        #expect(obj["id"] == nil, "the local id is not part of the contract")
        #expect(obj["kind"] as? String == "crash" && obj["occurredAt"] != nil)
    }

    @Test("Contact details: optional, validated, consent required, never prefilled")
    func contact() {
        #expect(ContactRules.build(email: "", whatsapp: "", consent: false).0 == nil)
        #expect(ContactRules.build(email: "", whatsapp: "", consent: false).1 == nil)
        #expect(ContactRules.build(email: "ali@", whatsapp: "", consent: true).1 != nil)
        #expect(ContactRules.build(email: "ali@example.com", whatsapp: "", consent: false).1 != nil, "consent is required")
        let ok = ContactRules.build(email: " ali@example.com ", whatsapp: "012-345 6789", consent: true)
        #expect(ok.0 == ContactDetails(email: "ali@example.com", whatsapp: "+60123456789", consent: true))
        #expect(ContactRules.normalizeWhatsApp("+65 9123 4567") == "+6591234567")
        #expect(ContactRules.normalizeWhatsApp("123") == nil)
    }

    @Test("Coarse location is rounded to 2 dp on the phone")
    func coarse() {
        let c = CoarseLocation(LatLng(lat: 3.158765, lng: 101.711234))
        #expect(c.lat == 3.16 && c.lng == 101.71)
    }

    @Test("Device model includes the marketing name when known")
    func deviceModel() {
        #expect(DeviceIdentity.describe("iPhone17,3") == "iPhone17,3 (iPhone 16)")
        #expect(DeviceIdentity.describe("iPhone99,9") == "iPhone99,9")
    }

    @Test("Pending crash records are stored locally until the user decides")
    @MainActor
    func pendingStore() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("crash-\(UUID().uuidString)")
        let reporter = CrashReporter(directory: dir)
        reporter.save(CrashReport(occurredAt: .now, kind: "hang", exception: "hang"))
        reporter.save(CrashReport(occurredAt: .now.addingTimeInterval(-60), kind: "crash", exception: "EXC"))
        #expect(reporter.pending.count == 2 && reporter.pending.first?.kind == "hang")
        reporter.discardAll()
        #expect(reporter.pending.isEmpty)
    }

    @Test("Demo repository records crash reports; contact can be withdrawn")
    func demo() async throws {
        let repo = DemoRepository(latency: .zero)
        let created = try await repo.sendFeedback(FeedbackRequest(category: .bug, message: "Peta tak keluar", rating: nil, screen: .home,
                                                                   contact: ContactDetails(email: "a@b.co", consent: true)))
        #expect(try await repo.myFeedback().first?.hasContact == true)
        try await repo.deleteFeedbackContact(id: created.id)
        #expect(try await repo.myFeedback().first?.hasContact == false)
        _ = try await repo.sendCrashReport(CrashReport(occurredAt: .now, kind: "crash"))
        #expect(await repo.crashReports.count == 1)
    }

    @Test("An approved report that can't be sent is queued (scrubbed) and not asked about again")
    @MainActor
    func approvedQueue() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("crash-\(UUID().uuidString)")
        let reporter = CrashReporter(directory: dir)
        reporter.save(CrashReport(occurredAt: .now, kind: "crash", message: "at 3.152613, 101.703718"))
        // A server that isn't there (connection refused).
        let failing = LiveRepository(baseURL: URL(string: "http://127.0.0.1:9/api/v1/")!)
        let r = try #require(reporter.pending.first)
        let device = DeviceInfo(appVersion: "0.1.0", appBuild: 1, osVersion: "iOS 26.1", deviceModel: "iPhone17,3",
                                screen: .init(widthPx: 1, heightPx: 1, density: 3, widthDp: 1, heightDp: 1), locale: "en", theme: "light", dataMode: "live")
        #expect(await reporter.send(r, device: device, contact: nil, repo: failing) == false)
        #expect(reporter.pending.isEmpty, "not asked again")
        #expect(reporter.approved.count == 1 && reporter.approved[0].message?.contains("3.152613") == false)
        let demo = DemoRepository(latency: .zero)
        await reporter.retryApproved(repo: demo)
        #expect(reporter.approved.isEmpty)
        #expect(await demo.crashReports.count == 1)
    }
}

@Suite("Location quality")
struct LocationQualityTests {
    @Test("Weak GPS only after > 50 m for 10 s; recovers immediately")
    func weak() {
        var q = GpsQuality()
        let t0 = Date(timeIntervalSince1970: 1_792_713_600)
        q.update(accuracyM: 80, at: t0)
        #expect(!q.isWeak)
        q.update(accuracyM: 120, at: t0 + 9)
        #expect(!q.isWeak)
        q.update(accuracyM: 90, at: t0 + 10)
        #expect(q.isWeak && q.lastAccuracyM == 90)
        q.update(accuracyM: 12, at: t0 + 11)
        #expect(!q.isWeak)
        // One approximate fix, then nothing for a while: weak by the clock.
        var a = GpsQuality()
        a.update(accuracyM: 1500, at: t0)
        #expect(!a.isWeak(at: t0 + 5) && a.isWeak(at: t0 + 10))
    }

    @Test("Fixes worse than 100 m don't drive guidance")
    func guidance() {
        func fix(_ acc: Double) -> LocationFix {
            LocationFix(coordinate: LatLng(lat: 3.15, lng: 101.7), speedKmh: 40, course: 90, accuracyM: acc, timestamp: .now)
        }
        #expect(GpsQuality.usableForGuidance(fix(30)))
        #expect(GpsQuality.usableForGuidance(fix(100)))
        #expect(!GpsQuality.usableForGuidance(fix(1500)))
    }

    @Test("Device block carries precise/approximate and last accuracy, never coordinates")
    @MainActor
    func deviceBlock() throws {
        let d = DeviceInfo.current(theme: "light", dataMode: "live", precise: false, lastAccuracyM: 1234.4)
        let obj = try JSONSerialization.jsonObject(with: JSON.makeEncoder().encode(d)) as! [String: Any]
        #expect(obj["locationPrecision"] as? String == "approximate" && obj["lastAccuracyM"] as? Int == 1234)
        #expect(obj["lat"] == nil && obj["lng"] == nil)
    }
}
