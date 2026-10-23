import PhotosUI
import SwiftUI

/// Categories offered when adding a place (big icon grid).
@MainActor
enum AddPlaceCategories {
    static let all: [(PlaceCategory, LocalizedStringKey)] = [
        (.restaurant, "Food"), (.cafe, "Cafe"), (.fuel, "Fuel"), (.surau, "Surau"), (.mosque, "Mosque"), (.parking, "Parking"),
        (.evCharging, "EV"), (.convenience, "Shop"), (.carRepair, "Workshop"), (.clinic, "Clinic"), (.school, "School"), (.other, "Other"),
    ]
}

extension PlaceCategory {
    /// Duotone icon (shared set), with sensible stand-ins for categories without their own glyph.
    var tileIcon: GIcon {
        if let g = gIcon { return g }
        switch self {
        case .convenience, .supermarket, .mall: return .shop
        case .clinic, .hospital, .pharmacy: return .hospital
        case .school, .university: return .school
        case .carRepair, .carWash: return .car
        default: return .map
        }
    }
}

/// One photo picked for a new place or review (kept as upload-ready JPEG + a preview).
struct PickedPhoto: Identifiable, Equatable {
    let id = UUID()
    let jpeg: Data
    let preview: UIImage
}

enum PhotoPicking {
    static let maxPhotos = 5

    /// Loads picker items as ≤ 2048 px JPEG (HEIC/PNG converted, metadata stripped).
    static func load(_ items: [PhotosPickerItem]) async -> [PickedPhoto] {
        var out: [PickedPhoto] = []
        for item in items {
            guard let data = try? await item.loadTransferable(type: Data.self),
                  let jpeg = ImageProcessing.uploadJPEG(data), let img = UIImage(data: jpeg) else { continue }
            out.append(PickedPhoto(jpeg: jpeg, preview: img))
        }
        return out
    }

    static func picked(_ image: UIImage) -> PickedPhoto? {
        guard let jpeg = ImageProcessing.uploadJPEG(image), let img = UIImage(data: jpeg) else { return nil }
        return PickedPhoto(jpeg: jpeg, preview: img)
    }

    #if DEBUG
    /// Generated photo for UI tests (`-GimanoTestPhotos YES`), so tests don't depend on the photo library.
    static func testPhoto() -> PickedPhoto? {
        let img = UIGraphicsImageRenderer(size: CGSize(width: 1200, height: 900)).image { ctx in
            UIColor(hex: 0x0047AB).setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 1200, height: 900))
            UIColor(hex: 0xFF6B4A).setFill(); ctx.fill(CGRect(x: 300, y: 250, width: 600, height: 400))
            ("Gimano test photo" as NSString).draw(at: CGPoint(x: 340, y: 420), withAttributes: [.font: UIFont.boldSystemFont(ofSize: 60), .foregroundColor: UIColor.white])
        }
        return picked(img)
    }
    #endif
}

