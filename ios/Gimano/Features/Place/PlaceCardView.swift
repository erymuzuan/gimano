import SwiftUI

/// Place card: name, badges (Community / Unverified / Just for me), rating, open state, distance, address,
/// Save · Call · Share, photos, hours, amenities, "Is this place here?", reviews, and the Routes CTA.
struct PlaceCardView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    let summary: PlaceSummary
    @State private var place: Place?
    @State private var privatePlace: PrivatePlace?
    @State private var showSuggest = false
    @State private var placeIssue: IssueKind?
    @State private var verifyDone = false
    @State private var verifyResult: VerifyResult?
    @State private var viewer: PhotoViewerItem?
    @State private var confirmDelete = false

    private var isPrivate: Bool { summary.isPrivate }
    private var isCommunity: Bool { (place?.source ?? summary.source) == .community }
    private var status: PlaceStatus? { place?.status ?? summary.status }
    private var canReview: Bool { !isPrivate && !summary.id.hasPrefix("pin:") && !summary.id.hasPrefix("local:") }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button { model.go(.home) } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(Color.textPrimary)
                        .frame(width: 48, height: 48)
                        .background(Color.surface, in: Circle())
                        .fabShadow()
                }
                .accessibilityLabel(Text("Back"))
                Spacer()
                Menu {
                    Button { model.openFeedback(from: .place, placeId: summary.id) } label: {
                        Label("Add feedback", systemImage: "bubble.left.and.text.bubble.right")
                    }
                    if !isPrivate && !summary.id.hasPrefix("pin:") && !summary.id.hasPrefix("local:") {
                        Menu {
                            Button { placeIssue = .placeClosedPerm } label: { Label("Permanently closed", systemImage: "xmark.octagon") }
                            Button { placeIssue = .placeClosedTemp } label: { Label("Temporarily closed", systemImage: "clock.badge.xmark") }
                            Button { placeIssue = .placeHoursWrong } label: { Label("Opening hours wrong", systemImage: "clock") }
                            Button {
                                model.entrancePlace = (summary.id, summary.location)
                                model.startPinPick(.placeEntrance, start: summary.location)
                            } label: { Label("Entrance or parking wrong", systemImage: "door.left.hand.open") }
                            Button { showSuggest = true } label: { Label("Name, type, phone", systemImage: "pencil") }
                            Button { placeIssue = .placeDuplicate } label: { Label("Duplicate", systemImage: "square.on.square") }
                        } label: { Label("Suggest an edit", systemImage: "pencil") }
                            .accessibilityIdentifier("suggestEditMenu")
                    }
                    if isPrivate {
                        Button(role: .destructive) { confirmDelete = true } label: { Label("Delete", systemImage: "trash") }
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(Color.textPrimary)
                        .frame(width: 48, height: 48)
                        .background(Color.surface, in: Circle())
                        .fabShadow()
                }
                .accessibilityLabel(Text("More"))
                .accessibilityIdentifier("placeMore")
            }
            .padding(.horizontal, Tokens.Space.gutter)
            .padding(.top, 8)

            Spacer()

            VStack(spacing: 0) {
                GrabHandle().frame(maxWidth: .infinity).padding(.vertical, 8)
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        header
                        actions
                        badgesAndVerify
                        if !isPrivate && !summary.id.hasPrefix("pin:") && !summary.id.hasPrefix("local:") && !summary.isSearchOnly {
                            PlaceIssueBadges(placeId: summary.id, location: summary.location)
                        }
                        photosStrip
                        infoRows
                        if canReview { ReviewsSection(place: place, summary: summary) }
                        Text(isPrivate ? "Just for me: stored only on this phone" : isCommunity ? "Added by a Gimano user" : "Place data © OpenStreetMap contributors")
                            .font(.system(size: 11))
                            .foregroundStyle(Color.textMuted)
                            .frame(maxWidth: .infinity)
                    }
                    .padding(.horizontal, Tokens.Space.gutter)
                    .padding(.bottom, 12)
                }
                .frame(maxHeight: UIScreen.main.bounds.height * 0.52)
                routesButton
                    .padding(.horizontal, Tokens.Space.gutter)
                    .padding(.vertical, 10)
            }
            .background(
                UnevenRoundedRectangle(topLeadingRadius: Tokens.Radius.sheetTop, topTrailingRadius: Tokens.Radius.sheetTop)
                    .fill(Color.surface)
                    .shadow(color: .black.opacity(0.15), radius: 10, y: -2)
                    .ignoresSafeArea(edges: .bottom)
            )
        }
        .sheet(item: $placeIssue) { k in
            PlaceIssueSheet(kind: k, placeId: summary.id, location: summary.location)
        }
        .sheet(isPresented: $showSuggest) {
            SuggestEditSheet(placeId: summary.id, current: place, fallback: summary)
        }
        .fullScreenCover(item: $viewer) { v in PhotoViewer(urls: v.urls, index: v.index) }
        .confirmationDialog("Delete this place from your phone?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                if let id = privatePlace?.id {
                    try? model.store.deletePrivatePlace(id: id)
                    model.reloadLocal()
                }
                model.go(.home)
            }
        }
        .task(id: summary.id) { await load() }
    }

    private func load() async {
        if isPrivate {
            let id = String(summary.id.dropFirst(PrivatePlace.idPrefix.count))
            privatePlace = try? model.store.privatePlace(id: id)
            return
        }
        guard !summary.id.hasPrefix("pin:"), !summary.id.hasPrefix("local:") else { return }
        place = try? await model.repo.place(id: summary.id, near: model.position)
    }

    // MARK: sections

    private var header: some View {
        HStack(alignment: .top, spacing: 12) {
            CategoryIcon(category: summary.category, size: 52)
            VStack(alignment: .leading, spacing: 4) {
                Text(summary.name).font(Tokens.Font.title).foregroundStyle(Color.textPrimary).accessibilityIdentifier("placeTitle")
                HStack(spacing: 6) {
                    if let open = place?.openNow {
                        Text(open ? "Open now" : "Closed").foregroundStyle(open ? Color.brand : Color.speeding).fontWeight(.semibold)
                        Text("·")
                    }
                    Text(ManeuverFormatter.distance(Geo.distance(model.position, summary.location)))
                    if let r = place?.rating ?? summary.rating, (place?.reviewCount ?? summary.reviewCount ?? 0) >= 3 {
                        Text("·")
                        Label(String(format: "%.1f", r), systemImage: "star.fill").labelStyle(.titleAndIcon)
                            .foregroundStyle(Color(uiColor: Tokens.Palette.traffic2))
                    }
                }
                .font(Tokens.Font.caption)
                .foregroundStyle(Color.textMuted)
                Text(privatePlace?.address ?? summary.address)
                    .font(Tokens.Font.caption)
                    .foregroundStyle(Color.textMuted)
                    .lineLimit(2)
            }
        }
    }

    private var actions: some View {
        HStack(spacing: 0) {
            if !isPrivate {
                action("Save", icon: "bookmark") { model.savingPlace = summary }
                    .accessibilityIdentifier("savePlace")
            }
            let phone = place?.phone ?? privatePlace?.phone
            action("Call", icon: "phone") {
                if let p = phone?.filter({ $0.isNumber || $0 == "+" }), let url = URL(string: "tel:\(p)") { openURL(url) }
            }
            .disabled(phone == nil)
            ShareLink(item: shareText) { actionLabel("Share", icon: "square.and.arrow.up") }
                .buttonStyle(.plain)
            if let site = place?.website ?? privatePlace?.website, let url = URL(string: site.hasPrefix("http") ? site : "https://\(site)") {
                action("Website", icon: "safari") { openURL(url) }
            }
        }
    }

    @ViewBuilder private var badgesAndVerify: some View {
        if isPrivate || isCommunity {
            HStack(spacing: 8) {
                if isPrivate { badge("Just for me", icon: .heart, id: "badgePrivate") }
                if isCommunity { badge("Community", icon: .map, id: "badgeCommunity") }
                if isCommunity && status == .pending {
                    badge("Unverified", icon: .hazard, id: "badgeUnverified")
                    VettingBadge(confirmed: verifyResult.map { Int($0.confirmationsWeighted) } ?? place?.confirmationsWeighted.map { Int($0) } ?? place?.verifiedCount ?? 0,
                                 required: verifyResult.map { Int($0.required) } ?? place?.required.map { Int($0) } ?? 3)
                }
                if let n = place?.verifiedCount, n > 0 {
                    Text("Confirmed by \(n)").font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                }
            }
        }
        if isCommunity && status == .pending && model.ownPlaceIds.contains(summary.id) {
            Label("Waiting for other drivers to confirm (\(place?.verifiedCount ?? 0)/3)", systemImage: "hourglass")
                .font(Tokens.Font.caption)
                .foregroundStyle(Color.textMuted)
                .accessibilityIdentifier("awaitingVerification")
        } else if isCommunity && status == .pending {
            VStack(alignment: .leading, spacing: 10) {
                Text("Is this place here?").font(Tokens.Font.bodyBold)
                if verifyDone {
                    Label("Thanks for checking!", systemImage: "checkmark.seal.fill").foregroundStyle(Color.brand)
                        .accessibilityIdentifier("verifyThanks")
                } else if Proximity.claim(me: model.position, place: summary.location, rural: place?.tags?["rural"] == "yes") == nil {
                    Label("Get within 1 km of the place to confirm it.", systemImage: "location.circle")
                        .font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                        .accessibilityIdentifier("verifyTooFar")
                } else {
                    HStack(spacing: 8) {
                        Button { verify(.yes, location: true) } label: { Label("Yes", systemImage: "hand.thumbsup.fill") }
                            .buttonStyle(PrimaryButtonStyle())
                            .accessibilityIdentifier("verifyYes")
                        Button { verify(.no, location: nil) } label: { Label("No", systemImage: "hand.thumbsdown.fill") }
                            .buttonStyle(SecondaryButtonStyle())
                            .accessibilityIdentifier("verifyNo")
                    }
                    HStack {
                        Button("It's here, but the pin is in the wrong spot") { verify(.yes, location: false) }
                        Spacer()
                        Button("Not sure") { verify(.unsure, location: nil) }
                    }
                    .font(Tokens.Font.caption)
                }
            }
            .padding(12)
            .background(Color.brandMist, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
        }
    }

    private func verify(_ answer: VoteAnswer, location: Bool?) {
        Task {
            do {
                let target = place ?? Place(summary: summary)
                verifyResult = try await model.vote(on: target, answer: answer, nameCorrect: answer == .yes ? true : nil, locationCorrect: location)
                verifyDone = true
            } catch {
                model.showToast(error.localizedDescription)
            }
        }
    }

    @ViewBuilder private var photosStrip: some View {
        let urls: [URL] = privatePlace.map { p in p.photoFiles.map { model.store.photoURL($0) } }
            ?? (place?.photos ?? []).compactMap { MediaURL.resolve($0.url) }
        let thumbs: [URL] = privatePlace.map { _ in urls }
            ?? (place?.photos ?? []).filter { $0.url != nil }.compactMap { MediaURL.resolve($0.thumbUrl ?? $0.url) }
        if !urls.isEmpty {
            PhotoStrip(thumbs: thumbs) { i in viewer = PhotoViewerItem(urls: urls, index: i) }
        }
    }

    @ViewBuilder private var infoRows: some View {
        if let hours = place?.openingHours ?? privatePlace?.openingHours {
            Label(hours, systemImage: "clock").font(Tokens.Font.caption).foregroundStyle(Color.textPrimary)
        }
        if let d = place?.description ?? privatePlace?.description {
            Text(d).font(Tokens.Font.body).foregroundStyle(Color.textPrimary)
        }
        if let amenities = place?.amenities ?? privatePlace?.amenities, !amenities.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(amenities, id: \.self) { a in
                        AddPlaceFlow.amenityLabel(a)
                            .font(.system(size: 12, weight: .medium))
                            .padding(.horizontal, 10).padding(.vertical, 5)
                            .background(Color.surfaceAlt, in: Capsule())
                    }
                }
            }
        }
    }

    private var routesButton: some View {
        Button {
            model.planRoute(to: Destination(name: summary.name, address: summary.address, location: summary.location,
                                            placeId: summary.id.hasPrefix("pin:") || summary.id.hasPrefix("local:") || isPrivate ? nil : summary.id,
                                            category: summary.category))
        } label: {
            Label("Routes", systemImage: "arrow.triangle.turn.up.right.diamond.fill")
        }
        .buttonStyle(PrimaryButtonStyle())
        .accessibilityIdentifier("routesButton")
    }

    private func badge(_ title: LocalizedStringKey, icon: GIcon, id: String) -> some View {
        HStack(spacing: 4) {
            GimanoIcon(icon: icon, size: 16)
            Text(title).font(Tokens.Font.small)
        }
        .foregroundStyle(Color.brandInk)
        .padding(.horizontal, 10).padding(.vertical, 5)
        .background(Color.brandMist, in: Capsule())
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier(id)
    }

    private var shareText: String {
        "\(summary.name)\n\(summary.address)\nhttps://www.openstreetmap.org/?mlat=\(summary.lat)&mlon=\(summary.lng)#map=17/\(summary.lat)/\(summary.lng)"
    }

    private func action(_ title: LocalizedStringKey, icon: String, perform: @escaping () -> Void) -> some View {
        Button(action: perform) { actionLabel(title, icon: icon) }
            .buttonStyle(.plain)
    }

    private func actionLabel(_ title: LocalizedStringKey, icon: String) -> some View {
        VStack(spacing: 6) {
            Image(systemName: icon)
                .font(.system(size: 18, weight: .semibold))
                .frame(width: 44, height: 44)
                .background(Color.surfaceAlt, in: Circle())
            Text(title).font(Tokens.Font.nunito(12, .bold))
        }
        .foregroundStyle(Color.brand)
        .frame(maxWidth: .infinity)
    }
}

