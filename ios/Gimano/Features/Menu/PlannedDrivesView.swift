import SwiftUI

/// Menu → Planned drives: list, edit, delete; "Leave by" from departure times.
struct PlannedDrivesView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        List {
            if model.plannedDrives.isEmpty {
                ContentUnavailableView("No planned drives", systemImage: "calendar",
                                       description: Text("Plan a drive here or from the route screen with “Leave later”."))
            }
            ForEach(model.plannedDrives) { d in
                Button { model.editingDrive = d } label: {
                    HStack(spacing: 12) {
                        GimanoIcon(icon: d.schedule.isRepeating ? .recent : .logo, size: 28)
                            .frame(width: 48, height: 48)
                            .background(Color.brandMist, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(d.title).font(Tokens.Font.bodyBold)
                            Text(d.scheduleText()).font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                            if let lb = model.nextLeaveBy[d.id] ?? d.departAt {
                                Text("Leave by \(lb.formatted(date: lb.timeIntervalSinceNow > 86_000 ? .abbreviated : .omitted, time: .shortened))")
                                    .font(Tokens.Font.label).foregroundStyle(Color.brand)
                                    .accessibilityIdentifier("leaveBy")
                            }
                        }
                        Spacer()
                        GimanoIcon(icon: d.vehicle.gIcon, size: 22)
                    }
                    .foregroundStyle(Color.textPrimary)
                }
                .accessibilityIdentifier("plannedDrive.\(d.title)")
                .swipeActions {
                    Button("Delete", role: .destructive) { model.deletePlannedDrive(d) }
                    Button("Go") { model.openPlannedDrive(id: d.id) }.tint(.brand)
                }
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
