import SwiftUI

// MARK: - Labels

extension IssueKind {
    var title: LocalizedStringKey {
        switch self {
        case .roadClosedTemp: "Road closed"
        case .roadNotExist: "No road here"
        case .onewayWrongClosedDir, .onewayWrongOpenDir: "One way"
        case .turnNotAllowed: "Can't turn"
        case .oddRoute: "Odd route"
        case .newRoad: "New road"
        case .placeClosedPerm: "Permanently closed"
        case .placeClosedTemp: "Temporarily closed"
        case .placeHoursWrong: "Opening hours wrong"
        case .placeEntranceWrong: "Entrance or parking wrong"
        case .placeInfoWrong: "Name, type, phone"
        case .placeDuplicate: "Duplicate"
        case .unknown: "Road problem"
        }
    }

    var systemImage: String {
        switch self {
        case .roadClosedTemp: "xmark.octagon.fill"
        case .roadNotExist: "road.lanes"
        case .onewayWrongClosedDir, .onewayWrongOpenDir: "arrow.right.circle.fill"
        case .turnNotAllowed: "arrow.uturn.left.circle.fill"
        case .oddRoute: "point.topleft.down.to.point.bottomright.curvepath"
        case .newRoad: "plus.circle.fill"
        default: "exclamationmark.triangle.fill"
        }
    }
}

extension ClosureDuration {
    var title: LocalizedStringKey {
        switch self {
        case .unknown: "Don't know"
        case .today: "Today"
        case .days: "A few days"
        case .longWorks: "Long roadworks"
        }
    }
}

extension DeviationReason {
    var title: LocalizedStringKey {
        switch self {
        case .closed: "Road closed"
        case .noRoad: "No road here"
        case .oneWay: "One way"
        case .noTurn: "Can't turn"
        case .jam: "Bad jam"
        case .myChoice: "My own choice"
        }
    }
}

enum IssueText {
    static func subtype(_ s: String?) -> String? {
        switch s {
        case "accident": String(localized: "accident")
        case "flood": String(localized: "flood")
        case "event": String(localized: "event")
        case "roadworks": String(localized: "roadworks")
        default: nil
        }
    }

    /// "Avoiding Jalan X: closed (flood) · confirmed by 3 drivers · 2 h ago"
    static func avoidLine(_ i: RouteIssue) -> String {
        var parts: [String] = []
        let road = i.roadName ?? i.summary ?? String(localized: "a road")
        let what = IssueText.subtype(i.subtype).map { String(localized: "closed (\($0))") } ?? String(localized: "closed")
        parts.append(String(localized: "Avoiding \(road): \(what)"))
        if let n = i.confirmations, n >= 1 { parts.append(String(localized: "confirmed by \(Int(n)) drivers")) }
        if let t = i.createdAt { parts.append(t.formatted(.relative(presentation: .named))) }
        return parts.joined(separator: " · ")
    }
}

// MARK: - Report sheet: "Road problem" group

struct RoadProblemPanel: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var kind: IssueKind?
    @State private var duration: ClosureDuration = .unknown
    @State private var sending = false

    static let kinds: [IssueKind] = [.roadClosedTemp, .roadNotExist, .onewayWrongClosedDir, .turnNotAllowed, .oddRoute, .newRoad]
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 10), count: 3)

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            LazyVGrid(columns: columns, spacing: 10) {
                ForEach(Self.kinds, id: \.self) { k in
                    Button { kind = k } label: {
                        VStack(spacing: 6) {
                            Image(systemName: k.systemImage).font(.system(size: 28, weight: .bold)).foregroundStyle(Color.report)
                            Text(k.title).font(Tokens.Font.nunito(13, .black)).multilineTextAlignment(.center).foregroundStyle(Color.brandInk)
                        }
                        .frame(maxWidth: .infinity, minHeight: 88)
                        .selectableTile(kind == k, radius: 18)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("roadProblem.\(k.rawValue)")
                }
            }
            if kind == .roadClosedTemp {
                Text("For how long?").font(Tokens.Font.label).foregroundStyle(Color.textMuted)
                FlowLayout(spacing: 8) {
                    ForEach(ClosureDuration.allCases, id: \.self) { d in
                        Button { duration = d } label: {
                            Text(d.title).font(Tokens.Font.nunito(13, .black))
                                .padding(.horizontal, 12).frame(height: 36)
                                .foregroundStyle(duration == d ? Color.brandOn : Color.brandInk)
                                .background(duration == d ? Color.brand : Color.brandMist, in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("closureDuration.\(d.rawValue)")
                    }
                }
            }
            Text("One report never closes a road: drivers nearby are asked to confirm.")
                .font(Tokens.Font.small).foregroundStyle(Color.textMuted)
            Button {
                guard let kind else { return }
                sending = true
                Task {
                    let ok = await model.reportIssue(kind, subtype: kind == .roadClosedTemp && duration == .longWorks ? "roadworks" : nil,
                                                     duration: kind == .roadClosedTemp ? duration : nil)
                    sending = false
                    if ok { model.showReport = false; dismiss() }
                }
            } label: { Label("Send", systemImage: "paperplane.fill") }
                .buttonStyle(PrimaryButtonStyle(color: .report, foreground: .reportOn))
                .disabled(kind == nil || sending)
                .accessibilityIdentifier("sendRoadProblem")
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 24)
    }
}

