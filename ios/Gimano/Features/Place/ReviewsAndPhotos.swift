import SwiftUI

// MARK: - Photos

struct PhotoViewerItem: Identifiable {
    let id = UUID()
    let urls: [URL]
    let index: Int
}

/// Image from a remote or file URL (WebP decodes natively on iOS 14+).
struct RemoteImage: View {
    let url: URL
    var contentMode: ContentMode = .fill

    var body: some View {
        AsyncImage(url: url) { phase in
            switch phase {
            case .success(let img): img.resizable().aspectRatio(contentMode: contentMode)
            case .failure: Image(systemName: "photo").font(.system(size: 24)).foregroundStyle(Color.textMuted)
            default: ProgressView()
            }
        }
    }
}

/// Horizontal gallery strip.
struct PhotoStrip: View {
    let thumbs: [URL]
    let onTap: (Int) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Array(thumbs.enumerated()), id: \.offset) { i, u in
                    Button { onTap(i) } label: {
                        RemoteImage(url: u)
                            .frame(width: 112, height: 84)
                            .background(Color.surfaceAlt)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("placePhoto")
                    .accessibilityValue(Text(verbatim: u.absoluteString))
                }
            }
        }
    }
}

/// Full-screen swipeable photo viewer.
struct PhotoViewer: View {
    @Environment(\.dismiss) private var dismiss
    let urls: [URL]
    @State var index: Int

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            TabView(selection: $index) {
                ForEach(Array(urls.enumerated()), id: \.offset) { i, u in
                    RemoteImage(url: u, contentMode: .fit).tag(i)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: urls.count > 1 ? .always : .never))
            .accessibilityIdentifier("photoViewer")
            Button { dismiss() } label: {
                GimanoIcon(icon: .close, size: 22, stroke: .white, fill: .clear)
                    .frame(width: 48, height: 48)
                    .background(.ultraThinMaterial, in: Circle())
            }
            .padding()
            .accessibilityLabel(Text("Close"))
            .accessibilityIdentifier("closeViewer")
        }
    }
}

// MARK: - Stars

struct StarsView: View {
    let rating: Double
    var size: CGFloat = 14

    var body: some View {
        HStack(spacing: 1) {
            ForEach(1...5, id: \.self) { i in
                Image(systemName: Double(i) <= rating + 0.25 ? "star.fill" : (Double(i) - 0.5 <= rating ? "star.leadinghalf.filled" : "star"))
                    .font(.system(size: size))
                    .foregroundStyle(Color(uiColor: Tokens.Palette.traffic2))
            }
        }
        .accessibilityElement()
        .accessibilityLabel(Text("\(Int(rating.rounded())) stars"))
    }
}

// MARK: - Reviews

/// Rating summary with distribution, top reviews, "Write a review", "All reviews".
struct ReviewsSection: View {
    @Environment(AppModel.self) private var model
    let place: Place?
    let summary: PlaceSummary
    @State private var reviews: [Review] = []
    @State private var loaded = false
    @State private var writing = false
    @State private var showAll = false

    private var stats: RatingSummary {
        RatingSummary.make(reviews: reviews, serverAverage: place?.rating ?? summary.rating, serverCount: place?.reviewCount ?? summary.reviewCount)
    }

    private var myReviewId: String? { try? model.store.setting("review.\(summary.id)") }
    private var myReview: Review? { reviews.first { $0.id == myReviewId } }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Reviews").font(Tokens.Font.title)
            HStack(alignment: .center, spacing: 16) {
                VStack(spacing: 2) {
                    if stats.isNew {
                        Text("New").font(Tokens.Font.nunito(30, .black)).foregroundStyle(Color.brand).accessibilityIdentifier("ratingNew")
                    } else {
                        Text(String(format: "%.1f", stats.average)).font(Tokens.Font.nunito(36, .black)).accessibilityIdentifier("ratingAverage")
                    }
                    StarsView(rating: stats.average)
                    Text(stats.count == 1 ? String(localized: "1 review") : String(localized: "\(stats.count) reviews")).font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                }
                .frame(width: 96)
                VStack(spacing: 3) {
                    let maxCount = max(1, stats.distribution.max() ?? 1)
                    ForEach((1...5).reversed(), id: \.self) { star in
                        HStack(spacing: 6) {
                            Text("\(star)").font(Tokens.Font.small).frame(width: 12)
                            GeometryReader { g in
                                ZStack(alignment: .leading) {
                                    Capsule().fill(Color.surfaceAlt)
                                    Capsule().fill(Color(uiColor: Tokens.Palette.traffic2))
                                        .frame(width: g.size.width * CGFloat(stats.distribution[star - 1]) / CGFloat(maxCount))
                                }
                            }
                            .frame(height: 8)
                        }
                    }
                }
                .accessibilityIdentifier("ratingDistribution")
            }