/// Photo row: thumbnails, remove, PhotosPicker and camera.
struct PhotoPickerRow: View {
    @Binding var photos: [PickedPhoto]
    @State private var items: [PhotosPickerItem] = []
    @State private var showCamera = false

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(photos) { p in
                    Image(uiImage: p.preview)
                        .resizable().scaledToFill()
                        .frame(width: 76, height: 76)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .overlay(alignment: .topTrailing) {
                            Button { photos.removeAll { $0.id == p.id } } label: {
                                Image(systemName: "xmark.circle.fill").font(.system(size: 22)).foregroundStyle(.white, .black.opacity(0.6))
                            }
                            .padding(4)
                            .accessibilityLabel(Text("Remove photo"))
                        }
                        .accessibilityIdentifier("pickedPhoto")
                }
                if photos.count < PhotoPicking.maxPhotos {
                    let photosTile = tile(system: "photo.on.rectangle.angled", title: "Photos")
                    PhotosPicker(selection: $items, maxSelectionCount: PhotoPicking.maxPhotos - photos.count, matching: .images) {
                        photosTile
                    }
                    .accessibilityIdentifier("pickPhotos")
                    if UIImagePickerController.isSourceTypeAvailable(.camera) {
                        Button { showCamera = true } label: { tile(system: "camera.fill", title: "Camera") }
                            .accessibilityIdentifier("takePhoto")
                    }
                    #if DEBUG
                    if UserDefaults.standard.bool(forKey: "GimanoTestPhotos") {
                        Button { if let p = PhotoPicking.testPhoto() { photos.append(p) } } label: { tile(system: "testtube.2", title: "Test") }
                            .accessibilityIdentifier("addTestPhoto")
                    }
                    #endif
                }
            }
        }
        .onChange(of: items) {
            let picked = items
            items = []
            Task { photos += await PhotoPicking.load(picked).prefix(PhotoPicking.maxPhotos - photos.count) }
        }
        .fullScreenCover(isPresented: $showCamera) {
            CameraPicker { image in
                if let image, let p = PhotoPicking.picked(image), photos.count < PhotoPicking.maxPhotos { photos.append(p) }
                showCamera = false
            }
            .ignoresSafeArea()
        }
    }

    private func tile(system: String, title: LocalizedStringKey) -> some View {
        VStack(spacing: 4) {
            Image(systemName: system).font(.system(size: 24, weight: .semibold))
            Text(title).font(Tokens.Font.small)
        }
        .foregroundStyle(Color.brandInk)
        .frame(width: 76, height: 76)
        .background(Color.brandMist, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

/// UIImagePickerController camera wrapper.
struct CameraPicker: UIViewControllerRepresentable {
    let onDone: (UIImage?) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let c = UIImagePickerController()
        c.sourceType = .camera
        c.delegate = context.coordinator
        return c
    }

    func updateUIViewController(_ c: UIImagePickerController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onDone) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onDone: (UIImage?) -> Void
        init(_ onDone: @escaping (UIImage?) -> Void) { self.onDone = onDone }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            onDone(info[.originalImage] as? UIImage)
        }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { onDone(nil) }
    }
}

