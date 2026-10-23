import AVFoundation
import Foundation

enum SoundMode: String, CaseIterable, Sendable {
    case on, alertsOnly, off

    var next: SoundMode {
        switch self {
        case .on: .alertsOnly
        case .alertsOnly: .off
        case .off: .on
        }
    }

    var systemImage: String {
        switch self {
        case .on: "speaker.wave.2.fill"
        case .alertsOnly: "exclamationmark.bubble"
        case .off: "speaker.slash.fill"
        }
    }

    var title: LocalizedStringResource {
        switch self {
        case .on: "Sound on"
        case .alertsOnly: "Alerts only"
        case .off: "Sound off"
        }
    }
}

/// Spoken guidance via AVSpeechSynthesizer, in the app language (en / ms), ducking other audio.
@MainActor
final class VoiceGuide {
    var mode: SoundMode = .on
    private let synth = AVSpeechSynthesizer()

    static var languageCode: String {
        let lang = Bundle.main.preferredLocalizations.first ?? Locale.current.language.languageCode?.identifier ?? "en"
        return lang.hasPrefix("ms") ? "ms" : "en"
    }

    private lazy var voice: AVSpeechSynthesisVoice? = {
        if Self.languageCode == "ms" {
            // Malay voices aren't installed on every device; Indonesian is the closest fallback.
            return AVSpeechSynthesisVoice(language: "ms-MY") ?? AVSpeechSynthesisVoice(language: "id-ID") ?? AVSpeechSynthesisVoice(language: "en-GB")
        }
        return AVSpeechSynthesisVoice(language: "en-GB") ?? AVSpeechSynthesisVoice(language: "en-US")
    }()

    enum Kind { case guidance, alert }

    func speak(_ text: String, kind: Kind = .guidance) {
        switch (mode, kind) {
        case (.off, _), (.alertsOnly, .guidance): return
        default: break
        }
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .voicePrompt, options: [.duckOthers, .interruptSpokenAudioAndMixWithOthers])
        try? session.setActive(true)
        let u = AVSpeechUtterance(string: text)
        u.voice = voice
        u.rate = AVSpeechUtteranceDefaultSpeechRate * 1.02
        if synth.isSpeaking { synth.stopSpeaking(at: .word) }
        synth.speak(u)
    }

    func stop() {
        synth.stopSpeaking(at: .immediate)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

/// Decides which prompt to speak for the upcoming maneuver. Pure, so it is unit-testable.
struct VoiceAnnouncer: Sendable {
    enum Stage: Int, Sendable { case early, near }
    private var spoken: Set<String> = []
    var mode: Vehicle = .car

    init(mode: Vehicle = .car) { self.mode = mode }

    /// Early prompt distance grows with speed (highways); short for walking and cycling.
    static func earlyDistance(speedKmh: Double, mode: Vehicle = .car) -> Double {
        switch mode {
        case .walk: 120
        case .bicycle: 250
        default: speedKmh > 75 ? 1500 : 600
        }
    }

    static func nearDistance(speedKmh: Double, mode: Vehicle = .car) -> Double {
        switch mode {
        case .walk: 20
        case .bicycle: 40
        default: max(80, speedKmh / 3.6 * 7)
        }
    }

    mutating func prompt(maneuverIndex: Int, maneuver: Maneuver, distanceM: Double, speedKmh: Double, stopName: String? = nil) -> String? {
        let early = Self.earlyDistance(speedKmh: speedKmh, mode: mode)
        let near = Self.nearDistance(speedKmh: speedKmh, mode: mode)
        if distanceM <= near {
            guard spoken.insert("\(maneuverIndex)-near").inserted else { return nil }
            spoken.insert("\(maneuverIndex)-early")
            return ManeuverFormatter.voicePrompt(for: maneuver, distanceM: nil, mode: mode, stopName: stopName)
        }
        if distanceM <= early, distanceM > near + min(100, early / 4) {
            guard spoken.insert("\(maneuverIndex)-early").inserted else { return nil }
            // Our own localized phrasing (en/ms) rather than the backend's English verbalPre.
            return ManeuverFormatter.voicePrompt(for: maneuver, distanceM: distanceM, mode: mode, stopName: stopName)
        }
        return nil
    }

    mutating func reset() { spoken.removeAll() }
}
