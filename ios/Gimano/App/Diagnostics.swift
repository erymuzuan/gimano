import Foundation
import MetricKit
import UIKit

// MARK: - Device info (feedback & crash reports; shown to the user before sending)

enum DeviceIdentity {
    /// Hardware identifier, e.g. "iPhone17,3".
    static var hardwareModel: String {
        #if targetEnvironment(simulator)
        if let sim = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] { return sim }
        #endif
        var sys = utsname()
        uname(&sys)
        return withUnsafeBytes(of: &sys.machine) { raw in String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self) }
    }

    /// Marketing names for recent iPhones (unknown identifiers are sent as-is).
    static let marketingNames: [String: String] = [
        "iPhone14,7": "iPhone 14", "iPhone14,8": "iPhone 14 Plus", "iPhone15,2": "iPhone 14 Pro", "iPhone15,3": "iPhone 14 Pro Max",
        "iPhone15,4": "iPhone 15", "iPhone15,5": "iPhone 15 Plus", "iPhone16,1": "iPhone 15 Pro", "iPhone16,2": "iPhone 15 Pro Max",
        "iPhone17,1": "iPhone 16 Pro", "iPhone17,2": "iPhone 16 Pro Max", "iPhone17,3": "iPhone 16", "iPhone17,4": "iPhone 16 Plus",
        "iPhone17,5": "iPhone 16e", "iPhone18,1": "iPhone 17 Pro", "iPhone18,2": "iPhone 17 Pro Max", "iPhone18,3": "iPhone 17",
        "iPhone18,4": "iPhone Air", "iPhone14,6": "iPhone SE (3rd generation)", "iPhone14,5": "iPhone 13", "iPhone14,2": "iPhone 13 Pro",
        "iPhone14,3": "iPhone 13 Pro Max", "iPhone14,4": "iPhone 13 mini",
    ]

    /// "iPhone17,3 (iPhone 16)".
    static func describe(_ id: String) -> String { marketingNames[id].map { "\(id) (\($0))" } ?? id }
}

extension DeviceInfo {
    @MainActor
    static func current(theme: String, dataMode: String, precise: Bool? = nil, lastAccuracyM: Double? = nil, screen: UIScreen? = nil) -> DeviceInfo {
        let info = Bundle.main.infoDictionary
        let scr = screen ?? UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.screen }.first
        let bounds = scr?.bounds.size ?? CGSize(width: 393, height: 852)
        let scale = scr?.scale ?? 3
        let v = ProcessInfo.processInfo.operatingSystemVersion
        let os = "iOS \(v.majorVersion).\(v.minorVersion)" + (v.patchVersion > 0 ? ".\(v.patchVersion)" : "")
        return DeviceInfo(appVersion: info?["CFBundleShortVersionString"] as? String ?? "?",
                          appBuild: Int(info?["CFBundleVersion"] as? String ?? "") ?? 0,
                          osVersion: os,
                          deviceModel: DeviceIdentity.describe(DeviceIdentity.hardwareModel),
                          screen: .init(widthPx: Int(bounds.width * scale), heightPx: Int(bounds.height * scale), density: Double(scale),
                                        widthDp: Int(bounds.width), heightDp: Int(bounds.height)),
                          locale: Bundle.main.preferredLocalizations.first ?? "en",
                          theme: theme, dataMode: dataMode,
                          locationPrecision: precise.map { $0 ? "precise" : "approximate" },
                          lastAccuracyM: lastAccuracyM.map { Int($0.rounded()) })
    }
}

// MARK: - Contact validation