/// "Can't find it? Add a place": pin → details → visibility → save.
struct AddPlaceFlow: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let start: LatLng

    enum Step { case pin, details, visibility }
    @State private var step: Step = .pin
    @State private var pinMap: MapState?
    @State private var center: LatLng
    @State private var address = ""
    @State private var name = ""
    @State private var category: PlaceCategory = .restaurant
    @State private var phone = ""
    @State private var website = ""
    @State private var details = ""
    @State private var amenities: Set<String> = []
    @State private var hours = OpeningHours.uniform([OpeningHours.Range(open: 8 * 60, close: 22 * 60)])
    @State private var hoursEnabled = false
    @State private var photos: [PickedPhoto] = []
    @State private var saving = false
    @State private var uploadProgress: Double?
    @State private var duplicate: PlaceSummary?
    @State private var error: String?

    init(start: LatLng) {
        self.start = start
        _center = State(initialValue: start)
    }

    static let amenityOptions: [(String, LocalizedStringKey)] = [
        ("toilets", "Toilets"), ("surau", "Surau"), ("parking", "Parking"), ("wheelchair", "Wheelchair"),
        ("wifi", "Wi-Fi"), ("ev_charging", "EV charging"), ("cards", "Cards"), ("touch_n_go", "Touch 'n Go"),
    ]

    var body: some View {
        NavigationStack {
            Group {
                switch step {
                case .pin: pinStep
                case .details: detailsStep
                case .visibility: visibilityStep
                }
            }
            .navigationTitle("Add a place")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(step == .pin ? "Cancel" : "Back") {
                        switch step {
                        case .pin: dismiss()
                        case .details: step = .pin
                        case .visibility: step = .details
                        }
                    }
                    .accessibilityIdentifier("addPlaceBack")
                }
            }
        }
        .alert("Is it this one?", isPresented: Binding(get: { duplicate != nil }, set: { if !$0 { duplicate = nil } })) {
            Button("Yes, open it") {
                if let d = duplicate { dismiss(); model.openPlace(d) }
            }
            Button("No, go back", role: .cancel) { step = .details }
        } message: {
            Text("A place called \(duplicate?.name ?? "") is already here.")
        }
    }

    // MARK: 1. pin

    private var pinStep: some View {
        ZStack {
            if let pinMap {
                GimanoMapView(state: pinMap).ignoresSafeArea(edges: .bottom)
            }
            // Fixed centre pin: move the map under it.
            VStack(spacing: 0) {
                GimanoIcon(icon: .addStop, size: 54, stroke: .brand, fill: .brandTint)
                    .shadow(color: .black.opacity(0.2), radius: 4, y: 2)
                Color.clear.frame(height: 54)
            }
            .allowsHitTesting(false)
            .accessibilityIdentifier("centerPin")
            VStack {
                Spacer()
                VStack(alignment: .leading, spacing: 10) {
                    Text("Move the map to put the pin on the place").font(Tokens.Font.label).foregroundStyle(Color.textMuted)
                    Label(address.isEmpty ? String(localized: "Finding the address…") : address, systemImage: "mappin.and.ellipse")
                        .font(Tokens.Font.bodyBold)
                        .lineLimit(2)
                        .accessibilityIdentifier("pinAddress")
                    Button("Next") { step = .details }
                        .buttonStyle(PrimaryButtonStyle())
                        .accessibilityIdentifier("pinNext")
                }
                .padding(Tokens.Space.gutter)
                .background(Color.surface, in: RoundedRectangle(cornerRadius: Tokens.Radius.lg, style: .continuous))
                .padding(Tokens.Space.gutter)
            }
        }
        .onAppear {
            if pinMap == nil {
                let m = MapState(styleURL: model.config.styleURL)
                m.puck = model.position
                m.camera = .center(start, zoom: 17)
                m.cameraRevision = 1
                pinMap = m
            }
        }
        .task(id: pinMap?.visibleBBox) {
            guard let b = pinMap?.visibleBBox else { return }
            center = LatLng(lat: (b.minLat + b.maxLat) / 2, lng: (b.minLng + b.maxLng) / 2)
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled else { return }
            if let rg = try? await model.repo.reverseGeocode(center) { address = rg.address }
        }
    }

    // MARK: 2. details

    private var detailsStep: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                TextField(String(localized: "Name (required)"), text: $name)
                    .font(Tokens.Font.nunito(20, .bold))
                    .padding(.horizontal, 16)
                    .frame(height: 60)
                    .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: Tokens.Radius.searchField, style: .continuous))
                    .accessibilityIdentifier("placeName")

                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 4), spacing: 10) {
                    ForEach(AddPlaceCategories.all, id: \.0) { cat, title in
                        Button { category = cat } label: {
                            VStack(spacing: 4) {
                                GimanoIcon(icon: cat.tileIcon, size: 34, stroke: category == cat ? .brandOn : .brandInk, fill: category == cat ? .brandOn : .brandTint)
                                Text(title).font(Tokens.Font.small).lineLimit(1)
                            }
                            .foregroundStyle(category == cat ? Color.brandOn : Color.brandInk)
                            .frame(maxWidth: .infinity, minHeight: 72)
                            .background(category == cat ? Color.brand : Color.brandMist, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("category.\(cat.rawValue)")
                    }
                }

                PhotoPickerRow(photos: $photos)

                Toggle("Opening hours", isOn: $hoursEnabled.animation()).font(Tokens.Font.bodyBold).accessibilityIdentifier("hoursToggle")
                if hoursEnabled { OpeningHoursEditor(hours: $hours) }

                Group {
                    TextField("Phone (optional)", text: $phone).keyboardType(.phonePad)
                    TextField("Website (optional)", text: $website).keyboardType(.URL).textInputAutocapitalization(.never)
                    TextField("Description (optional)", text: $details, axis: .vertical).lineLimit(2...5)
                }
                .padding(14)
                .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))

                Text("Amenities").font(Tokens.Font.label).foregroundStyle(Color.textMuted)
                FlowChips(options: Self.amenityOptions, selection: $amenities)

                Button("Next") { step = .visibility }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                    .accessibilityIdentifier("detailsNext")
            }
            .padding(Tokens.Space.gutter)
        }
        .scrollDismissesKeyboard(.interactively)
    }

    // MARK: 3. visibility

    private var visibilityStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Make this place discoverable by other Gimano users?").font(Tokens.Font.title)
            visibilityCard(icon: .heart, title: "Just for me", subtitle: "Saved only on this phone (and in your own backups). Never sent to Gimano.",
                           id: "visibilityPrivate") { Task { await savePrivate() } }
            visibilityCard(icon: .map, title: "Share with everyone", subtitle: "Other drivers will help verify it.",
                           id: "visibilityPublic") { Task { await savePublic() } }
            if saving {
                if let uploadProgress {
                    ProgressView(value: uploadProgress) { Text("Uploading photos…").font(Tokens.Font.caption) }
                        .accessibilityIdentifier("uploadProgress")
                } else {
                    ProgressView()
                }
            }
            if let error { Text(error).foregroundStyle(Color.speeding).font(Tokens.Font.caption).accessibilityIdentifier("addPlaceError") }
            Spacer()
        }
        .padding(Tokens.Space.gutter)
        .disabled(saving)
    }

    private func visibilityCard(icon: GIcon, title: LocalizedStringKey, subtitle: LocalizedStringKey, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                GimanoIcon(icon: icon, size: 40)
                    .frame(width: 64, height: 64)
                    .background(Color.brandMist, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(Tokens.Font.nunito(19, .extrabold))
                    Text(subtitle).font(Tokens.Font.caption).foregroundStyle(Color.textMuted).multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
            }
            .foregroundStyle(Color.textPrimary)
            .padding(14)
            .background(Color.surface, in: RoundedRectangle(cornerRadius: Tokens.Radius.lg, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Tokens.Radius.lg, style: .continuous).stroke(Color.outline, lineWidth: 1.5))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(id)
    }

    // MARK: save

    private var trimmedName: String { name.trimmingCharacters(in: .whitespaces) }
    private func opt(_ s: String) -> String? { s.trimmingCharacters(in: .whitespaces).isEmpty ? nil : s.trimmingCharacters(in: .whitespaces) }

    private func savePrivate() async {
        saving = true
        defer { saving = false }
        let webps = photos.compactMap { ImageProcessing.webp($0.jpeg) }
        let p = PrivatePlace(id: "", name: trimmedName, category: category, lat: center.lat, lng: center.lng, address: opt(address),
                             description: opt(details), phone: opt(phone), website: opt(website),
                             openingHours: hoursEnabled ? opt(hours.osm) : nil, amenities: amenities.sorted())
        do {
            let saved = try model.store.addPrivatePlace(p, webpPhotos: webps)
            model.reloadLocal()
            dismiss()
            model.showToast(String(localized: "Saved on this phone only"))
            model.openPlace(saved.summary)
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func savePublic() async {
        saving = true
        defer { saving = false }
        let new = NewPlace(name: trimmedName, category: category, lat: center.lat, lng: center.lng, address: opt(address),
                           description: opt(details), phone: opt(phone), website: opt(website),
                           openingHours: hoursEnabled ? opt(hours.osm) : nil, amenities: amenities.sorted())
        do {
            let place = try await model.repo.createPlace(new)
            model.rememberOwnPlace(place.id)
            if !photos.isEmpty {
                uploadProgress = 0
                for (i, p) in photos.enumerated() {
                    let base = Double(i) / Double(photos.count), share = 1 / Double(photos.count)
                    _ = try await model.repo.uploadPhoto(p.jpeg, placeId: place.id, caption: nil) { frac in
                        Task { @MainActor in uploadProgress = base + frac * share }
                    }
                }
            }
            dismiss()
            model.showToast(String(localized: "Thanks! Other drivers will help verify it."))
            model.openPlace(place.summary)
        } catch APIError.conflict(let body) {
            duplicate = DuplicatePlace.existing(in: body)
            if duplicate == nil { error = String(localized: "This place may already exist.") }
        } catch let e as APIError where e.isServerSideGap {
            error = String(localized: "Sharing places isn't available on the server yet. Save it just for you for now.")
        } catch {
            self.error = error.localizedDescription
        }
    }
}

/// Wrapping multi-select chips.
struct FlowChips: View {
    let options: [(String, LocalizedStringKey)]
    @Binding var selection: Set<String>

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 8)], alignment: .leading, spacing: 8) {
            ForEach(options, id: \.0) { key, title in
                let on = selection.contains(key)
                Button {
                    if on { selection.remove(key) } else { selection.insert(key) }
                } label: {
                    Text(title)
                        .font(Tokens.Font.label)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .foregroundStyle(on ? Color.brandOn : Color.brandInk)
                        .background(on ? Color.brand : Color.brandMist, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("amenity.\(key)")
            }
        }
    }
}