/// "Suggest an edit" (any place, including OSM): hours, phone, website, category, name, comment.
struct SuggestEditSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let placeId: String
    let current: Place?
    let fallback: PlaceSummary
    @State private var name = ""
    @State private var category: PlaceCategory = .other
    @State private var phone = ""
    @State private var website = ""
    @State private var hours = OpeningHours.uniform([OpeningHours.Range(open: 8 * 60, close: 22 * 60)])
    @State private var editHours = false
    @State private var comment = ""
    @State private var sending = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") { TextField("Name", text: $name).accessibilityIdentifier("suggestName") }
                Section("Category") {
                    Picker("Category", selection: $category) {
                        ForEach(AddPlaceCategories.all, id: \.0) { c, t in Text(t).tag(c) }
                    }
                }
                Section("Contact") {
                    TextField("Phone", text: $phone).keyboardType(.phonePad)
                    TextField("Website", text: $website).keyboardType(.URL).textInputAutocapitalization(.never)
                }
                Section {
                    Toggle("Change opening hours", isOn: $editHours)
                    if editHours { OpeningHoursEditor(hours: $hours) }
                }
                Section("Anything else?") {
                    TextField("e.g. moved to the next shoplot", text: $comment, axis: .vertical).lineLimit(2...4)
                        .accessibilityIdentifier("suggestComment")
                }
                Section {
                    Button("Send suggestion") { Task { await send() } }
                        .disabled(sending)
                        .accessibilityIdentifier("sendSuggestion")
                    if let error { Text(error).foregroundStyle(Color.speeding).font(Tokens.Font.caption) }
                }
            }
            .navigationTitle("Suggest an edit")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } } }
            .onAppear {
                name = current?.name ?? fallback.name
                category = current?.category ?? fallback.category
                phone = current?.phone ?? ""
                website = current?.website ?? ""
                if let h = current?.openingHours, let parsed = OpeningHours.parse(h) { hours = parsed }
            }
        }
    }

    private func send() async {
        sending = true
        defer { sending = false }
        func changed(_ new: String, _ old: String?) -> String? {
            let t = new.trimmingCharacters(in: .whitespaces)
            return t.isEmpty || t == (old ?? "") ? nil : t
        }
        let s = PlaceEditSuggestion(
            name: changed(name, current?.name ?? fallback.name),
            category: category == (current?.category ?? fallback.category) ? nil : category,
            phone: changed(phone, current?.phone), website: changed(website, current?.website),
            openingHours: editHours ? hours.osm : nil,
            comment: comment.trimmingCharacters(in: .whitespaces).isEmpty ? nil : comment)
        do {
            _ = try await model.repo.suggestEdit(placeId: placeId, s)
            model.showToast(String(localized: "Thanks! Your suggestion was sent."))
            dismiss()
        } catch let e as APIError where e.isServerSideGap {
            error = String(localized: "Edit suggestions aren't available on the server yet.")
        } catch {
            self.error = error.localizedDescription
        }
    }
}
