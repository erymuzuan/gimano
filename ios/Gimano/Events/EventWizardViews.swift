import SwiftUI
import UIKit

// "Acara baharu" wizard (boards EwType, EwWhen, EwVenue, EwRoute, EwMore, EwAdvanced, EwReview): Jenis → Bila →
// (Di mana | Laluan) → Info → Semak. It edits the same `HostedEventDraft` as before (saved on every change), so
// publishing rules, revisions and drafts are unchanged. Editing an existing event opens on "Semak".
// Privacy: everything stays on this phone; the only server calls are the app's place search, the address shown while
// picking on the map and coordinates-only routing.

// MARK: - Shared pieces

/// Header of the wizard and the share screen: back, title, "Peribadi", five labelled segments.
struct EventWizardHeader: View {
    let title: String
    let current: WizardStep
    let journey: Bool
    var reachable: (WizardStep) -> Bool = { _ in false }
    let onBack: () -> Void
    var onSelect: (WizardStep) -> Void = { _ in }

    var body: some View {
        VStack(spacing: Tokens.Space.s) {
            HStack(spacing: Tokens.Space.m) {
                Button(action: onBack) {
                    Image(systemName: "chevron.left").font(.system(size: 18, weight: .heavy))
                        .foregroundStyle(Color.textPrimary)
                        .frame(width: 44, height: 44)
                        .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Back"))
                .accessibilityIdentifier("eventWizardBack")
                Text(verbatim: title).font(Tokens.Font.title).lineLimit(1)
                Spacer(minLength: 0)
                EventPrivateChip()
            }
            HStack(spacing: 6) {
                ForEach(WizardStep.allCases, id: \.self) { s in
                    Button { onSelect(s) } label: {
                        VStack(spacing: 4) {
                            Capsule().fill(s <= current ? Color.brand : Color.outline).frame(height: 5)
                            Text(s.label(journey: journey))
                                .font(s == current ? Tokens.Font.small.weight(.heavy) : Tokens.Font.small)
                                .foregroundStyle(s == current ? Color.textPrimary : Color.textMuted)
                                .lineLimit(1).minimumScaleFactor(0.8)
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.plain)
                    .disabled(!reachable(s) || s == current)
                    .accessibilityAddTraits(s == current ? .isSelected : [])
                    .accessibilityIdentifier("eventWizardStep.\(s.rawValue)")
                }
            }
        }
        .padding(.horizontal, Tokens.Space.gutter)
        .padding(.top, Tokens.Space.s)
        .padding(.bottom, Tokens.Space.s)
        .background(Color.appBackground)
    }
}

/// "Peribadi": nothing about the event reaches Manotu's servers.
struct EventPrivateChip: View {
    var body: some View {
        HStack(spacing: 4) {
            EventIconView(icon: .lock, size: 16)
            Text("Private").font(Tokens.Font.small.weight(.heavy)).foregroundStyle(Color.brandInk)
        }
        .padding(.horizontal, 10).padding(.vertical, 5)
        .background(Color.brandMist, in: Capsule())
        .accessibilityElement(children: .combine)
        .accessibilityHint(Text("Manotu's servers never receive this event."))
    }
}

/// A selectable or dashed "add" chip with an icon.
struct EventChip: View {
    let icon: EventIcon
    let text: Text
    var selected = false
    var dashed = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                EventIconView(icon: icon, size: 20)
                text.font(Tokens.Font.label).foregroundStyle(Color.textPrimary).lineLimit(1)
            }
            .padding(.leading, 8).padding(.trailing, 12).padding(.vertical, 7)
            .background(selected ? Color.brandMist : (dashed ? Color.clear : Color.surface), in: Capsule())
            .overlay {
                if dashed {
                    Capsule().strokeBorder(Color.brandTint, style: StrokeStyle(lineWidth: 1.5, dash: [5, 4]))
                } else {
                    Capsule().strokeBorder(selected ? Color.brand : Color.outline, lineWidth: 2)
                }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

private struct WizardCard<Content: View>: View {
    var tint: Color = .surface
    var stroke: Color? = nil
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.Space.s) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(Tokens.Space.l)
            .background(tint, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
            .overlay {
                if let stroke {
                    RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous).strokeBorder(stroke, lineWidth: 2)
                }
            }
    }
}

private struct DashedHint: View {
    let icon: EventIcon
    let text: Text
    var body: some View {
        HStack(alignment: .top, spacing: Tokens.Space.s) {
            EventIconView(icon: icon, size: 18)
            text.font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Tokens.Space.m)
        .overlay(RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous)
            .strokeBorder(Color.outline, style: StrokeStyle(lineWidth: 1.5, dash: [5, 4])))
    }
}

private func sectionLabel(_ t: LocalizedStringKey) -> some View {
    Text(t).font(Tokens.Font.small.weight(.heavy)).foregroundStyle(Color.textMuted).textCase(.uppercase)
}

private func fieldLabel(_ t: LocalizedStringKey) -> some View {
    Text(t).font(Tokens.Font.small.weight(.bold)).foregroundStyle(Color.textMuted)
}

private extension View {
    func wizardField() -> some View {
        padding(.horizontal, 14).padding(.vertical, 12)
            .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous).strokeBorder(Color.outline, lineWidth: 1.5))
    }
}

// MARK: - Wizard