/// Day chips + 24 h time ranges + Closed → OSM opening_hours.
struct OpeningHoursEditor: View {
    @Binding var hours: OpeningHours
    @State private var selected: Set<OpeningHours.Day> = [.mo, .tu, .we, .th, .fr]

    private var firstSelected: OpeningHours.Day { selected.min() ?? .mo }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 6) {
                ForEach(OpeningHours.Day.allCases) { d in
                    let on = selected.contains(d)
                    Button {
                        if on { if selected.count > 1 { selected.remove(d) } } else { selected.insert(d) }
                    } label: {
                        Text(d.osm)
                            .font(Tokens.Font.label)
                            .frame(maxWidth: .infinity, minHeight: 40)
                            .foregroundStyle(on ? Color.brandOn : Color.brandInk)
                            .background(on ? Color.brand : Color.brandMist, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("day.\(d.osm)")
                }
            }
            HStack(spacing: 8) {
                quick("24 h") { apply([OpeningHours.Range(open: 0, close: 1440)]) }
                quick("Closed") { apply([]) }
                quick("+ Time") { apply(hours.ranges(firstSelected) + [OpeningHours.Range(open: 14 * 60, close: 18 * 60)]) }
            }
            let ranges = hours.ranges(firstSelected)
            if ranges.isEmpty {
                Text("Closed").font(Tokens.Font.bodyBold).foregroundStyle(Color.textMuted)
            }
            ForEach(Array(ranges.enumerated()), id: \.offset) { i, r in
                HStack {
                    timePicker(r.open) { v in update(i) { $0.open = v } }
                    Text("–")
                    timePicker(min(r.close, 1439)) { v in update(i) { $0.close = v } }
                    Spacer()
                    Button { apply(ranges.enumerated().filter { $0.offset != i }.map(\.element)) } label: {
                        Image(systemName: "minus.circle.fill").font(.system(size: 22)).foregroundStyle(Color.speeding)
                    }
                    .accessibilityLabel(Text("Remove time"))
                }
            }
            Text(verbatim: hours.osm.isEmpty ? "—" : hours.osm)
                .font(.system(.footnote, design: .monospaced))
                .foregroundStyle(Color.textMuted)
                .accessibilityIdentifier("hoursPreview")
        }
        .padding(12)
        .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
    }

    private func quick(_ title: LocalizedStringKey, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(Tokens.Font.label).padding(.horizontal, 12).padding(.vertical, 8)
                .background(Color.surface, in: Capsule())
        }
        .buttonStyle(.plain)
    }

    private func apply(_ ranges: [OpeningHours.Range]) {
        for d in selected { hours.days[d] = ranges }
    }

    private func update(_ i: Int, _ change: (inout OpeningHours.Range) -> Void) {
        var ranges = hours.ranges(firstSelected)
        guard ranges.indices.contains(i) else { return }
        change(&ranges[i])
        apply(ranges)
    }

    /// 24-hour wheel picker bound to minutes since midnight.
    private func timePicker(_ minutes: Int, set: @escaping (Int) -> Void) -> some View {
        let cal = Calendar(identifier: .gregorian)
        let base = cal.startOfDay(for: Date(timeIntervalSince1970: 0))
        return DatePicker("", selection: Binding(
            get: { base.addingTimeInterval(TimeInterval(minutes * 60)) },
            set: { d in
                let c = cal.dateComponents([.hour, .minute], from: d)
                set((c.hour ?? 0) * 60 + (c.minute ?? 0))
            }), displayedComponents: .hourAndMinute)
            .labelsHidden()
            .environment(\.locale, Locale(identifier: "en_GB")) // 24 h
    }
}

/// 409 from `POST /places`: a problem+json with the similar place under `existing` ("Is it this one?").
enum DuplicatePlace {
    private struct Problem: Decodable { var existing: PlaceSummary? }

    static func existing(in body: Data) -> PlaceSummary? {
        let d = JSON.makeDecoder()
        if let p = try? d.decode(Problem.self, from: body), let e = p.existing { return e }
        return (try? d.decode(Place.self, from: body))?.summary
    }
}
