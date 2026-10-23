import Foundation

/// Result chips (board "Search results with spelling fix").
enum ResultSort: String, CaseIterable, Sendable {
    case nearest, bestMatch, openNow
}

/// A row in the result list: one place, or a chain with its branches nearest-first.
enum ResultSection: Identifiable, Equatable, Sendable {
    case place(PlaceSummary)
    case chain(name: String, places: [PlaceSummary])

    var id: String {
        switch self {
        case .place(let p): "p:\(p.id)"
        case .chain(let name, _): "c:\(name.lowercased())"
        }
    }
}

enum SearchLogic {
    /// Chain key: the server's `chainGroup`, else the brand.
    static func chainKey(_ p: PlaceSummary) -> String? {
        let k = (p.chainGroup ?? p.brand)?.trimmingCharacters(in: .whitespaces)
        return k?.isEmpty == false ? k : nil
    }

    /// Sorts/filters, then folds branches of the same chain (≥ 2) into one group placed where its best member was.
    static func sections(_ results: [PlaceSummary], sort: ResultSort) -> [ResultSection] {
        var list = results
        switch sort {
        case .nearest: list.sort { ($0.distanceM ?? .infinity) < ($1.distanceM ?? .infinity) }
        case .bestMatch: break                                   // server order
        case .openNow: list = list.filter { $0.openNow == true }
        }
        var counts: [String: Int] = [:]
        for p in list { if let k = chainKey(p)?.lowercased() { counts[k, default: 0] += 1 } }
        var out: [ResultSection] = []
        var emitted = Set<String>()
        for p in list {
            if let key = chainKey(p), let n = counts[key.lowercased()], n >= 2 {
                guard emitted.insert(key.lowercased()).inserted else { continue }
                let branches = list.filter { chainKey($0)?.lowercased() == key.lowercased() }
                    .sorted { ($0.distanceM ?? .infinity) < ($1.distanceM ?? .infinity) }
                out.append(.chain(name: key, places: branches))
            } else {
                out.append(.place(p))
            }
        }
        return out
    }

    /// "FamilyMart" → "FM", "7-Eleven" → "7E", "Petronas" → "PE".
    static func initials(_ name: String) -> String {
        let caps = name.filter { $0.isUppercase || $0.isNumber }
        if caps.count >= 2 { return String(caps.prefix(2)) }
        let words = name.split(whereSeparator: { !$0.isLetter && !$0.isNumber })
        if words.count >= 2 { return String(words.prefix(2).compactMap(\.first)).uppercased() }
        return String(name.prefix(2)).uppercased()
    }

    /// Whether any result says if it's open (otherwise the "Open now" chip is hidden).
    static func knowsOpening(_ results: [PlaceSummary]) -> Bool { results.contains { $0.openNow != nil } }

    /// English and Malay words → a category to suggest "… nearby" when nothing matched.
    static let categoryWords: [(String, PlaceCategory)] = [
        ("kedai runcit", .convenience), ("runcit", .convenience), ("convenience", .convenience), ("kedai serbaneka", .convenience),
        ("stesen minyak", .fuel), ("minyak", .fuel), ("petrol", .fuel), ("fuel", .fuel), ("gas station", .fuel),
        ("kedai makan", .restaurant), ("restoran", .restaurant), ("restaurant", .restaurant), ("makan", .restaurant),
        ("warung", .restaurant), ("gerai", .restaurant), ("food", .restaurant), ("mamak", .restaurant),
        ("kafe", .cafe), ("cafe", .cafe), ("kopitiam", .cafe), ("coffee", .cafe),
        ("surau", .surau), ("masjid", .mosque), ("mosque", .mosque),
        ("klinik", .clinic), ("clinic", .clinic), ("hospital", .hospital),
        ("letak kereta", .parking), ("parkir", .parking), ("parking", .parking),
        ("bengkel", .carRepair), ("workshop", .carRepair), ("mechanic", .carRepair),
        ("sekolah", .school), ("school", .school), ("tol", .tollBooth), ("toll", .tollBooth),
        ("hentian rehat", .restArea), ("r&r", .restArea), ("rnr", .restArea),
        ("cas ev", .evCharging), ("ev charging", .evCharging), ("pengecas", .evCharging),
    ]

    static func categoryHint(in text: String) -> PlaceCategory? {
        let cleaned = text.lowercased().folding(options: .diacriticInsensitive, locale: nil)
            .map { $0.isLetter || $0.isNumber || $0 == "&" ? $0 : " " }
        let t = " " + String(cleaned) + " "
        return categoryWords.first { t.contains(" \($0.0) ") }?.1
    }
}