enum ContactRules {
    static func validEmail(_ s: String) -> Bool {
        s.range(of: #"^[^\s@]+@[^\s@]+\.[A-Za-z]{2,}$"#, options: .regularExpression) != nil
    }

    /// E.164: "+" and 8–15 digits. Malaysian numbers typed as 012-345 6789 become +60123456789.
    static func normalizeWhatsApp(_ s: String) -> String? {
        var digits = s.filter(\.isNumber)
        if s.trimmingCharacters(in: .whitespaces).hasPrefix("+") {
            // keep as-is
        } else if digits.hasPrefix("0") {
            digits = "60" + digits.dropFirst()
        }
        guard (8...15).contains(digits.count) else { return nil }
        return "+" + digits
    }

    /// nil when nothing was entered; throws-like nil + message when invalid.
    static func build(email: String, whatsapp: String, consent: Bool) -> (ContactDetails?, String?) {
        let e = email.trimmingCharacters(in: .whitespaces), w = whatsapp.trimmingCharacters(in: .whitespaces)
        if e.isEmpty && w.isEmpty { return (nil, nil) }
        if !e.isEmpty && !validEmail(e) { return (nil, String(localized: "Check the email address.")) }
        let phone = w.isEmpty ? nil : normalizeWhatsApp(w)
        if !w.isEmpty && phone == nil { return (nil, String(localized: "Check the WhatsApp number.")) }
        guard consent else { return (nil, String(localized: "Tick the box to agree we may contact you about this report.")) }
        return (ContactDetails(email: e.isEmpty ? nil : e, whatsapp: phone, consent: true), nil)
    }
}

// MARK: - Scrubbing (on the phone, before anything leaves it)

enum Scrubber {
    static let redacted = "[redacted]"

