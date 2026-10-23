import SwiftUI

/// Report sheet: 3-column grid of 104pt duotone tiles (no title — the icons carry the meaning),
/// then subtypes. One tap sends.
struct ReportSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var type: ReportType?
    @State private var comment = ""

    private let columns = Array(repeating: GridItem(.flexible(), spacing: Tokens.Space.gridGap), count: 3)

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                if type != nil {
                    Button { withAnimation(.snappy) { type = nil } } label: {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 20, weight: .bold))
                            .foregroundStyle(Color.brandInk)
                            .frame(width: 48, height: 48)
                            .background(Color.brandMist, in: Circle())
                    }
                    .accessibilityLabel(Text("Back"))
                }
                Spacer()
                Button { dismiss() } label: {
                    GimanoIcon(icon: .close, size: 22)
                        .frame(width: 48, height: 48)
                        .background(Color.brandMist, in: Circle())
                }
                .accessibilityLabel(Text("Close"))
            }
            .padding(.horizontal, 20)
            .padding(.top, 16)
            .padding(.bottom, 8)

            ScrollView {
                if let type {
                    subtypeGrid(type)
                } else {
                    LazyVGrid(columns: columns, spacing: Tokens.Space.gridGap + 4) {
                        ForEach(ReportType.allCases) { t in
                            Button { withAnimation(.snappy) { type = t } } label: {
                                ReportTile(icon: t.gIcon, title: t.shortTitle, type: t)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Text(t.title))
                            .accessibilityIdentifier("reportType.\(t.rawValue)")
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 24)
                }
            }
        }
        .background(Color.surface)
    }

    @ViewBuilder private func subtypeGrid(_ t: ReportType) -> some View {
        VStack(spacing: 18) {
            if !t.subtypes.isEmpty {
                LazyVGrid(columns: columns, spacing: Tokens.Space.gridGap + 4) {
                    ForEach(t.subtypes) { s in
                        Button {
                            model.submitReport(type: t, subtype: s.id, comment: nil)
                        } label: {
                            ReportTile(icon: t.gIcon, title: s.title, type: t, badge: s.systemImage)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(Text("\(String(localized: t.title)): \(String(localized: s.title))"))
                    }
                }
            } else {
                ReportTile(icon: t.gIcon, title: t.shortTitle, type: t)
                    .frame(width: Tokens.Size.reportTile + 20)
            }
            if t.wantsComment {
                TextField(t == .closure ? String(localized: "Which road? (optional)") : String(localized: "What's wrong? (optional)"),
                          text: $comment, axis: .vertical)
                    .font(Tokens.Font.body)
                    .lineLimit(2...4)
                    .padding(14)
                    .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: Tokens.Radius.md))
            }
            Button {
                model.submitReport(type: t, subtype: nil, comment: comment)
            } label: {
                Label("Send", systemImage: "paperplane.fill")
            }
            .buttonStyle(PrimaryButtonStyle(color: .report, foreground: .reportOn))
            .accessibilityIdentifier("sendReport")
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 24)
    }
}

struct ReportTile: View {
    let icon: GIcon
    let title: LocalizedStringResource
    let type: ReportType
    /// Small SF Symbol hint for subtypes (the main glyph stays the shared duotone icon).
    var badge: String?

    var body: some View {
        VStack(spacing: 8) {
            GimanoIcon(icon: icon, size: Tokens.Size.reportIcon, stroke: type.strokeColor, fill: type.fillColor)
                .frame(maxWidth: .infinity)
                .frame(height: Tokens.Size.reportTile)
                .background(type.tileColor, in: RoundedRectangle(cornerRadius: Tokens.Radius.reportTile, style: .continuous))
                .overlay(alignment: .topTrailing) {
                    if let badge {
                        Image(systemName: badge)
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(type.strokeColor)
                            .frame(width: 30, height: 30)
                            .background(Color.white.opacity(0.85), in: Circle())
                            .padding(8)
                    }
                }
            Text(title)
                .font(Tokens.Font.nunito(15, .extrabold))
                .foregroundStyle(Color.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .contentShape(Rectangle())
    }
}