            Button { writing = true } label: {
                Label(myReview == nil ? "Write a review" : "Edit your review", systemImage: "star.bubble")
            }
            .buttonStyle(SecondaryButtonStyle())
            .accessibilityIdentifier("writeReview")

            ForEach(reviews.prefix(3)) { r in
                ReviewRow(review: r) { await reload() }
            }
            if reviews.count > 3 {
                Button("All reviews (\(reviews.count))") { showAll = true }.font(Tokens.Font.bodyBold)
            }
            if loaded && reviews.isEmpty {
                Text("No reviews yet. Be the first!").font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
            }
        }
        .task(id: summary.id) { await reload() }
        .sheet(isPresented: $writing) {
            WriteReviewSheet(placeId: summary.id, placeName: summary.name, existing: myReview) { await reload() }
        }
        .sheet(isPresented: $showAll) { AllReviewsSheet(placeId: summary.id) }
    }

    private func reload() async {
        reviews = (try? await model.repo.reviews(placeId: summary.id, sort: .recent, cursor: nil).reviews) ?? []
        loaded = true
        // Photos are re-encoded to WebP on the server: poll briefly until they're published.
        for _ in 0..<6 where reviews.contains(where: { r in (r.photos ?? []).contains { $0.url == nil || $0.status == .processing } }) {
            try? await Task.sleep(for: .seconds(2))
            if Task.isCancelled { return }
            reviews = (try? await model.repo.reviews(placeId: summary.id, sort: .recent, cursor: nil).reviews) ?? reviews
        }
    }
}

struct ReviewRow: View {
    @Environment(AppModel.self) private var model
    let review: Review
    let onChange: () async -> Void
    @State private var viewer: PhotoViewerItem?
    @State private var helpfulOn = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text(review.author.displayName).font(Tokens.Font.bodyBold)
                if let lvl = review.author.level { Text("Lv \(lvl)").font(Tokens.Font.small).foregroundStyle(Color.textMuted) }
                Spacer()
                Menu {
                    ForEach(FlagReason.allCases) { r in
                        Button(r.title) {
                            Task { try? await model.repo.flagReview(id: review.id, reason: r); model.showToast(String(localized: "Thanks, we'll take a look")) }
                        }
                    }
                } label: {
                    Image(systemName: "flag").font(.system(size: 14)).foregroundStyle(Color.textMuted).frame(width: 32, height: 32)
                }
                .accessibilityLabel(Text("Report review"))
                .accessibilityIdentifier("flagReview")
            }
            HStack(spacing: 6) {
                StarsView(rating: Double(review.rating), size: 13)
                Text(review.createdAt.formatted(.relative(presentation: .named))).font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                if review.editedAt != nil { Text("edited").font(Tokens.Font.small).foregroundStyle(Color.textMuted) }
            }
            if let t = review.text, !t.isEmpty { Text(t).font(Tokens.Font.body) }
            if let photos = review.photos, !photos.isEmpty {
                let ready = photos.filter { $0.url != nil }
                let urls = ready.compactMap { MediaURL.resolve($0.url) }
                PhotoStrip(thumbs: ready.compactMap { MediaURL.resolve($0.thumbUrl ?? $0.url) }) { i in viewer = PhotoViewerItem(urls: urls, index: i) }
                ForEach(photos.filter { $0.status == .processing || $0.url == nil }) { _ in
                    Label("Photo processing…", systemImage: "hourglass").font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                        .accessibilityIdentifier("photoProcessing")
                }
            }
            Button {
                helpfulOn.toggle()
                Task { try? await model.repo.toggleHelpful(reviewId: review.id); await onChange() }
            } label: {
                Label("Helpful (\(review.helpfulCount))", systemImage: helpfulOn ? "hand.thumbsup.fill" : "hand.thumbsup")
                    .font(Tokens.Font.label)
            }
            .buttonStyle(.plain)
            .foregroundStyle(Color.brand)
            .accessibilityIdentifier("helpfulReview")
        }
        .padding(12)
        .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
        .accessibilityIdentifier("reviewRow")
        .fullScreenCover(item: $viewer) { v in PhotoViewer(urls: v.urls, index: v.index) }
    }
}