struct EventWizardView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let eventId: String
    /// `.type` for a new event, `.review` when editing.
    let startStep: WizardStep

    @State private var draft: HostedEventDraft?
    @State private var step: WizardStep
    @State private var furthest: WizardStep
    @State private var multiDay = false
    @State private var customTime = false
    /// The time was chosen by hand: changing the template no longer moves it.
    @State private var timeTouched = false
    /// The name the template filled in (replaced when the template changes, unless the host typed their own).
    @State private var prefilledTitle: String?
    @State private var problems: [EventDraftIssue] = []
    @State private var publishError: String?
    @State private var confirmDelete = false
    @State private var showAdvanced = false
    @State private var adding: AddPlace?
    @State private var editingStop: HostedStopDraft?
    @State private var buildingRoute: RouteDraft?
    @State private var dayIndex = 0
    @State private var safetyCode: String?
    @State private var showAgenda = false
    @State private var blockedTap = 0
    @FocusState private var focusedInfo: String?

    init(eventId: String, startStep: WizardStep) {
        self.eventId = eventId
        self.startStep = startStep
        _step = State(initialValue: startStep)
        _furthest = State(initialValue: startStep)
    }

    /// What a place picked in the picker becomes.
    struct AddPlace: Identifiable {
        enum Purpose { case mainVenue, extra, routeStop }
        let id = UUID()
        var purpose: Purpose
        var kind: EventStopKind
    }

    var body: some View {
        Group {
            if let d = draft {
                content(d)
            } else {
                ContentUnavailableView("Event not found", systemImage: "questionmark.circle")
            }
        }
        .background(Color.appBackground)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear(perform: load)
        .onChange(of: draft) { _, d in if let d { model.events.saveDraft(d) } }
        .sheet(item: $adding) { a in
            EventPlacePicker { label, location, guess in add(a, label: label, location: location, guess: guess) }
        }
        .sheet(item: $editingStop) { s in
            EventStopEditor(stop: s) { updated in
                if let i = draft?.stops.firstIndex(where: { $0.id == updated.id }) { draft?.stops[i] = updated }
                draft?.syncRoutesWithStops()
                calculateRoutes()
            } onDelete: {
                removeStop(s.id)
            }
        }
        .sheet(isPresented: $showAdvanced) {
            EventAdvancedSheet(draft: binding)
                .presentationDetents([.medium, .large])
        }
        .fullScreenCover(item: $buildingRoute) { r in
            let d = draft ?? .new()
            RouteBuilderView(route: r, stops: d.stops, context: .event(title: d.title.isEmpty ? String(localized: "New event") : d.title),
                             days: d.days, transport: model.routeBuilderTransport, isDemo: model.routeBuilderIsDemo,
                             start: d.stops.first?.location ?? model.position) { saved, stops in
                guard var nd = draft else { return }
                nd.stops = stops
                if let i = nd.routes.firstIndex(where: { $0.id == saved.id }) { nd.routes[i] = saved } else { nd.routes.append(saved) }
                nd.syncRoutesWithStops()
                draft = nd
            }
        }
        .alert("Can't share yet", isPresented: Binding(get: { publishError != nil }, set: { if !$0 { publishError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(verbatim: publishError ?? "")
        }
        .confirmationDialog("Delete this event from this phone?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) { model.events.deleteHosted(eventId) }
        } message: {
            Text("Invitations you already sent keep working until their access ends.")
        }
        .sensoryFeedback(.error, trigger: blockedTap)
    }

    private var binding: Binding<HostedEventDraft> {
        Binding(get: { draft ?? .new() }, set: { draft = $0 })
    }

    private func load() {
        guard draft == nil, let h = model.events.hostedEvent(eventId) else { return }
        draft = h.draft
        var cal = Calendar.current
        cal.timeZone = h.draft.timeZone
        multiDay = !cal.isDate(h.draft.start, inSameDayAs: h.draft.end)
        showAgenda = !h.draft.sessions.isEmpty
        if startStep == .type {
            furthest = .type
            // A brand-new draft: the template's name and first suggested time.
            if h.draft.title.isEmpty, h.draft.stops.isEmpty, h.draft.routes.isEmpty { applyTemplate(h.draft.template, initial: true) }
        } else {
            furthest = .review
            timeTouched = true
        }
        if step == .review { refreshReview() }
    }

    // MARK: layout

    private func headerTitle(_ d: HostedEventDraft) -> String {
        if startStep == .review { return d.title.isEmpty ? String(localized: "Edit event") : d.title }
        return String(localized: "New event")
    }

    private func content(_ d: HostedEventDraft) -> some View {
        VStack(spacing: 0) {
            EventWizardHeader(title: headerTitle(d), current: step, journey: d.isJourney,
                              reachable: { $0 <= furthest }, onBack: back, onSelect: go)
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: Tokens.Space.l) {
                        switch step {
                        case .type: typeStep(d)
                        case .when: whenStep(d)
                        case .place: if d.isJourney { routeStep(d) } else { venueStep(d) }
                        case .info: infoStep(d)
                        case .review: reviewStep(d)
                        }
                    }
                    .padding(.horizontal, Tokens.Space.gutter)
                    .padding(.bottom, Tokens.Space.xl)
                    .id("top")
                }
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: step) { withAnimation { proxy.scrollTo("top", anchor: .top) } }
            }
        }
        .safeAreaInset(edge: .bottom) { bottomBar(d) }
    }

    private func stepTitle(_ t: LocalizedStringKey, _ sub: LocalizedStringKey? = nil) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(t).font(Tokens.Font.nunito(28, .black, relativeTo: .largeTitle)).foregroundStyle(Color.textPrimary)
                .accessibilityAddTraits(.isHeader)
            if let sub { Text(sub).font(Tokens.Font.caption).foregroundStyle(Color.textMuted) }
        }
        .padding(.top, Tokens.Space.xs)
    }

    @ViewBuilder
    private func bottomBar(_ d: HostedEventDraft) -> some View {
        HStack(spacing: Tokens.Space.m) {
            switch step {
            case .info:
                Button { go(.review) } label: { Text("Skip") }
                    .font(Tokens.Font.button).foregroundStyle(Color.brand)
                    .frame(minWidth: 88, minHeight: 56)
                    .accessibilityIdentifier("eventWizardSkip")
                nextButton
            case .review:
                Button { saveAndClose() } label: { Text("Draft") }
                    .buttonStyle(SecondaryButtonStyle())
                    .frame(width: 96)
                    .accessibilityHint(Text("Keeps the event on this phone without sharing it."))
                    .accessibilityIdentifier("eventWizardDraft")
                Button { share() } label: {
                    if model.events.publishing == eventId {
                        HStack(spacing: Tokens.Space.s) { ProgressView().tint(Color.brandOn); Text("Preparing invitation…") }
                    } else {
                        Label("Share invitation", systemImage: "square.and.arrow.up")
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(model.events.publishing != nil)
                .accessibilityIdentifier("eventShareInvitation")
            default:
                nextButton
            }
        }
        .padding(.horizontal, Tokens.Space.gutter)
        .padding(.top, Tokens.Space.s)
        .padding(.bottom, Tokens.Space.s)
        .background(Color.surface.shadow(.drop(color: .black.opacity(0.06), radius: 8, y: -2)), ignoresSafeAreaEdges: .bottom)
    }

    private var nextButton: some View {
        Button { if let n = step.next { go(n) } } label: { Text("Next") }
            .buttonStyle(PrimaryButtonStyle())
            .accessibilityIdentifier("eventWizardNext")
    }

    private func go(_ s: WizardStep) {
        focusedInfo = nil
        withAnimation(.easeInOut(duration: 0.2)) { step = s }
        furthest = max(furthest, s)
        if s == .review { refreshReview() }
        if s == .place, draft?.isJourney == true { calculateRoutes() }
    }

    private func back() {
        if step == startStep || step == .type {
            dismiss()
        } else if let p = step.previous {
            go(p)
        }
    }

    private func saveAndClose() {
        if let d = draft { model.events.saveDraft(d) }
        model.showToast(String(localized: "Draft saved on this phone"))
        dismiss()
    }

    // MARK: 1 · Jenis

    private func typeStep(_ d: HostedEventDraft) -> some View {
        VStack(alignment: .leading, spacing: Tokens.Space.m) {
            stepTitle("What kind of event?", "Pick one. The form follows it.")
            typeCard(d, journey: false)
            typeCard(d, journey: true)
        }
    }

    private func typeCard(_ d: HostedEventDraft, journey: Bool) -> some View {
        let selected = d.template.isJourney == journey
        let templates = journey ? EventTemplate.journeys : EventTemplate.oneVenue
        return VStack(alignment: .leading, spacing: Tokens.Space.m) {
            Button {
                if !selected, let t = templates.first { applyTemplate(t) }
            } label: {
                HStack(spacing: Tokens.Space.m) {
                    EventIconView(icon: journey ? .journey : .venue, size: 34)
                        .frame(width: 56, height: 56)
                        .background(selected ? Color.brandMist : Color.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(journey ? "Journey" : "One place").font(Tokens.Font.nunito(22, .black, relativeTo: .title2))
                        Text(journey ? "Start → stops → finish" : "All guests go to one location")
                            .font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 26)).foregroundStyle(selected ? Color.brand : Color.outline)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(selected ? .isSelected : [])
            .accessibilityIdentifier(journey ? "eventTypeJourney" : "eventTypeOnePlace")
            FlowLayout(spacing: 8) {
                ForEach(templates, id: \.self) { t in
                    EventChip(icon: t.eventIcon, text: Text(t.title), selected: d.template == t) { applyTemplate(t) }
                        .accessibilityIdentifier("eventTemplate.\(t.rawValue)")
                }
            }
            HStack(spacing: 6) {
                ForEach(journey ? [EventIcon.route, .rest, .finish] : [.parking, .entrance, .dropoff], id: \.self) {
                    EventIconView(icon: $0, size: 16)
                }
                Text(journey ? "with routes, rests and days" : "you can add parking, an entrance and a drop-off")
                    .font(Tokens.Font.small).foregroundStyle(Color.textMuted)
            }
        }
        .padding(Tokens.Space.l)
        .selectableTile(selected, radius: 20, tint: selected ? Color.surface : Color.surfaceAlt)
    }

    private func applyTemplate(_ t: EventTemplate, initial: Bool = false) {
        guard var d = draft else { return }
        let wasJourney = d.isJourney
        d.template = t
        let name = t.defaultName
        if d.title.isEmpty || d.title == prefilledTitle {
            d.title = name
            prefilledTitle = name
        }
        if !timeTouched, let p = t.timePresets.first {
            p.apply(to: &d, keepEndDay: false)
            multiDay = false
            customTime = false
        }
        // Routes follow the new kind of journey; nothing else is removed.
        if t.isJourney, !wasJourney || initial {
            for i in d.routes.indices where d.routes[i].points.isEmpty { d.routes[i].mode = t.defaultMode }
        }
        draft = d
    }

    // MARK: 2 · Bila

    private func whenStep(_ d: HostedEventDraft) -> some View {
        let presets = d.template.timePresets
        let chosen = presets.first { $0.matches(d) }
        let tz = d.timeZone
        return VStack(alignment: .leading, spacing: Tokens.Space.m) {
            stepTitle("When?")
            fieldLabel("Event name")
            HStack(spacing: Tokens.Space.s) {
                EventIconView(icon: d.template.eventIcon, size: 24)
                TextField("Event name", text: binding.title)
                    .font(Tokens.Font.bodyBold)
                    .submitLabel(.done)
                    .accessibilityIdentifier("eventTitleField")
            }
            .wizardField()
            if problems.contains(.titleMissing), d.title.trimmingCharacters(in: .whitespaces).isEmpty {
                Text(verbatim: EventDraftIssue.titleMissing.message).font(Tokens.Font.caption).foregroundStyle(Color.speeding)
            }
            fieldLabel("Date")
            HStack(spacing: Tokens.Space.s) {
                EventIconView(icon: .calendar, size: 22)
                DatePicker("Date", selection: shifting, displayedComponents: .date)
                    .labelsHidden()
                Spacer(minLength: 0)
            }
            .wizardField()
            fieldLabel("Time")
            FlowLayout(spacing: 8) {
                ForEach(presets, id: \.self) { p in
                    EventChip(icon: .clock, text: p.allDay ? Text("All day") : Text(verbatim: p.text),
                              selected: chosen == p && !customTime) {
                        guard var nd = draft else { return }
                        p.apply(to: &nd, keepEndDay: multiDay)
                        draft = nd
                        customTime = false
                        timeTouched = true
                    }
                    .accessibilityIdentifier("eventTimePreset.\(p.text)")
                }
                EventChip(icon: .clock, text: Text("Choose time"), selected: customTime || chosen == nil) {
                    customTime = true
                    timeTouched = true
                }
                .accessibilityIdentifier("eventTimeCustom")
            }
            if customTime || chosen == nil {
                VStack(spacing: Tokens.Space.s) {
                    DatePicker("Starts", selection: shiftingTime, displayedComponents: .hourAndMinute)
                        .accessibilityIdentifier("eventStartTime")
                    DatePicker("Ends", selection: Binding(get: { d.end }, set: { draft?.end = $0; timeTouched = true }),
                               in: d.start..., displayedComponents: multiDay ? [.date, .hourAndMinute] : .hourAndMinute)
                        .accessibilityIdentifier("eventEndTime")
                }
                .font(Tokens.Font.body)
                .wizardField()
            }
            HStack(spacing: Tokens.Space.m) {
                EventIconView(icon: .calendar, size: 24)
                Toggle(isOn: Binding(get: { multiDay }, set: { on in
                    multiDay = on
                    guard var nd = draft else { return }
                    var cal = Calendar(identifier: .gregorian)
                    cal.timeZone = tz
                    if on, cal.isDate(nd.start, inSameDayAs: nd.end) {
                        nd.end = cal.date(byAdding: .day, value: 1, to: nd.end) ?? nd.end
                    } else if !on, !cal.isDate(nd.start, inSameDayAs: nd.end) {
                        let comps = cal.dateComponents([.hour, .minute], from: nd.end)
                        nd.end = cal.date(bySettingHour: comps.hour ?? 0, minute: comps.minute ?? 0, second: 0, of: nd.start) ?? nd.end
                        if nd.end <= nd.start { nd.end = nd.start.addingTimeInterval(3600) }
                    }
                    draft = nd
                })) { Text("More than one day").font(Tokens.Font.bodyBold) }
                    .tint(Color.brand)
                    .accessibilityIdentifier("eventMultiDay")
            }
            .padding(Tokens.Space.m)
            .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
            if multiDay {
                DatePicker("Ends", selection: Binding(get: { d.end }, set: { draft?.end = $0; timeTouched = true }),
                           in: d.start..., displayedComponents: [.date, .hourAndMinute])
                    .font(Tokens.Font.body)
                    .wizardField()
            }
            if d.end <= d.start {
                Text(verbatim: EventDraftIssue.endBeforeStart.message).font(Tokens.Font.caption).foregroundStyle(Color.speeding)
            } else if d.end.timeIntervalSince(d.start) > Double(EventLimits.maxEventDays) * 86_400 {
                Text(verbatim: EventDraftIssue.tooLong.message).font(Tokens.Font.caption).foregroundStyle(Color.speeding)
            }
            if let first = presets.first {
                DashedHint(icon: d.template.eventIcon,
                           text: first.allDay
                            ? Text("Name, organizer and time can be changed any time.")
                            : Text("\(Text(d.template.title)): usually \(first.text). Name, organizer and time can be changed any time."))
            }
        }
        .environment(\.timeZone, tz)
    }

    /// Changing the date moves the end by the same amount (the event keeps its length).
    private var shifting: Binding<Date> {
        Binding(get: { draft?.start ?? .now }, set: { new in
            guard let d = draft else { return }
            let delta = new.timeIntervalSince(d.start)
            draft?.start = new
            draft?.end = d.end.addingTimeInterval(delta)
        })
    }

    private var shiftingTime: Binding<Date> {
        Binding(get: { draft?.start ?? .now }, set: { new in
            timeTouched = true
            guard let d = draft else { return }
            let delta = new.timeIntervalSince(d.start)
            draft?.start = new
            draft?.end = d.end.addingTimeInterval(delta)
        })
    }

    // MARK: 3A · Di mana

    private func venueStep(_ d: HostedEventDraft) -> some View {
        let main = d.mainVenue
        let extras = d.stops.filter { $0.id != main?.id }
        return VStack(alignment: .leading, spacing: Tokens.Space.m) {
            stepTitle("Where?", "The main location. Parking and entrance if needed.")
            if let main {
                WizardCard(stroke: Color.outline) {
                    HStack(alignment: .top, spacing: Tokens.Space.m) {
                        EventIconView(icon: .venue, size: 30)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(verbatim: main.label.isEmpty ? String(localized: "Unnamed place") : main.label)
                                .font(Tokens.Font.bodyBold)
                            Text(verbatim: String(format: "%.5f, %.5f", main.lat, main.lng))
                                .font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                            if !main.notes.isEmpty {
                                Text(verbatim: main.notes).font(Tokens.Font.caption).foregroundStyle(Color.textMuted).lineLimit(2)
                            }
                        }
                        Spacer(minLength: 0)
                        Button("Change") { adding = AddPlace(purpose: .mainVenue, kind: .venue) }
                            .font(Tokens.Font.label).foregroundStyle(Color.brand)
                            .accessibilityIdentifier("eventVenueChange")
                    }
                    Button { editingStop = main } label: {
                        Label("Name, notes and vehicle entrance", systemImage: "slider.horizontal.3").font(Tokens.Font.label)
                    }
                    .accessibilityIdentifier("eventStop.\(main.id)")
                }
            } else {
                Button { adding = AddPlace(purpose: .mainVenue, kind: .venue) } label: {
                    HStack(spacing: Tokens.Space.m) {
                        EventIconView(icon: .venue, size: 30)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Choose the location").font(Tokens.Font.bodyBold)
                            Text("Map, search or saved places").font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                        }
                        Spacer(minLength: 0)
                        Image(systemName: "chevron.right").foregroundStyle(Color.textMuted)
                    }
                    .padding(Tokens.Space.l)
                    .overlay(RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous)
                        .strokeBorder(problems.contains(.noStops) ? Color.speeding : Color.brandTint, style: StrokeStyle(lineWidth: 2, dash: [6, 5])))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("eventAddPlace")
            }
            sectionLabel("Extra places · optional").padding(.top, Tokens.Space.s)
            ForEach(extras) { s in stopRow(s) }
            FlowLayout(spacing: 8) {
                ForEach(EventStopKind.venueChips, id: \.self) { k in
                    EventChip(icon: k.eventIcon, text: Text(verbatim: k == .entrance ? String(localized: "Gate") : k.name), dashed: true) {
                        adding = AddPlace(purpose: .extra, kind: k)
                    }
                    .accessibilityLabel(Text("Add \(k.name)"))
                    .accessibilityIdentifier("eventAddStop.\(k.rawValue)")
                }
            }
            problemList(d, for: .place)
        }
    }

    private func stopRow(_ s: HostedStopDraft, time: Date? = nil, tz: TimeZone? = nil) -> some View {
        Button { editingStop = s } label: {
            HStack(spacing: Tokens.Space.m) {
                EventIconView(icon: s.kind.eventIcon, size: 26)
                    .frame(width: 44, height: 44)
                    .background(Color.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: s.label.isEmpty ? String(localized: "Unnamed place") : s.label).font(Tokens.Font.bodyBold).lineLimit(1)
                    let sub = [s.kind.name, s.notes].filter { !$0.isEmpty }.joined(separator: " · ")
                    Text(verbatim: sub).font(Tokens.Font.small).foregroundStyle(Color.textMuted).lineLimit(1)
                    if problems.contains(where: { $0.stopId == s.id }) {
                        Text("Check this place").font(Tokens.Font.small).foregroundStyle(Color.speeding)
                    }
                }
                Spacer(minLength: 0)
                if !s.audience.isEmpty {
                    Text(verbatim: s.audience).font(Tokens.Font.small.weight(.heavy)).foregroundStyle(Color.brandInk)
                        .padding(.horizontal, 8).padding(.vertical, 3).background(Color.brandMist, in: Capsule())
                }
                if let time, let tz {
                    Text(verbatim: time.formatted(Date.FormatStyle(date: .omitted, time: .shortened, timeZone: tz)))
                        .font(Tokens.Font.label).foregroundStyle(Color.brand)
                }
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Color.textMuted)
            }
            .padding(Tokens.Space.m)
            .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("eventStop.\(s.id)")
        .contextMenu {
            Button("Remove", systemImage: "trash", role: .destructive) { removeStop(s.id) }
        }
        .accessibilityAction(named: Text("Remove")) { removeStop(s.id) }
    }

    private func add(_ a: AddPlace, label: String, location: LatLng, guess: EventStopKind) {
        guard var d = draft else { return }
        let name = String(String.UnicodeScalarView(label.trimmingCharacters(in: .whitespacesAndNewlines).unicodeScalars.prefix(EventLimits.stopLabelChars)))
        switch a.purpose {
        case .mainVenue:
            if let main = d.mainVenue, let i = d.stops.firstIndex(where: { $0.id == main.id }) {
                d.stops[i].location = location
                d.stops[i].entrance = nil
                if !name.isEmpty { d.stops[i].label = name }
                d.syncRoutesWithStops()
            } else {
                d.stops.insert(HostedStopDraft(kind: .venue, label: name.isEmpty ? String(localized: "Venue") : name, location: location), at: 0)
            }
            draft = d
        case .extra:
            let stop = HostedStopDraft(kind: a.kind, label: name.isEmpty ? a.kind.name : name, location: location)
            d.stops.append(stop)
            draft = d
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) { editingStop = stop }
        case .routeStop:
            let stop = HostedStopDraft(kind: a.kind, label: name.isEmpty ? a.kind.name : name, location: location)
            d.stops.append(stop)
            let ri: Int
            if let i = d.routeIndex(day: dayIndex) {
                ri = i
            } else {
                let ds = d.days
                d.routes.append(RouteDraft(modeRaw: (d.routes.first?.mode ?? d.template.defaultMode).rawValue,
                                           day: ds.isEmpty ? nil : ds[min(dayIndex, ds.count - 1)]))
                ri = d.routes.count - 1
            }
            let finishes = Set(d.stops.filter { $0.kind == .finish }.map(\.id))
            d.routes[ri].addStopPoint(stop, finishIds: finishes)
            draft = d
            calculateRoutes()
        }
    }

    private func removeStop(_ id: String) {
        guard var d = draft else { return }
        for r in d.routes.indices {
            while let i = d.routes[r].points.firstIndex(where: { $0.stopId == id }) { d.routes[r].deletePoint(i) }
        }
        d.stops.removeAll { $0.id == id }
        d.syncRoutesWithStops()
        draft = d
        calculateRoutes()
    }

    // MARK: 3B · Laluan

    private func routeStep(_ d: HostedEventDraft) -> some View {
        let days = d.days
        let ri = d.routeIndex(day: dayIndex)
        let route = ri.map { d.routes[$0] }
        let mode = route?.mode ?? d.routes.first?.mode ?? d.template.defaultMode
        let items = route.map { d.timeline($0) } ?? []
        let dayStart = dayStartDate(d, days: days)
        let times = route.map { d.arrivals($0, dayStart: dayStart) } ?? [:]
        let hasStart = items.contains { $0.kind == .start }
        let hasFinish = items.contains { $0.kind == .finish }
        let others = d.routes.indices.filter { $0 != ri }
        return VStack(alignment: .leading, spacing: Tokens.Space.m) {
            stepTitle("Route", "Start, stops, finish. The map comes later.")
            HStack(spacing: 4) {
                ForEach(EventTravelMode.all, id: \.self) { m in
                    Button { setMode(m) } label: {
                        GimanoIcon(icon: m.vehicle.gIcon, size: 28, stroke: m == mode ? Color.brandOn : Color.brandInk,
                                   fill: m == mode ? Color.brandOn.opacity(0.5) : Color.brandTint)
                            .frame(maxWidth: .infinity, minHeight: 52)
                            .background(m == mode ? Color.brand : Color.clear, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(m.vehicle.title))
                    .accessibilityAddTraits(m == mode ? .isSelected : [])
                    .accessibilityIdentifier("eventRouteMode.\(m.rawValue)")
                }
            }
            .padding(4)
            .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            if !days.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(days.indices, id: \.self) { i in
                            Button { dayIndex = i } label: {
                                Text("Day \(i + 1)").font(Tokens.Font.label)
                                    .padding(.horizontal, 14).padding(.vertical, 8)
                                    .foregroundStyle(i == dayIndex ? Color.brandOn : Color.textPrimary)
                                    .background(i == dayIndex ? Color.textPrimary : Color.surfaceAlt, in: Capsule())
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(i == dayIndex ? .isSelected : [])
                            .accessibilityIdentifier("eventRouteDay.\(i)")
                        }
                    }
                }
            }
            if items.isEmpty {
                DashedHint(icon: .route, text: Text("Add the start first, then the stops and the finish. Manotu follows the roads between them."))
            }
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(items.enumerated()), id: \.element.id) { i, s in
                    HStack(alignment: .center, spacing: 0) {
                        VStack(spacing: 0) {
                            Rectangle().fill(i == 0 ? Color.clear : Color.outline).frame(width: 3, height: 8)
                            Circle().fill(Color.brandMist).frame(width: 40, height: 40)
                                .overlay(EventIconView(icon: s.kind.eventIcon, size: 22))
                            Rectangle().fill(i == items.count - 1 ? Color.clear : Color.outline).frame(width: 3, height: 8)
                        }
                        .frame(width: 44)
                        stopRow(s, time: times[s.id], tz: d.timeZone).padding(.leading, Tokens.Space.s).padding(.vertical, 2)
                    }
                }
            }
            FlowLayout(spacing: 8) {
                if !hasStart {
                    EventChip(icon: .start, text: Text(verbatim: EventStopKind.start.name), dashed: true) {
                        adding = AddPlace(purpose: .routeStop, kind: .start)
                    }
                    .accessibilityLabel(Text("Add \(EventStopKind.start.name)"))
                    .accessibilityIdentifier("eventAddStop.start")
                }
                ForEach([EventStopKind.checkpoint, .water, .rest] + d.template.extraRouteStops, id: \.self) { k in
                    EventChip(icon: k == .checkpoint ? .stop : k.eventIcon,
                              text: Text(verbatim: k == .checkpoint ? String(localized: "Stop") : k.name), dashed: true) {
                        adding = AddPlace(purpose: .routeStop, kind: k)
                    }
                    .accessibilityLabel(Text("Add \(k == .checkpoint ? String(localized: "Stop") : k.name)"))
                    .accessibilityIdentifier("eventAddStop.\(k.rawValue)")
                }
                if !hasFinish {
                    EventChip(icon: .finish, text: Text(verbatim: EventStopKind.finish.name), dashed: true) {
                        adding = AddPlace(purpose: .routeStop, kind: .finish)
                    }
                    .accessibilityLabel(Text("Add \(EventStopKind.finish.name)"))
                    .accessibilityIdentifier("eventAddStop.finish")
                }
            }
            Button {
                if let route { buildingRoute = route } else {
                    let ds = d.days
                    buildingRoute = RouteDraft(modeRaw: mode.rawValue, day: ds.isEmpty ? nil : ds[min(dayIndex, ds.count - 1)])
                }
            } label: {
                HStack(spacing: Tokens.Space.m) {
                    EventIconView(icon: .route, size: 28)
                        .frame(width: 48, height: 48)
                        .background(Color.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Build the route on the map").font(Tokens.Font.bodyBold)
                        if let route {
                            Group {
                                if route.needsCalculation > 0 || route.hasUnmatched {
                                    Text("\(RouteFormat.summary(route)) · to calculate").foregroundStyle(problems.contains { $0.routeId == route.id } ? Color.speeding : Color.amberInk)
                                } else {
                                    Text(verbatim: RouteFormat.summary(route) + (route.hasSketch ? "" : " · " + String(localized: "follows roads")))
                                }
                            }
                            .font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                        }
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right").foregroundStyle(Color.textMuted)
                }
                .padding(Tokens.Space.m)
                .background(Color.brandMist, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("eventAddRoute")
            ForEach(others, id: \.self) { i in
                let r = d.routes[i]
                Button { buildingRoute = r } label: {
                    RouteRow(route: r, fallbackName: String(localized: "Route \(i + 1)"), problem: problems.contains { $0.routeId == r.id })
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("eventRoute.\(r.id)")
                .contextMenu {
                    Button("Delete route", systemImage: "trash", role: .destructive) { draft?.routes.removeAll { $0.id == r.id } }
                }
            }
            let mine = model.events.personalRoutes
            if !mine.isEmpty, d.routes.count < EventLimits.maxRoutes {
                Menu {
                    ForEach(mine) { r in
                        Button(r.title.isEmpty ? String(localized: "Route") : r.title) {
                            var copy = r
                            copy.id = EventRandom.itemId()
                            for i in copy.points.indices { copy.points[i].stopId = nil }
                            if !days.isEmpty { copy.day = days[min(dayIndex, days.count - 1)] }
                            draft?.routes.append(copy)
                        }
                    }
                } label: { Label("From My routes", systemImage: "tray.and.arrow.down").font(Tokens.Font.label) }
            }
            // Places that aren't on any route (e.g. kept from another kind of event).
            let onRoutes = Set(d.routes.flatMap { $0.points.compactMap(\.stopId) })
            let loose = d.stops.filter { !onRoutes.contains($0.id) }
            if !loose.isEmpty {
                sectionLabel("Other places").padding(.top, Tokens.Space.s)
                ForEach(loose) { s in stopRow(s) }
            }
            problemList(d, for: .place)
        }
    }

    private func dayStartDate(_ d: HostedEventDraft, days: [String]) -> Date {
        guard dayIndex > 0, !days.isEmpty else { return d.start }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = d.timeZone
        let day = cal.date(byAdding: .day, value: dayIndex, to: cal.startOfDay(for: d.start)) ?? d.start
        let c = cal.dateComponents([.hour, .minute], from: d.start)
        return cal.date(bySettingHour: c.hour ?? 8, minute: c.minute ?? 0, second: 0, of: day) ?? day
    }

    private func setMode(_ m: EventTravelMode) {
        guard var d = draft else { return }
        for i in d.routes.indices { d.routes[i].setMode(m) }
        draft = d
        calculateRoutes()
    }

    /// Calculates waiting legs right here (coordinates and travel mode only), so the timeline gets times without
    /// opening the map. A result is kept only while the route is unchanged.
    private func calculateRoutes() {
        guard let d = draft else { return }
        for r in d.routes where r.needsCalculation > 0 && r.legs.contains(where: { $0.state == .pending }) {
            let m = RouteBuilderModel(route: r, stops: d.stops, context: .event(title: d.title), transport: model.routeBuilderTransport,
                                      isDemo: model.routeBuilderIsDemo)
            m.resolvePending()
            Task { @MainActor in
                await m.settle()
                guard var nd = draft, let i = nd.routes.firstIndex(where: { $0.id == r.id }), nd.routes[i].points == r.points,
                      nd.routes[i].mode == r.mode else { return }
                nd.routes[i] = m.route
                draft = nd
            }
        }
    }

    // MARK: 4 · Info

    private func infoStep(_ d: HostedEventDraft) -> some View {
        let suggested = (d.template.suggestsAgenda ? [nil] : []) + d.template.suggestedInfo.map { Optional($0) }
        var chips: [EventInfoKind?] = suggested
        if !chips.contains(nil) { chips.append(nil) }
        for k in EventInfoKind.chips where !chips.contains(k) { chips.append(k) }
        if showAgenda || !d.sessions.isEmpty { chips.removeAll { $0 == nil } }
        let full = d.info.count >= EventLimits.maxInfoBlocks
        return VStack(alignment: .leading, spacing: Tokens.Space.m) {
            stepTitle("Info for guests", "All optional. Guests see only what you fill in.")
            fieldLabel("Message for guests")
            HStack(alignment: .top, spacing: Tokens.Space.s) {
                EventIconView(icon: .message, size: 22)
                TextField("e.g. Please come early for parking.", text: binding.description, axis: .vertical)
                    .font(Tokens.Font.bodyBold)
                    .lineLimit(1...6)
                    .accessibilityIdentifier("eventDescriptionField")
            }
            .wizardField()
            if showAgenda || !d.sessions.isEmpty { agendaCard(d) }
            ForEach(d.info) { b in infoCard(b) }
            sectionLabel("Add").padding(.top, Tokens.Space.xs)
            FlowLayout(spacing: 8) {
                ForEach(Array(chips.enumerated()), id: \.offset) { _, k in
                    if let k {
                        EventChip(icon: k.eventIcon, text: Text(k.title), dashed: true) { addInfo(k) }
                            .disabled(full)
                            .accessibilityLabel(Text("Add \(Text(k.title))"))
                            .accessibilityIdentifier("eventAddInfo.\(k.rawValue)")
                    } else {
                        EventChip(icon: .agenda, text: Text("Agenda"), dashed: true) { addSession() }
                            .accessibilityLabel(Text("Add agenda"))
                            .accessibilityIdentifier("eventAddInfo.agenda")
                    }
                }
            }
            Button { showAdvanced = true } label: {
                HStack(spacing: Tokens.Space.m) {
                    EventIconView(icon: .sliders, size: 26)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("More options").font(Tokens.Font.bodyBold)
                        Text("Organizer · access end · own note · file").font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right").foregroundStyle(Color.textMuted)
                }
                .padding(Tokens.Space.m)
                .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("eventAdvanced")
            problemList(d, for: .info)
        }
    }

    private func cardHeader(_ icon: EventIcon, _ title: Text, removeLabel: Text, remove: @escaping () -> Void) -> some View {
        HStack(spacing: Tokens.Space.s) {
            EventIconView(icon: icon, size: 22)
            title.font(Tokens.Font.bodyBold).accessibilityAddTraits(.isHeader)
            Spacer(minLength: 0)
            Button(action: remove) {
                Image(systemName: "xmark").font(.system(size: 13, weight: .heavy)).foregroundStyle(Color.textMuted)
                    .frame(width: 32, height: 32).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(removeLabel)
        }
    }

    private func agendaCard(_ d: HostedEventDraft) -> some View {
        WizardCard(stroke: Color.outline) {
            cardHeader(.agenda, Text("Agenda"), removeLabel: Text("Remove agenda")) {
                draft?.sessions = []
                showAgenda = false
            }
            ForEach(d.sessions) { s in
                HStack(spacing: Tokens.Space.s) {
                    DatePicker("Time", selection: Binding(get: { s.start }, set: { v in
                        if let i = draft?.sessions.firstIndex(where: { $0.id == s.id }) { draft?.sessions[i].start = v }
                    }), displayedComponents: d.isMultiDayLike ? [.date, .hourAndMinute] : .hourAndMinute)
                        .labelsHidden()
                    TextField("e.g. Arrival", text: Binding(get: { s.title }, set: { v in
                        if let i = draft?.sessions.firstIndex(where: { $0.id == s.id }) { draft?.sessions[i].title = v }
                    }))
                    .font(Tokens.Font.body)
                    .accessibilityIdentifier("eventSessionTitle")
                    Button {
                        draft?.sessions.removeAll { $0.id == s.id }
                    } label: {
                        Image(systemName: "minus.circle.fill").foregroundStyle(Color.textMuted).frame(width: 32, height: 32)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Remove this item"))
                }
                if problems.contains(.sessionTime(s.id)) || problems.contains(.sessionTitle(s.id)) {
                    Text("Check this item").font(Tokens.Font.small).foregroundStyle(Color.speeding)
                }
            }
            Button { addSession() } label: { Label("Add an item", systemImage: "plus").font(Tokens.Font.label) }
                .disabled(d.sessions.count >= EventLimits.maxSessions)
                .accessibilityIdentifier("eventAddSession")
        }
        .environment(\.timeZone, d.timeZone)
    }

    private func infoCard(_ b: HostedInfoDraft) -> some View {
        WizardCard(stroke: problems.contains(.infoTooLong(b.id)) ? Color.speeding : Color.outline) {
            cardHeader(b.kind.eventIcon, Text(b.kind.title), removeLabel: Text("Remove \(Text(b.kind.title))")) {
                draft?.info.removeAll { $0.id == b.id }
            }
            TextField(b.kind.prompt, text: Binding(get: { b.text }, set: { v in
                if let i = draft?.info.firstIndex(where: { $0.id == b.id }) { draft?.info[i].text = v }
            }), axis: .vertical)
            .font(Tokens.Font.body)
            .lineLimit(1...10)
            .focused($focusedInfo, equals: b.id)
            .accessibilityIdentifier("eventInfoText.\(b.kind.rawValue)")
            let n = EventValidation.scalars(b.text)
            if n > EventLimits.infoTextChars - 100 {
                Text(verbatim: "\(n)/\(EventLimits.infoTextChars)").font(Tokens.Font.small)
                    .foregroundStyle(n > EventLimits.infoTextChars ? Color.speeding : Color.textMuted)
            }
        }
    }

    private func addInfo(_ k: EventInfoKind) {
        guard let d = draft, d.info.count < EventLimits.maxInfoBlocks else { return }
        let b = HostedInfoDraft(kind: k)
        draft?.info.append(b)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { focusedInfo = b.id }
    }

    private func addSession() {
        guard let d = draft, d.sessions.count < EventLimits.maxSessions else { return }
        showAgenda = true
        let last = d.sessions.map(\.start).max()
        let start = last.map { min($0.addingTimeInterval(3600), d.end) } ?? d.start
        draft?.sessions.append(HostedSessionDraft(start: start))
    }

    // MARK: 5 · Semak

    private func refreshReview() {
        guard let d = draft else { return }
        problems = d.event(rev: 1, parent: nil, publishedAt: Date()).1
        if safetyCode == nil { safetyCode = model.events.hostSafetyCode(eventId) }
    }

    private func reviewRow(_ icon: EventIcon, _ text: Text, to s: WizardStep, id: String) -> some View {
        Button { go(s) } label: {
            HStack(spacing: Tokens.Space.m) {
                EventIconView(icon: icon, size: 24)
                text.font(Tokens.Font.bodyBold).foregroundStyle(Color.textPrimary).lineLimit(2).multilineTextAlignment(.leading)
                Spacer(minLength: 0)
                EventIconView(icon: .edit, size: 18)
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint(Text("Change"))
        .accessibilityIdentifier("eventReview.\(id)")
    }

    private func reviewStep(_ d: HostedEventDraft) -> some View {
        let hosted = model.events.hostedEvent(eventId)
        let main = d.mainVenue
        let extras = d.isJourney ? [] : d.stops.filter { $0.id != main?.id }
        var counts: [(EventStopKind, Int)] = []
        for s in extras {
            if let i = counts.firstIndex(where: { $0.0 == s.kind }) { counts[i].1 += 1 } else { counts.append((s.kind, 1)) }
        }
        let extrasText = counts.map { "\($0.1) \($0.0.name.lowercased())" }.joined(separator: " · ")
        return VStack(alignment: .leading, spacing: Tokens.Space.m) {
            stepTitle("Review")
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 6) {
                    EventIconView(icon: .eye, size: 18)
                    Text("Guests see").font(Tokens.Font.small.weight(.heavy)).foregroundStyle(Color.textMuted).textCase(.uppercase)
                }
                Button { go(.when) } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: d.title.isEmpty ? String(localized: "Untitled event") : d.title)
                            .font(Tokens.Font.nunito(22, .black, relativeTo: .title2)).foregroundStyle(Color.textPrimary)
                            .multilineTextAlignment(.leading)
                        Text(verbatim: EventFormat.range(d.start, d.end, d.timeZone)).font(Tokens.Font.caption.weight(.bold))
                            .foregroundStyle(Color.textMuted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, Tokens.Space.s)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint(Text("Change"))
                .accessibilityIdentifier("eventReview.when")
                Divider()
                if d.isJourney {
                    if d.routes.isEmpty {
                        reviewRow(.route, Text("No route yet"), to: .place, id: "route")
                    }
                    ForEach(d.routes) { r in
                        reviewRow(.route, Text(verbatim: [r.title.isEmpty ? nil : r.title, r.day, RouteFormat.summary(r)]
                            .compactMap { $0 }.joined(separator: " · ")), to: .place, id: "route.\(r.id)")
                        Divider()
                    }
                    reviewRow(.stop, Text("Places: \(d.stops.count)"), to: .place, id: "stops")
                } else {
                    reviewRow(.venue, main.map { Text(verbatim: $0.label) } ?? Text("No location yet"), to: .place, id: "venue")
                    if !extrasText.isEmpty {
                        Divider()
                        reviewRow(extras.first?.kind.eventIcon ?? .parking, Text(verbatim: extrasText), to: .place, id: "extras")
                    }
                }
                if !d.description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Divider()
                    reviewRow(.message, Text(verbatim: d.description), to: .info, id: "description")
                }
                let agenda = d.sessions.filter { !$0.title.trimmingCharacters(in: .whitespaces).isEmpty }
                if !agenda.isEmpty {
                    Divider()
                    reviewRow(.agenda, Text("Agenda · \(agenda.count)"), to: .info, id: "agenda")
                }
                ForEach(d.info.filter { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) { b in
                    Divider()
                    reviewRow(b.kind.eventIcon, Text(b.kind.title), to: .info, id: "info.\(b.id)")
                }
                let alias = d.alias.trimmingCharacters(in: .whitespacesAndNewlines)
                if !alias.isEmpty {
                    Divider()
                    reviewRow(.person, Text("Organizer: \(alias)"), to: .info, id: "alias")
                }
            }
            .padding(Tokens.Space.l)
            .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            if !problems.isEmpty {
                VStack(alignment: .leading, spacing: Tokens.Space.s) {
                    ForEach(Array(problems.enumerated()), id: \.offset) { _, p in
                        Button { go(p.wizardStep) } label: {
                            Label { Text(verbatim: p.message).multilineTextAlignment(.leading) } icon: { Image(systemName: "exclamationmark.circle.fill") }
                                .font(Tokens.Font.caption).foregroundStyle(Color.speeding)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .id(EventDraftIssue.Anchor.problems)
                .accessibilityIdentifier("eventReviewProblems")
            }
            if let code = safetyCode {
                HStack(spacing: Tokens.Space.m) {
                    EventIconView(icon: .safety, size: 26)
                    Text("Your safety code. Guests can check it.").font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                    Spacer(minLength: 0)
                    Text(verbatim: code).font(.system(.title3, design: .monospaced).weight(.bold)).foregroundStyle(Color.brandInk)
                        .padding(.horizontal, 10).padding(.vertical, 4)
                        .background(Color.brandMist, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                        .accessibilityIdentifier("eventHostSafetyCode")
                }
                .padding(Tokens.Space.m)
                .overlay(RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous).strokeBorder(Color.outline, lineWidth: 2))
                .accessibilityElement(children: .combine)
            }
            Button { showAdvanced = true } label: {
                HStack(spacing: 6) {
                    EventIconView(icon: .key, size: 18)
                    Text("Access ends \(d.expiresAt.formatted(Date.FormatStyle(date: .abbreviated, time: .shortened, timeZone: d.timeZone)))")
                        .font(Tokens.Font.small.weight(.bold)).foregroundStyle(Color.textMuted)
                }
            }
            .buttonStyle(.plain)
            .accessibilityHint(Text("Change"))
            .accessibilityIdentifier("eventExpiry")
            VStack(alignment: .leading, spacing: 4) {
                if let rev = hosted?.latestRev {
                    if let h = hosted, model.events.hasUnpublishedChanges(h) {
                        Text("Version \(Int(rev)) was shared. Share again to send your changes as version \(Int(rev) + 1).")
                    } else {
                        Text("Version \(Int(rev)) was shared.")
                    }
                }
                Text("Notes for the organizer stay on this phone. Manotu's servers never receive this event.")
            }
            .font(Tokens.Font.small).foregroundStyle(Color.textMuted)
            Button(role: .destructive) { confirmDelete = true } label: { Label("Delete event", systemImage: "trash") }
                .font(Tokens.Font.label)
                .padding(.top, Tokens.Space.s)
                .accessibilityIdentifier("eventDeleteHosted")
        }
        .onChange(of: draft) { refreshReview() }
    }

    @ViewBuilder
    private func problemList(_ d: HostedEventDraft, for s: WizardStep) -> some View {
        let mine = problems.filter { $0.wizardStep == s }
        if !mine.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(mine.enumerated()), id: \.offset) { _, p in
                    Label { Text(verbatim: p.message) } icon: { Image(systemName: "exclamationmark.circle.fill") }
                        .font(Tokens.Font.caption).foregroundStyle(Color.speeding)
                }
            }
        }
    }

    private func share() {
        guard let d = draft else { return }
        model.events.saveDraft(d)
        let (_, issues) = d.event(rev: 1, parent: nil, publishedAt: Date())
        problems = issues
        guard issues.isEmpty else { blockedTap += 1; return }
        Task { publishError = await model.events.publish(eventId) }
    }
}

private extension HostedEventDraft {
    var isMultiDayLike: Bool {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        return !cal.isDate(start, inSameDayAs: end)
    }
}

// MARK: - Lanjutan (EwAdvanced)

struct EventAdvancedSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Binding var draft: HostedEventDraft

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    row(.person, "Organizer name") {
                        TextField("e.g. Keluarga Aqil", text: $draft.alias)
                            .accessibilityIdentifier("eventAliasField")
                    }
                } footer: { Text("Shown to guests instead of your name. It isn't linked to your account.") }
                Section {
                    row(.key, "Access ends") {
                        DatePicker("Access ends", selection: Binding(get: { draft.expiresAt }, set: { draft.expiresAtOverride = $0 }),
                                   in: draft.end...)
                            .labelsHidden()
                            .environment(\.timeZone, draft.timeZone)
                    }
                    if draft.expiresAtOverride != nil {
                        Button("Reset to 24 hours after the event") { draft.expiresAtOverride = nil }
                    }
                } footer: { Text("After access ends, Manotu deletes the event from phones. Copies outside Manotu may still exist.") }
                Section {
                    row(.note, "Note for me") {
                        TextField("Not shared", text: Binding(get: { draft.organizerNote ?? "" },
                                                              set: { draft.organizerNote = $0.isEmpty ? nil : $0 }), axis: .vertical)
                            .lineLimit(1...4)
                    }
                } footer: { Text("Stays on this phone. It is never put in the invitation.") }
                Section {
                    row(.globe, "Invitation language") {
                        Picker("Invitation language", selection: $draft.lang) {
                            Text(verbatim: "BM").tag(EventLang.ms)
                            Text(verbatim: "EN").tag(EventLang.en)
                        }
                        .pickerStyle(.segmented)
                        .frame(width: 110)
                        .accessibilityLabel(Text("Invitation language"))
                    }
                    Toggle(isOn: $draft.sensitive) {
                        HStack(spacing: Tokens.Space.m) {
                            EventIconView(icon: .fileLock, size: 26)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Send as file + code").font(Tokens.Font.bodyBold)
                                Text("For sensitive events").font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                            }
                        }
                    }
                    .tint(Color.brand)
                    .accessibilityIdentifier("eventSensitiveToggle")
                }
            }
            .navigationTitle("More options")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }.accessibilityIdentifier("eventAdvancedDone")
                }
            }
        }
    }

    private func row<C: View>(_ icon: EventIcon, _ title: LocalizedStringKey, @ViewBuilder _ content: () -> C) -> some View {
        HStack(spacing: Tokens.Space.m) {
            EventIconView(icon: icon, size: 26)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(Tokens.Font.bodyBold)
                content()
            }
        }
    }
}