// MARK: - "Why didn't you follow the route?"

struct DeviationPromptSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let event: DeviationEvent
    @State private var neverAgain = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Why didn't you follow the route?").font(Tokens.Font.title)
            if let road = event.roadName {
                Text("We suggested \(road).").font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
            }
            FlowLayout(spacing: 8) {
                ForEach(DeviationReason.allCases, id: \.self) { r in
                    Button {
                        model.answerDeviation(event, r, neverAskAgain: neverAgain)
                        dismiss()
                    } label: {
                        Text(r.title).font(Tokens.Font.nunito(14, .black)).foregroundStyle(Color.brandInk)
                            .padding(.horizontal, 14).frame(height: 40)
                            .background(Color.brandMist, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("deviation.\(r.rawValue)")
                }
            }
            Toggle("Don't ask again", isOn: $neverAgain).font(Tokens.Font.body).accessibilityIdentifier("deviationNeverAgain")
            Text("Only your answer and that spot are sent, never your trip.").font(Tokens.Font.small).foregroundStyle(Color.textMuted)
            Button("Skip") {
                model.answerDeviation(event, nil, neverAskAgain: neverAgain)
                dismiss()
            }
            .buttonStyle(SecondaryButtonStyle())
            .accessibilityIdentifier("deviationSkip")
        }
        .padding(Tokens.Space.gutter)
    }
}

// MARK: - "Still closed?" while driving

struct StillClosedCard: View {
    @Environment(AppModel.self) private var model
    let issue: Issue

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: issue.kind.systemImage).font(.system(size: 28, weight: .bold)).foregroundStyle(Color.report)
                    .frame(width: 56, height: 56)
                    .background(Color.reportTint, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(issue.kind.title).font(Tokens.Font.bodyBold)
                    Text(issue.roadName ?? issue.summary ?? "").font(Tokens.Font.caption).foregroundStyle(Color.textMuted).lineLimit(1)
                }
                Spacer()
                Button { model.stillClosedPrompt = nil } label: {
                    Image(systemName: "xmark").font(.system(size: 14, weight: .bold)).foregroundStyle(Color.textMuted)
                }
                .accessibilityLabel(Text("Close"))
            }
            Text("Still closed?").font(Tokens.Font.label).foregroundStyle(Color.textMuted)
            HStack(spacing: 8) {
                Button("Still closed") { model.voteIssue(issue, .still) }.buttonStyle(PrimaryButtonStyle())
                    .accessibilityIdentifier("issueVote.still")
                Button("It's open") { model.voteIssue(issue, .cleared) }.buttonStyle(SecondaryButtonStyle())
                    .accessibilityIdentifier("issueVote.cleared")
                Button("Not sure") { model.voteIssue(issue, .unsure) }.buttonStyle(SecondaryButtonStyle())
                    .accessibilityIdentifier("issueVote.unsure")
            }
        }
        .padding(Tokens.Space.gutter)
        .background(Color.surface, in: RoundedRectangle(cornerRadius: Tokens.Radius.lg, style: .continuous))
        .fabShadow()
        .accessibilityIdentifier("stillClosedCard")
    }
}

// MARK: - Route preview: "Avoiding …" / warnings

struct RouteIssuesBanner: View {
    @Environment(AppModel.self) private var model
    let route: Route
    @State private var details: RouteIssue?