extension FlagReason {
    var title: LocalizedStringKey {
        switch self {
        case .spam: "Spam"
        case .offensive: "Offensive"
        case .offTopic: "Off topic"
        case .conflictOfInterest: "Conflict of interest"
        case .other: "Other"
        }
    }
}

struct AllReviewsSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let placeId: String
    @State private var sort: ReviewSort = .recent
    @State private var reviews: [Review] = []
    @State private var next: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 10) {
                    Picker("Sort", selection: $sort) {
                        Text("Recent").tag(ReviewSort.recent)
                        Text("Helpful").tag(ReviewSort.helpful)
                    }
                    .pickerStyle(.segmented)
                    ForEach(reviews) { r in ReviewRow(review: r) { await load(reset: true) } }
                    if next != nil {
                        ProgressView().task { await load(reset: false) }
                    }
                }
                .padding(Tokens.Space.gutter)
            }
            .navigationTitle("Reviews")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            .task(id: sort) { await load(reset: true) }
        }
    }

    private func load(reset: Bool) async {
        guard let page = try? await model.repo.reviews(placeId: placeId, sort: sort, cursor: reset ? nil : next) else { return }
        reviews = reset ? page.reviews : reviews + page.reviews
        next = page.next
    }
}

/// Stars, text and up to 5 photos; one review per place (editable).
struct WriteReviewSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let placeId: String
    let placeName: String
    let existing: Review?
    let onDone: () async -> Void
    @State private var rating = 0
    @State private var text = ""
    @State private var photos: [PickedPhoto] = []
    @State private var progress: Double?
    @State private var error: String?
    @State private var sending = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    HStack(spacing: 8) {
                        ForEach(1...5, id: \.self) { n in
                            Button { rating = n } label: {
                                Image(systemName: n <= rating ? "star.fill" : "star")
                                    .font(.system(size: 38))
                                    .foregroundStyle(Color(uiColor: Tokens.Palette.traffic2))
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Text("\(n) stars"))
                            .accessibilityIdentifier("reviewStar.\(n)")
                        }
                    }
                    .frame(maxWidth: .infinity)
                    Text(StarWord.word(rating))
                        .font(Tokens.Font.bodyBold).foregroundStyle(Color.brandInk)
                        .frame(maxWidth: .infinity)
                        .accessibilityIdentifier("ratingWord")
                    TextField(String(localized: "Tell us about your visit"), text: $text, axis: .vertical)
                        .lineLimit(4...8)
                        .padding(14)
                        .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
                        .accessibilityIdentifier("reviewText")
                        .onChange(of: text) { if text.count > 2000 { text = String(text.prefix(2000)) } }
                    PhotoPickerRow(photos: $photos)
                    if let progress {
                        ProgressView(value: progress) { Text("Uploading photos…").font(Tokens.Font.caption) }
                            .accessibilityIdentifier("reviewUploadProgress")
                    }
                    if let error { Text(error).foregroundStyle(Color.speeding).font(Tokens.Font.caption).accessibilityIdentifier("reviewError") }
                    Button(existing == nil ? "Post review" : "Save changes") { Task { await send() } }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(rating == 0 || sending)
                        .accessibilityIdentifier("postReview")
                    if let existing {
                        Button("Delete review", role: .destructive) {
                            Task {
                                try? await model.repo.deleteReview(id: existing.id)
                                try? model.store.setSetting("review.\(placeId)", nil)
                                await onDone()
                                dismiss()
                            }
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
                .padding(Tokens.Space.gutter)
            }
            .navigationTitle(placeName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } } }
            .onAppear {
                if let existing { rating = existing.rating; text = existing.text ?? "" }
            }
        }
    }

    private func send() async {
        sending = true
        defer { sending = false }
        error = nil
        do {
            var photoIds = existing?.photos?.map(\.id) ?? []
            if !photos.isEmpty {
                progress = 0
                for (i, p) in photos.enumerated() {
                    let base = Double(i) / Double(photos.count), share = 1 / Double(photos.count)
                    let uploaded = try await model.repo.uploadPhoto(p.jpeg, placeId: placeId, caption: nil) { f in
                        Task { @MainActor in progress = base + f * share }
                    }
                    photoIds.append(uploaded.id)
                }
            }
            let body = NewReview(rating: rating, text: text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : text,
                                 photoIds: photoIds.isEmpty ? nil : Array(photoIds.prefix(5)))
            let saved = existing == nil ? try await model.repo.createReview(placeId: placeId, body) : try await model.repo.updateReview(id: existing!.id, body)
            try? model.store.setSetting("review.\(placeId)", saved.id)
            await onDone()
            model.showToast(String(localized: "Thanks for your review!"))
            dismiss()
        } catch let e as APIError where e.isServerSideGap {
            error = String(localized: "Reviews aren't available on the server yet.")
        } catch APIError.http(status: 403, _) where existing != nil {
            // Not ours any more (e.g. a different device identity): forget it so the next save writes a new review.
            try? model.store.setSetting("review.\(placeId)", nil)
            await onDone()
            error = String(localized: "Only the author can change this review.")
        } catch {
            self.error = error.localizedDescription
        }
        progress = nil
    }
}

