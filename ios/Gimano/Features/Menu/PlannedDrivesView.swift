import SwiftUI

/// Menu → Planned drives: list, edit, delete; "Leave by" from departure times.
struct PlannedDrivesView: View {
    @Environment(AppModel.self) private var model

    /// The soonest upcoming leave-by across all drives (hero card).
    private var next: (PlannedDrive, Date)? {
        model.plannedDrives.compactMap { d in (model.nextLeaveBy[d.id] ?? d.departAt).map { (d, $0) } }
            .filter { $0.1 > .now.addingTimeInterval(-600) }
            .min { $0.1 < $1.1 }
    }

    var body: some View {
        List {
            if model.plannedDrives.isEmpty {
                ContentUnavailableView("No planned drives", systemImage: "calendar",
                                       description: Text("Plan a drive here or from the route screen with “Leave later”."))
            }
            if let (d, lb) = next {
                Button { model.openPlannedDrive(id: d.id) } label: { hero(d, leaveBy: lb) }
                    .buttonStyle(.plain)
                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                    .listRowBackground(Color.clear)
                    .accessibilityIdentifier("plannedDriveHero")
            }
            ForEach(model.plannedDrives) { d in
                Button { model.editingDrive = d } label: { row(d) }
                    .accessibilityIdentifier("plannedDrive.\(d.title)")
                    .swipeActions {
                        Button("Delete", role: .destructive) { model.deletePlannedDrive(d) }
                        Button("Go") { model.openPlannedDrive(id: d.id) }.tint(.brand)
                    }
            }
            Section {
                Label("Saved on this phone only. “Leave by” reminders come from your phone.", systemImage: "lock.shield")
                    .font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                    .listRowBackground(Color.clear)
            }
        }
        .navigationTitle("Planned drives")
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { model.editingDrive = model.newPlannedDrive(from: nil) } label: { Image(systemName: "plus") }
                    .accessibilityLabel(Text("Plan a drive"))
                    .accessibilityIdentifier("addPlannedDrive")
            }
        }
        .task { await model.rescheduleAllPlannedDrives() }
    }

    private func hero(_ d: PlannedDrive, leaveBy: Date) -> some View {
        HStack(spacing: 14) {
            Image(systemName: "clock.fill").font(.system(size: 30, weight: .bold))
                .frame(width: 54, height: 54)
                .background(Color.brandOn.opacity(0.15), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text("\(leaveBy.formatted(.relative(presentation: .named))) · to \(d.title)").font(Tokens.Font.caption)
                Text("Leave \(leaveBy.formatted(date: .omitted, time: .shortened))").font(Tokens.Font.nunito(26, .black))
                    .accessibilityIdentifier("leaveBy")
                if let arrive = d.nextArrivals(after: .now, count: 1).first {
                    Text("to arrive \(arrive.formatted(date: .omitted, time: .shortened))").font(Tokens.Font.caption)
                }
            }
            Spacer(minLength: 0)
        }
        .foregroundStyle(Color.brandOn)
        .padding(16)
        .background(Color.brand, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private func row(_ d: PlannedDrive) -> some View {
        HStack(spacing: 12) {
            GimanoIcon(icon: d.destinationIcon, size: 30)
                .frame(width: 56, height: 56)
                .background(Color.brandMist, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            VStack(alignment: .leading, spacing: 4) {
                Text(d.title).font(Tokens.Font.bodyBold)
                if let arrive = d.schedule.arriveBy {
                    Text("Arrive \(arrive)").font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                } else {
                    Text(d.scheduleText()).font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                }
                if d.schedule.isRepeating { DaySquares(days: Set(d.schedule.days ?? [])) }
            }
            Spacer()
            if let lb = model.nextLeaveBy[d.id] ?? d.departAt {
                VStack(alignment: .trailing, spacing: 0) {
                    Text("Leave").font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                    Text(lb.formatted(date: .omitted, time: .shortened)).font(Tokens.Font.label).foregroundStyle(Color.brand)
                }
            }
        }
        .foregroundStyle(Color.textPrimary)
    }
}

/// Seven small day squares (Mon…Sun), active days in cobalt.
struct DaySquares: View {
    let days: Set<String>
    private static let order = ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"]

    var body: some View {
        HStack(spacing: 4) {
            ForEach(Self.order, id: \.self) { d in
                let on = days.contains(d)
                Text(Self.initial(d))
                    .font(Tokens.Font.nunito(11, .black))
                    .frame(width: 22, height: 22)
                    .foregroundStyle(on ? Color.brandOn : Color.textMuted)
                    .background(on ? Color.brand : Color.brandMist, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(days.sorted { Self.order.firstIndex(of: $0)! < Self.order.firstIndex(of: $1)! }.joined(separator: ", ")))
    }

    static func initial(_ code: String) -> String {
        var cal = Calendar(identifier: .gregorian)
        cal.locale = .current
        guard let i = order.firstIndex(of: code) else { return "?" }
        return cal.veryShortWeekdaySymbols[(i + 1) % 7]
    }
}

extension PlannedDrive {
    var destinationIcon: GIcon {
        let n = (destination.name ?? name ?? "").lowercased()
        if n.contains("home") || n.contains("rumah") { return .home }
        if n.contains("work") || n.contains("kerja") || n.contains("office") || n.contains("pejabat") { return .work }
        return .map
    }
}

/// Create / edit a planned drive: destination, arrive-by time, one-off date or repeating weekdays, travel mode.
struct PlannedDriveEditor: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State var drive: PlannedDrive
    @State private var repeatDays: Set<OpeningHours.Day> = []
    @State private var arrival = Date()
    @State private var name = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("e.g. Pergi kerja", text: $name).accessibilityIdentifier("plannedName")
                }
                Section("Destination") {
                    Menu {
                        ForEach(model.savedPlaces) { p in
                            Button(p.name) { setDestination(p.name, p.address, p.location, p.placeId) }
                        }
                        ForEach(model.recents.prefix(8)) { r in
                            Button(r.name) { setDestination(r.name, r.address, r.location, r.placeId) }
                        }
                    } label: {
                        HStack {
                            GimanoIcon(icon: .addStop, size: 26)
                            Text(drive.destination.name ?? String(localized: "Choose a destination")).font(Tokens.Font.bodyBold)
                            Spacer()
                            Image(systemName: "chevron.up.chevron.down").foregroundStyle(Color.textMuted)
                        }
                    }
                    .accessibilityIdentifier("plannedDestination")
                    if !drive.waypoints.isEmpty {
                        Text("Via \(drive.waypoints.map(\.name).joined(separator: ", "))").font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                    }
                }
                Section {
                    HStack(spacing: 6) {
                        ForEach(OpeningHours.Day.allCases) { d in
                            let on = repeatDays.contains(d)
                            Button {
                                if on { repeatDays.remove(d) } else { repeatDays.insert(d) }
                            } label: {
                                Text(d.osm).font(Tokens.Font.label)
                                    .frame(maxWidth: .infinity, minHeight: 40)
                                    .foregroundStyle(on ? Color.brandOn : Color.brandInk)
                                    .background(on ? Color.brand : Color.brandMist, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("repeat.\(d.osm)")
                        }
                    }
                    if repeatDays.isEmpty {
                        DatePicker("Arrive by", selection: $arrival, in: Date()..., displayedComponents: [.date, .hourAndMinute])
                            .accessibilityIdentifier("arriveBy")
                    } else {
                        DatePicker("Arrive by", selection: $arrival, displayedComponents: .hourAndMinute)
                            .accessibilityIdentifier("arriveBy")
                    }
                } header: {
                    Text("When")
                } footer: {
                    Text(repeatDays.isEmpty ? "One-off. Tap days to repeat every week." : "Repeats every week. Gimano reminds you when to leave.")
                }
                Section("Travel mode") {
                    TravelModeTabs(selection: $drive.vehicle).listRowBackground(Color.clear)
                }
                Section {
                    Button("Save") { save() }
                        .bold()
                        .accessibilityIdentifier("savePlannedDrive")
                    if (try? model.store.plannedDrives().contains { $0.id == drive.id }) == true {
                        Button("Delete", role: .destructive) { model.deletePlannedDrive(drive); dismiss() }
                    }
                } footer: {
                    Text("Saved on this phone only. To work out when to leave, the start and end are sent to the server for this calculation only and aren't stored.")
                }
            }
            .navigationTitle("Plan a drive")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } } }
            .onAppear {
                name = drive.name ?? ""
                repeatDays = Set((drive.schedule.days ?? []).compactMap(OpeningHours.Day.init(osm:)))
                if let at = drive.schedule.arriveAt { arrival = at }
                else if let hhmm = drive.schedule.arriveBy, let m = OpeningHours.Range.minutes(hhmm) {
                    arrival = Calendar.current.startOfDay(for: .now).addingTimeInterval(TimeInterval(m * 60))
                }
            }
        }
    }

    private func setDestination(_ name: String, _ address: String, _ location: LatLng, _ placeId: String?) {
        drive.destination = PlannedDrive.Place(lat: location.lat, lng: location.lng, name: name, address: address, placeId: placeId)
        if self.name.isEmpty { self.name = name }
    }

    private func save() {
        drive.name = name.trimmingCharacters(in: .whitespaces).isEmpty ? nil : name
        if repeatDays.isEmpty {
            drive.schedule = PlannedDrive.Schedule(arriveAt: arrival)
        } else {
            let c = Calendar.current.dateComponents([.hour, .minute], from: arrival)
            drive.schedule = PlannedDrive.Schedule(arriveBy: String(format: "%02d:%02d", c.hour ?? 8, c.minute ?? 0),
                                                   days: repeatDays.sorted().map(\.osm))
            drive.departAt = nil
        }
        model.savePlannedDrive(drive)
        dismiss()
    }
}