    private static let rules: [(String, NSRegularExpression.Options)] = [
        // JWTs and bearer/share tokens
        (#"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*"#, []),
        (#"(?i)\b(Bearer|Share)\s+[A-Za-z0-9._~+/=-]+"#, []),
        (#"(?i)(["']?(token|writeToken|access_token|refresh_token|sig|seed|seed_b64|password|secret|key|apiKey)["']?\s*[:=]\s*)("[^"]*"|'[^']*'|[^\s,&}]+)"#, []),
        // URLs with a query string (the whole URL)
        (#"(?i)\b[a-z][a-z0-9+.-]*://[^\s"'<>]*\?[^\s"'<>]*"#, []),
        // file paths under the app's / user's data
        (#"(/private)?/var/mobile/Containers/(Data|Shared)/[^\s"']*"#, []),
        (#"/Users/[^\s"']+"#, []),
        (#"file://[^\s"']+"#, []),
        // emails
        (#"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"#, []),
        // coordinates: "lat": 3.118 / latitude=3.1 and bare pairs 3.1180, 101.6190
        (#"(?i)(["']?(lat|lng|lon|latitude|longitude)["']?\s*[:=]\s*)-?\d{1,3}(\.\d+)?"#, []),
        (#"(?<![\w.])-?\d{1,2}\.\d{2,}\s*[,;/ ]\s*-?\d{1,3}\.\d{2,}(?![\w.])"#, []),
        // phone numbers: +E.164 and Malaysian 01x-xxx xxxx / 03-xxxx xxxx
        (#"(?<![\w+])\+\d[\d\s-]{7,16}\d(?!\w)"#, []),
        (#"(?<![\w.])0\d{1,2}[-\s]?\d{3,4}[-\s]?\d{4}(?![\w.])"#, []),
    ]
    private static let compiled: [NSRegularExpression] = rules.map { try! NSRegularExpression(pattern: $0.0, options: $0.1) }

    static func scrub(_ text: String) -> String {
        var out = text
        for (i, re) in compiled.enumerated() {
            let range = NSRange(out.startIndex..., in: out)
            // Key/value rules keep the key ($1) and redact the value.
            let template = (i == 2 || i == 8) ? "$1\(redacted)" : redacted
            out = re.stringByReplacingMatches(in: out, range: range, withTemplate: template)
        }
        return out
    }

    static func scrub(_ text: String?, limit: Int) -> String? {
        guard let text else { return nil }
        let s = scrub(text)
        guard s.utf8.count > limit else { return s }
        return String(decoding: Array(s.utf8.prefix(limit)), as: UTF8.self)
    }
}

// MARK: - Crash reports (docs/api.md "Crash reports"; no third-party SDK)

struct CrashReport: Codable, Sendable, Hashable, Identifiable {
    var id: String = UUID().uuidString
    var occurredAt: Date
    var kind: String            // crash | hang
    var exception: String?
    var message: String?
    var stack: String?
    var threads: String?
    var screen: String?
    var uptimeS: Int?
    var device: DeviceInfo?
    var contact: ContactDetails?

    enum CodingKeys: String, CodingKey { case occurredAt, kind, exception, message, stack, threads, screen, uptimeS, device, contact }

    /// Everything that leaves the phone goes through the scrubber, with the contract's size limits.
    func scrubbed() -> CrashReport {
        var r = self
        r.exception = Scrubber.scrub(exception, limit: 300)
        r.message = Scrubber.scrub(message, limit: 500)
        r.stack = Scrubber.scrub(stack, limit: 32 * 1024)
        r.threads = Scrubber.scrub(threads, limit: 32 * 1024)
        return r
    }
}

struct CrashCreated: Codable, Sendable { var id: String; var signature: String? }

enum CrashPreference: String, CaseIterable, Sendable {
    case ask, always, never
    var title: LocalizedStringResource {
        switch self {
        case .ask: "Ask every time"
        case .always: "Always send"
        case .never: "Don't send"
        }
    }
}

/// Collects crash/hang diagnostics locally (MetricKit on the next launch, plus ObjC exceptions), asks before sending.
@MainActor
final class CrashReporter: NSObject, MXMetricManagerSubscriber {
    static let shared = CrashReporter()

    let directory: URL
    var onPending: (() -> Void)?

    init(directory: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("gimano/crashes", isDirectory: true)) {
        self.directory = directory
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        super.init()
    }

    var preference: CrashPreference {
        get { CrashPreference(rawValue: UserDefaults.standard.string(forKey: "crash.preference") ?? "") ?? .ask }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: "crash.preference") }
    }

    func start() {
        MXMetricManager.shared.add(self)
        Self.installExceptionHandler(directory: directory)
        // Payloads delivered while the app wasn't running are available immediately.
        ingest(MXMetricManager.shared.pastDiagnosticPayloads)
    }

    nonisolated func didReceive(_ payloads: [MXDiagnosticPayload]) {
        let records = Self.records(from: payloads)
        Task { @MainActor in self.store(records) }
    }

    func ingest(_ payloads: [MXDiagnosticPayload]) { store(Self.records(from: payloads)) }

    nonisolated static func records(from payloads: [MXDiagnosticPayload]) -> [CrashReport] {
        payloads.flatMap { p in
            (p.crashDiagnostics ?? []).map { record(crash: $0, at: p.timeStampEnd) }
                + (p.hangDiagnostics ?? []).map { record(hang: $0, at: p.timeStampEnd) }
        }
    }

    private func store(_ records: [CrashReport]) {
        for r in records { save(r) }
        if !records.isEmpty { onPending?() }
    }

    nonisolated static func record(crash c: MXCrashDiagnostic, at date: Date) -> CrashReport {
        let exc = [c.exceptionType.map { "EXC \($0)" }, c.signal.map { "SIG \($0)" }, c.exceptionCode.map { "code \($0)" }]
            .compactMap { $0 }.joined(separator: " · ")
        let stack = String(decoding: c.callStackTree.jsonRepresentation(), as: UTF8.self)
        return CrashReport(occurredAt: date, kind: "crash", exception: exc.isEmpty ? "crash" : exc,
                           message: c.terminationReason ?? c.virtualMemoryRegionInfo, stack: stack)
    }

    nonisolated static func record(hang h: MXHangDiagnostic, at date: Date) -> CrashReport {
        let stack = String(decoding: h.callStackTree.jsonRepresentation(), as: UTF8.self)
        return CrashReport(occurredAt: date, kind: "hang", exception: "hang",
                           message: "Main thread unresponsive for \(h.hangDuration.formatted())", stack: stack)
    }

    // MARK: storage (pending until the user decides)

    func save(_ r: CrashReport) {
        guard let d = try? JSON.makeEncoder().encode(r) else { return }
        try? d.write(to: directory.appendingPathComponent("\(r.id).json"), options: .atomic)
    }

    var pending: [CrashReport] {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        return files.filter { $0.pathExtension == "json" }
            .compactMap { url -> CrashReport? in
                // The local id isn't part of the contract payload: it's the file name.
                guard var r = try? JSON.makeDecoder().decode(CrashReport.self, from: Data(contentsOf: url)) else { return nil }
                r.id = url.deletingPathExtension().lastPathComponent
                return r
            }
            .sorted { $0.occurredAt > $1.occurredAt }
    }

    func discard(_ id: String) {
        try? FileManager.default.removeItem(at: directory.appendingPathComponent("\(id).json"))
    }

    func discardAll() { for r in pending { discard(r.id) } }

    /// Approved by the user but not sent yet (server unreachable): retried quietly, never asked again.
    var approvedDirectory: URL { directory.appendingPathComponent("approved", isDirectory: true) }

    var approved: [CrashReport] {
        let files = (try? FileManager.default.contentsOfDirectory(at: approvedDirectory, includingPropertiesForKeys: nil)) ?? []
        return files.filter { $0.pathExtension == "json" }.compactMap { try? JSON.makeDecoder().decode(CrashReport.self, from: Data(contentsOf: $0)) }
    }

    /// Sends one report (scrubbed, with device info and optional contact). If sending fails it moves to the
    /// approved queue (already scrubbed, with the user's choices) and is retried on later launches.
    func send(_ r: CrashReport, device: DeviceInfo, contact: ContactDetails?, repo: any GimanoRepository) async -> Bool {
        var out = r.scrubbed()
        out.device = device
        out.contact = contact
        discard(r.id)
        do {
            let created = try await repo.sendCrashReport(out)
            UserDefaults.standard.set("\(created.id) · \(created.signature ?? "-")", forKey: "crash.lastSent")
            return true
        } catch {
            try? FileManager.default.createDirectory(at: approvedDirectory, withIntermediateDirectories: true)
            if let d = try? JSON.makeEncoder().encode(out) {
                try? d.write(to: approvedDirectory.appendingPathComponent("\(r.id).json"), options: .atomic)
            }
            return false
        }
    }

    func retryApproved(repo: any GimanoRepository) async {
        let files = (try? FileManager.default.contentsOfDirectory(at: approvedDirectory, includingPropertiesForKeys: nil)) ?? []
        for f in files where f.pathExtension == "json" {
            guard let r = try? JSON.makeDecoder().decode(CrashReport.self, from: Data(contentsOf: f)) else { try? FileManager.default.removeItem(at: f); continue }
            if (try? await repo.sendCrashReport(r)) != nil { try? FileManager.default.removeItem(at: f) }
        }
    }

    // MARK: ObjC exceptions (written synchronously; MetricKit covers signals)

    private nonisolated(unsafe) static var exceptionDirectory: URL?

    private static func installExceptionHandler(directory: URL) {
        exceptionDirectory = directory
        NSSetUncaughtExceptionHandler { e in
            guard let dir = CrashReporter.exceptionDirectory else { return }
            let r = CrashReport(occurredAt: Date(), kind: "crash", exception: e.name.rawValue, message: e.reason,
                                stack: e.callStackSymbols.joined(separator: "\n"),
                                uptimeS: nil)
            if let d = try? JSON.makeEncoder().encode(r) { try? d.write(to: dir.appendingPathComponent("\(r.id).json")) }
        }
    }
}