// MARK: - My contributions

struct MyContributionsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var places: [PlaceSummary] = []
    @State private var loading = true
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 14) {
                        TierRing(tier: model.user?.tier ?? .baharu, progress: model.tierProgress)
                        VStack(alignment: .leading, spacing: 4) {
                            TierBadge(tier: model.user?.tier ?? .baharu)
                            if let next = model.user?.nextTier {
                                Text("Next: \(Text(next.name.title))").font(Tokens.Font.bodyBold)
                                if let first = next.missing.first {
                                    Text(NextTierText.describe(first)).font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                                }
                            }
                        }
                    }
                    .accessibilityIdentifier("contributionsTier")
                }
                Section("Shared with everyone") {
                    if loading { ProgressView() }
                    if let error { Text(error).foregroundStyle(Color.textMuted).font(Tokens.Font.caption) }
                    if !loading && places.isEmpty && error == nil { Text("Nothing shared yet").foregroundStyle(Color.textMuted) }
                    ForEach(places) { p in
                        Button { dismiss(); model.openPlace(p) } label: { row(p.name, p.category, status: p.status) }
                            .accessibilityIdentifier("contribution.\(p.name)")
                    }
                }
                Section {
                    if model.privatePlaces.isEmpty { Text("No private places").foregroundStyle(Color.textMuted) }
                    ForEach(model.privatePlaces) { p in
                        Button { dismiss(); model.openPlace(p.summary) } label: { row(p.name, p.category, status: nil) }
                            .accessibilityIdentifier("privatePlace.\(p.name)")
                    }
                } header: {
                    Text("Just for me")
                } footer: {
                    Text("Stored only on this phone and in your own backups.")
                }
            }
            .navigationTitle("My contributions")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            .task {
                do { places = try await model.repo.myContributions() } catch {
                    self.error = (error as? APIError)?.isServerSideGap == true
                        ? String(localized: "Contributions aren't available on the server yet.") : error.localizedDescription
                }
                loading = false
            }
        }
    }

    private func row(_ name: String, _ cat: PlaceCategory, status: PlaceStatus?) -> some View {
        HStack(spacing: 12) {
            CategoryIcon(category: cat)
            Text(name).font(Tokens.Font.bodyBold).foregroundStyle(Color.textPrimary)
            Spacer()
            if let status {
                Text(status.badgeTitle)
                    .font(Tokens.Font.small).foregroundStyle(.white)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(status == .published ? Color.brand : Color(uiColor: Tokens.Palette.traffic2), in: Capsule())
            }
        }
    }
}

/// The word under the stars (board "Tulis ulasan").
enum StarWord {
    static func word(_ rating: Int) -> LocalizedStringKey {
        switch rating {
        case 1: "Awful"
        case 2: "Poor"
        case 3: "Okay"
        case 4: "Good"
        case 5: "Excellent!"
        default: "Tap a star"
        }
    }
}

extension PlaceStatus {
    /// Localized badge text (a `String` would bypass the catalog).
    var badgeTitle: LocalizedStringKey {
        switch self {
        case .pending: "Unverified"
        case .published: "Published"
        case .closed: "Closed"
        case .hidden: "Hidden"
        }
    }
}