    var body: some View {
        let issues = (route.issues ?? []).filter { $0.state.isOpen }
        VStack(alignment: .leading, spacing: 8) {
            ForEach(issues) { i in
                VStack(alignment: .leading, spacing: 6) {
                    if i.avoided == true {
                        Label(IssueText.avoidLine(i), systemImage: "arrow.triangle.branch").font(Tokens.Font.caption)
                        HStack(spacing: 12) {
                            Button("Details") { details = i }.font(Tokens.Font.label)
                            Button("Drive through anyway (I know this road)") { model.driveThroughAnyway(i) }
                                .font(Tokens.Font.label)
                                .accessibilityIdentifier("driveThroughAnyway")
                        }
                    } else {
                        Label(String(localized: "Reported on this route: \(i.roadName ?? i.summary ?? String(localized: "a road"))"),
                              systemImage: "exclamationmark.triangle.fill")
                            .font(Tokens.Font.caption)
                    }
                }
                .foregroundStyle(Color.amberInk)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.amberMist, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .accessibilityIdentifier("routeIssueBanner")
            }
        }
        .sheet(item: $details) { i in
            VStack(alignment: .leading, spacing: 10) {
                Label { Text(i.kind.title) } icon: { Image(systemName: i.kind.systemImage) }.font(Tokens.Font.title)
                Text(IssueText.avoidLine(i)).font(Tokens.Font.body)
                Text(i.state == .confirmed ? "Confirmed by drivers nearby." : "Reported, waiting for confirmation.")
                    .font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                Text("Reports expire, and traffic driving through clears them.").font(Tokens.Font.small).foregroundStyle(Color.textMuted)
            }
            .padding(Tokens.Space.gutter)
            .presentationDetents([.height(240)])
        }
    }
}

// MARK: - Place card: "Suggest an edit" menu + badges

struct PlaceIssueBadges: View {
    @Environment(AppModel.self) private var model
    let placeId: String
    let location: LatLng
    @State private var issues: [Issue] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(issues) { i in
                switch i.kind {
                case .placeClosedPerm:
                    VStack(alignment: .leading, spacing: 8) {
                        Label("Reported closed · confirm?", systemImage: "exclamationmark.triangle.fill").font(Tokens.Font.bodyBold)
                        HStack(spacing: 8) {
                            Button("Closed") { vote(i, .still) }.buttonStyle(SecondaryButtonStyle())
                            Button("Still open") { vote(i, .cleared) }.buttonStyle(SecondaryButtonStyle())
                        }
                    }
                    .padding(12)
                    .background(Color.amberMist, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .accessibilityIdentifier("badgeReportedClosed")
                case .placeClosedTemp:
                    Label(i.until.map { String(localized: "Temporarily closed until \($0.formatted(date: .abbreviated, time: .omitted))") }
                          ?? String(localized: "Temporarily closed"), systemImage: "clock.badge.xmark")
                        .font(Tokens.Font.bodyBold).foregroundStyle(Color.amberInk)
                        .accessibilityIdentifier("badgeClosedTemp")
                case .placeHoursWrong:
                    Label("Hours may have changed", systemImage: "clock.arrow.circlepath").font(Tokens.Font.caption).foregroundStyle(Color.amberInk)
                default:
                    EmptyView()
                }
            }
        }
        .task(id: placeId) {
            let box = BBox(minLng: location.lng, minLat: location.lat, maxLng: location.lng, maxLat: location.lat).expanded(byM: 300)
            let kinds: [IssueKind] = [.placeClosedPerm, .placeClosedTemp, .placeHoursWrong]
            issues = ((try? await model.repo.issuesNearby(bbox: box, kinds: kinds)) ?? []).filter { $0.placeId == placeId && $0.state.isOpen }
        }
    }

    private func vote(_ i: Issue, _ a: IssueVoteAnswer) {
        issues.removeAll { $0.id == i.id }
        model.voteIssue(i, a)
    }
}

/// The non-field "Suggest an edit" choices (closures, hours, entrance, duplicate) are sent as place issues.
struct PlaceIssueSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let kind: IssueKind
    let placeId: String
    let location: LatLng
    @State private var until = Date().addingTimeInterval(7 * 86400)
    @State private var hours = OpeningHours()
    @State private var sending = false

    var body: some View {
        NavigationStack {
            Form {
                switch kind {
                case .placeClosedTemp:
                    DatePicker("Closed until", selection: $until, in: Date()...Date().addingTimeInterval(60 * 86400), displayedComponents: .date)
                        .accessibilityIdentifier("closedUntil")
                case .placeHoursWrong:
                    OpeningHoursEditor(hours: $hours)
                default:
                    Text(kind == .placeClosedPerm ? "Drivers nearby will be asked to confirm before anything changes."
                                                  : "A maintainer will check it.")
                }
                Section {
                    Button("Send") {
                        sending = true
                        Task {
                            var payload: [String: String] = [:]
                            if kind == .placeClosedTemp { payload["until"] = LocalStore.timestamp(until) }
                            if kind == .placeHoursWrong { payload["openingHours"] = hours.osm }
                            let ok = await model.reportIssue(kind, placeId: placeId, at: location, source: .placeEdit,
                                                             payload: payload.isEmpty ? nil : payload)
                            sending = false
                            if ok { dismiss() }
                        }
                    }
                    .disabled(sending)
                    .accessibilityIdentifier("sendPlaceIssue")
                }
            }
            .navigationTitle(Text(kind.title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } } }
        }
    }
}
