import CloudKit
import SwiftUI
import UniformTypeIdentifiers

extension UTType {
    static let manotuBackup = UTType(exportedAs: "my.manotu.backup", conformingTo: .zip)
    /// Backups made before the rename (still imported).
    static let gimanoBackup = UTType(importedAs: "my.gimano.backup", conformingTo: .zip)
}

/// "Your places stay on your phone" (docs/privacy-promise.md, short version).
struct PrivacyCard: View {
    var onBackup: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                GimanoIcon(icon: .home, size: 40)
                    .frame(width: 60, height: 60)
                    .background(Color.brandMist, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                Text("Your places stay on your phone").font(Tokens.Font.title)
            }
            Text(GoogleDriveBackupDestination.isEnabled
                 ? "Home, work, kampung, your in-laws' house: your places live on your phone, not on our servers. Manotu never stores your personal data. Backups go straight to your own Google Drive or a file, optionally locked with a passphrase only you know."
                 : "Home, work, kampung, your in-laws' house: your places live on your phone, not on our servers. Manotu never stores your personal data. Back up to a file you keep, optionally locked with a passphrase only you know.")
                .font(Tokens.Font.body)
                .foregroundStyle(Color.textMuted)
            if let onBackup {
                Button(action: onBackup) { Label("Backup & restore", systemImage: "arrow.clockwise.icloud") }
                    .buttonStyle(SecondaryButtonStyle())
                    .accessibilityIdentifier("privacyCardBackup")
            }
        }
        .padding(Tokens.Space.gutter)
        .background(Color.surface, in: RoundedRectangle(cornerRadius: Tokens.Radius.lg, style: .continuous))
        .accessibilityIdentifier("privacyCard")
    }
}

/// Settings → Backup & restore.
struct BackupView: View {
    @Environment(AppModel.self) private var model
    @State private var usePassphrase = false
    @State private var passphrase = ""
    @State private var confirm = ""
    @State private var exportURL: URL?
    @State private var showImporter = false
    @State private var pendingRestore: PendingRestore?
    @State private var icloudStatus: CKAccountStatus = .couldNotDetermine
    @State private var googleEmail: String?
    @State private var message: String?
    #if DEBUG
    @State private var selfTest: String?
    #endif

    struct PendingRestore: Identifiable {
        let id = UUID()
        var remote: RemoteBackup?
        var file: URL?
        var manifest: BackupManifest?
    }

    private var backup: BackupManager { model.backup }
    private var passphraseOK: Bool { !usePassphrase || (passphrase.count >= 8 && passphrase == confirm) }

    var body: some View {
        @Bindable var backup = model.backup
        Form {
            if model.restoreOnFirstRun {
                Section {
                    Label(firstRunRestoreNote, systemImage: "arrow.uturn.backward.circle")
                        .font(Tokens.Font.caption)
                        .accessibilityIdentifier("firstRunRestoreNote")
                    Button("Choose a backup file") { showImporter = true }
                        .accessibilityIdentifier("firstRunPickFile")
                }
            }
            Section {
                statusCard.listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
            } footer: {
                Label("Your places stay on your phone. Backups go straight to your own storage; Gimano can't read them.", systemImage: "lock.shield")
                    .font(Tokens.Font.caption)
                    .accessibilityIdentifier("privacyCard")
            }

            Section("Back up to") {
                ForEach(backup.offeredDestinations) { kind in destinationRow(kind) }
                destinationStatus
                if !GoogleDriveBackupDestination.isEnabled {
                    // Store build (GIMANO_DRIVE = NO): no Drive destination, no Google sign-in. Same for every user.
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Google Drive").font(Tokens.Font.bodyBold)
                            Text("Google Drive backup isn't available in this version. Backup to a file works.").font(Tokens.Font.caption)
                        }
                    } icon: {
                        Image(systemName: "externaldrive.badge.xmark")
                    }
                    .foregroundStyle(Color.textMuted)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("backupDestination.googleDriveUnavailable")
                }
                if !ICloudBackupDestination.isEnabled {
                    // Shown, but clearly unavailable: no iCloud entitlement in this build.
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("iCloud").font(Tokens.Font.bodyBold)
                            Text("iCloud backup isn't available in this version").font(Tokens.Font.caption)
                        }
                    } icon: {
                        Image(systemName: "icloud.slash")
                    }
                    .foregroundStyle(Color.textMuted)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("backupDestination.icloudUnavailable")
                }
            }

            Section {
                Toggle("Lock with a passphrase", isOn: $usePassphrase).accessibilityIdentifier("backupPassphraseToggle")
                if usePassphrase {
                    SecureField("Passphrase (8+ characters)", text: $passphrase).accessibilityIdentifier("backupPassphrase")
                    SecureField("Repeat passphrase", text: $confirm).accessibilityIdentifier("backupPassphraseConfirm")
                }
            } footer: {
                Text(usePassphrase
                     ? "End-to-end encrypted. Gimano never stores your passphrase: if you lose it, this backup can't be recovered."
                     : "Backups are stored in your own cloud or file. Add a passphrase to encrypt them end to end.")
                if !usePassphrase && (try? model.store.identity()) != nil {
                    Label("Without a passphrase, this backup also holds your pseudonym's key in plain form in your own storage. Anyone with the file could sign as you.",
                          systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(Color(uiColor: Tokens.Palette.traffic2))
                        .accessibilityIdentifier("identityBackupWarning")
                }
            }

            Section {
                if backup.destination == .file {
                    Button {
                        Task { await export() }
                    } label: { Label("Export backup file", systemImage: "square.and.arrow.up") }
                        .disabled(!passphraseOK || backup.isWorking)
                        .accessibilityIdentifier("exportBackup")
                    if let exportURL {
                        ShareLink(item: exportURL) { Label("Share \(exportURL.lastPathComponent)", systemImage: "square.and.arrow.up.on.square") }
                            .accessibilityIdentifier("shareBackup")
                    }
                    Button { showImporter = true } label: { Label("Restore from file…", systemImage: "square.and.arrow.down") }
                        .accessibilityIdentifier("importBackup")
                } else {
                    Button {
                        Task { await backup.backUpNow(passphrase: usePassphrase ? passphrase : nil); await backup.refreshRemote() }
                    } label: {
                        Label(backup.destination == .demo ? "Back up to demo storage" : "Back up now", systemImage: "arrow.up.circle.fill")
                    }
                        .disabled(!passphraseOK || backup.isWorking || !destinationReady)
                        .accessibilityIdentifier("backUpNow")
                }
                if backup.isWorking { ProgressView() }
                if let s = backup.status { Text(s).foregroundStyle(Color.brand).accessibilityIdentifier("backupStatus") }
                if let e = backup.lastError { Text(e).foregroundStyle(Color.speeding).font(Tokens.Font.caption).accessibilityIdentifier("backupError") }
                if let message { Text(message).font(Tokens.Font.caption).accessibilityIdentifier("backupMessage") }
            } header: {
                Text("Back up")
            } footer: {
                if let at = backup.lastBackupAt {
                    Text("Last backup \(at.formatted(date: .abbreviated, time: .shortened))\(backup.lastBackupSize.map { " · " + ByteCountFormatter.string(fromByteCount: $0, countStyle: .file) } ?? "")")
                        .accessibilityIdentifier("lastBackup")
                }
            }

            if backup.destination != .file {
                Section("Restore") {
                    if backup.remoteBackups.isEmpty {
                        Text("No backups yet").foregroundStyle(Color.textMuted)
                    }
                    ForEach(backup.remoteBackups) { b in
                        Button {
                            Task {
                                do {
                                    let file = try await backup.download(b)
                                    pendingRestore = PendingRestore(file: file, manifest: backup.manifest(of: file))
                                } catch {
                                    message = error.localizedDescription
                                }
                            }
                        } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(b.createdAt.formatted(date: .abbreviated, time: .shortened)).font(Tokens.Font.bodyBold)
                                    Text(b.name).font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                                }
                                Spacer()
                                if let s = b.size { Text(ByteCountFormatter.string(fromByteCount: s, countStyle: .file)).font(Tokens.Font.caption) }
                            }
                            .foregroundStyle(Color.textPrimary)
                        }
                        .accessibilityIdentifier("remoteBackup")
                    }
                }
                if backup.destination != .demo {
                Section {
                    Toggle("Back up automatically every day", isOn: $backup.autoBackup).accessibilityIdentifier("autoBackup")
                } footer: {
                    Text("On Wi-Fi while charging, only when something changed. Automatic backups are not passphrase-locked, because Gimano never stores your passphrase.")
                }
                }
            }

            #if DEBUG
            Section("Developer") {
                Button("Self-test: back up and restore (file)") { Task { await runSelfTest() } }
                    .accessibilityIdentifier("backupSelfTest")
                if let selfTest { Text(selfTest).font(Tokens.Font.caption).accessibilityIdentifier("backupSelfTestResult") }
            }
            #endif
        }
        .navigationTitle("Backup & restore")
        .onDisappear { model.restoreOnFirstRun = false }
        .task { await refreshStatus() }
        .onChange(of: backup.destination) { Task { await refreshStatus() } }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.manotuBackup, .gimanoBackup, .zip, .data]) { result in
            if case .success(let url) = result {
                pendingRestore = PendingRestore(file: url, manifest: backup.manifest(of: url))
            }
        }
        .sheet(item: $pendingRestore) { p in
            RestoreSheet(pending: p) { message = String(localized: "Restored") }
        }
    }

    /// Names only the places this build can restore from.
    private var firstRunRestoreNote: LocalizedStringKey {
        switch (GoogleDriveBackupDestination.isEnabled, ICloudBackupDestination.isEnabled) {
        case (true, true): "Restore your places from a backup: choose where it is (a file, Google Drive or iCloud), then pick it. Nothing on this phone changes until you choose Merge or Replace and confirm."
        case (true, false): "Restore your places from a backup: choose where it is (a file or Google Drive), then pick it. Nothing on this phone changes until you choose Merge or Replace and confirm."
        case (false, true): "Restore your places from a backup: choose where it is (a file or iCloud), then pick it. Nothing on this phone changes until you choose Merge or Replace and confirm."
        case (false, false): "Restore your places from a backup file: choose the file, then check what it holds. Nothing on this phone changes until you choose Merge or Replace and confirm."
        }
    }

    private var destinationReady: Bool {
        switch backup.destination {
        case .icloud: ICloudBackupDestination.isEnabled && icloudStatus == .available
        case .googleDrive: GoogleDriveBackupDestination.isEnabled && googleEmail != nil
        case .file: true
        case .demo: backup.demoMode
        }
    }

    /// One row per destination: what it is and what it needs (board "Sandaran"; review build: no account needed except
    /// for the user's own Google Drive).
    private func destinationRow(_ kind: BackupDestinationKind) -> some View {
        let selected = backup.destination == kind
        let (title, detail, system): (LocalizedStringKey, LocalizedStringKey, String) = switch kind {
        case .googleDrive: ("Google Drive (your own Google account)", "Needs your Google account; Manotu never sees it. Optional.", "externaldrive.fill.badge.icloud")
        case .file: ("Save to a file (no account)", "The full backup and restore, passphrase included. You choose where the file goes.", "doc.zipper")
        case .demo: ("Demo storage (on this phone)", GoogleDriveBackupDestination.isEnabled
                     ? "Demo, not Google Drive. Shown only in demo mode, to try backup and restore without any account. Kept inside this app and removed with it."
                     : "Demo only, not a cloud backup. Shown only in demo mode, to try backup and restore without any account. Kept inside this app and removed with it.",
                     "play.circle")
        case .icloud: ("iCloud", "Your own iCloud account", "icloud.fill")
        }
        return Button { model.backup.destination = kind } label: {
            HStack(spacing: 12) {
                Image(systemName: system).font(.system(size: 22, weight: .semibold)).foregroundStyle(Color.brandInk)
                    .frame(width: 40, height: 40)
                    .background(Color.brandMist, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(Tokens.Font.bodyBold).foregroundStyle(Color.textPrimary)
                    Text(detail).font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 4)
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .font(.system(size: 22)).foregroundStyle(selected ? Color.brand : Color.outline)
                    .accessibilityHidden(true)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("backupDestination.\(kind.rawValue)")
    }

    @ViewBuilder private var destinationStatus: some View {
        switch backup.destination {
        case .icloud where !ICloudBackupDestination.isEnabled:
            Label("iCloud isn't enabled in this build", systemImage: "exclamationmark.circle")
                .font(Tokens.Font.caption)
                .accessibilityIdentifier("icloudStatus")
        case .icloud:
            Label(icloudStatus == .available ? String(localized: "iCloud is ready") : String(localized: "Sign in to iCloud in Settings to back up there."),
                  systemImage: icloudStatus == .available ? "checkmark.circle.fill" : "exclamationmark.circle")
                .font(Tokens.Font.caption)
                .accessibilityIdentifier("icloudStatus")
        case .googleDrive where !GoogleDriveBackupDestination.isEnabled:
            // Unreachable (Drive is never offered or kept as the destination in this build); no sign-in button.
            Label("Google Drive backup isn't available in this version. Backup to a file works.", systemImage: "exclamationmark.circle")
                .font(Tokens.Font.caption)
                .accessibilityIdentifier("driveNotAvailable")
        case .googleDrive:
            if !GoogleDriveBackupDestination.isConfigured {
                Label("Google Drive: not configured in this build", systemImage: "exclamationmark.circle")
                    .font(Tokens.Font.caption)
                    .accessibilityIdentifier("driveNotConfigured")
            } else if let googleEmail {
                HStack {
                    Label(googleEmail, systemImage: "person.crop.circle.fill").font(Tokens.Font.caption)
                    Spacer()
                    Button("Sign out") { GoogleDriveBackupDestination.signOut(); self.googleEmail = nil }
                }
            } else {
                Button("Sign in with Google") {
                    Task {
                        do { googleEmail = try await GoogleDriveBackupDestination.signIn(); await backup.refreshRemote() }
                        catch { message = error.localizedDescription }
                    }
                }
            }
        case .file:
            Text("Export a .gimano file and keep it anywhere: Files, WhatsApp, email.").font(Tokens.Font.caption)
        case .demo:
            Label(GoogleDriveBackupDestination.isEnabled ? "Demo, not Google Drive" : "Demo only, not a cloud backup", systemImage: "info.circle")
                .font(Tokens.Font.caption)
                .accessibilityIdentifier("demoBackupNote")
        }
    }

    /// Board "Sandaran": big status card (last backup, where, size, locked or not).
    private var statusCard: some View {
        let at = model.backup.lastBackupAt
        let kind: LocalizedStringKey = switch model.backup.destination {
        case .icloud: "iCloud"
        case .googleDrive: "Google Drive"
        case .file: "File"
        case .demo: "Demo storage (on this phone)"
        }
        return HStack(spacing: 14) {
            Image(systemName: at == nil ? "icloud.slash.fill" : "checkmark.icloud.fill")
                .font(.system(size: 30, weight: .bold))
                .foregroundStyle(at == nil ? Color.textMuted : Color.brand)
                .frame(width: 56, height: 56)
                .background(Color.brandMist, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            VStack(alignment: .leading, spacing: 3) {
                if let at {
                    Text("Last backup: \(at.formatted(.relative(presentation: .named)))").font(Tokens.Font.bodyBold)
                } else {
                    Text("No backup yet").font(Tokens.Font.bodyBold)
                }
                HStack(spacing: 4) {
                    Text(kind)
                    if let size = model.backup.lastBackupSize { Text(verbatim: "· " + ByteCountFormatter.string(fromByteCount: size, countStyle: .file)) }
                    if usePassphrase { Text("· locked") }
                }
                .font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .background(Color.surface, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("backupStatusCard")
    }

    private func refreshStatus() async {
        switch backup.destination {
        case .icloud:
            icloudStatus = await ICloudBackupDestination().accountStatus()
            if icloudStatus == .available { await backup.refreshRemote() }
        case .googleDrive:
            guard GoogleDriveBackupDestination.isEnabled else { googleEmail = nil; return }
            googleEmail = await GoogleDriveBackupDestination.restoreSession()
            if googleEmail != nil { await backup.refreshRemote() }
        case .file:
            break
        case .demo:
            await backup.refreshRemote()
        }
    }

    private func export() async {
        do {
            exportURL = try await backup.makeFile(passphrase: usePassphrase ? passphrase : nil)
            message = nil
        } catch {
            message = error.localizedDescription
        }
    }

    #if DEBUG
    /// Exports, wipes and restores on device, then checks the counts match (used by the device UI tests).
    private func runSelfTest() async {
        selfTest = nil
        let before = (try? model.store.counts()) ?? [:]
        do {
            let pass = usePassphrase ? passphrase : nil
            let file = try await backup.makeFile(passphrase: pass)
            try model.store.wipePersonalData()
            try await backup.restore(file: file, mode: .replace, passphrase: pass)
            model.reloadLocal()
            let after = (try? model.store.counts()) ?? [:]
            let kind = pass == nil ? "plain" : "encrypted"
            selfTest = before == after ? "PASS \(kind) \(after.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: " "))" : "FAIL \(before) vs \(after)"
        } catch {
            selfTest = "FAIL \(error.localizedDescription)"
        }
    }
    #endif
}

/// Choose Replace or Merge (and the passphrase if needed), then restore.
struct RestoreSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let pending: BackupView.PendingRestore
    let onDone: () -> Void
    @State private var mode: RestoreMode = .merge
    @State private var passphrase = ""
    @State private var error: String?
    @State private var working = false

    var body: some View {
        NavigationStack {
            Form {
                if let m = pending.manifest {
                    Section("Backup") {
                        LabeledContent("Made on", value: m.platform == "android" ? "Android" : "iPhone")
                        LabeledContent("Date", value: LocalStore.date(m.createdAt)?.formatted(date: .abbreviated, time: .shortened) ?? m.createdAt)
                        LabeledContent("Saved places", value: "\(m.counts["saved_places"] ?? 0)")
                        LabeledContent("Private places", value: "\(m.counts["private_places"] ?? 0)")
                        LabeledContent("Recents", value: "\(m.counts["recents"] ?? 0)")
                    }
                }
                Section {
                    Picker("Restore", selection: $mode) {
                        Text("Merge").tag(RestoreMode.merge)
                        Text("Replace").tag(RestoreMode.replace)
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("restoreMode")
                } footer: {
                    Text(mode == .merge ? "Keeps your current places and adds or updates places from the backup. Home and Work come from the backup."
                                        : "Deletes the places on this phone first, then restores the backup.")
                }
                if let m = pending.manifest, m.encryption != BackupManifest.encryptionNone {
                    Section("Passphrase") {
                        SecureField("Passphrase", text: $passphrase).accessibilityIdentifier("restorePassphrase")
                    }
                }
                Section {
                    Button(mode == .replace ? "Replace with this backup" : "Merge this backup") { Task { await restore() } }
                        .disabled(working)
                        .accessibilityIdentifier("restoreButton")
                    if working { ProgressView() }
                    if let error { Text(error).foregroundStyle(Color.speeding).accessibilityIdentifier("restoreError") }
                }
            }
            .navigationTitle("Restore")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } } }
        }
    }

    private func restore() async {
        working = true
        defer { working = false }
        do {
            let pass = passphrase.isEmpty ? nil : passphrase
            if let remote = pending.remote {
                try await model.backup.restore(remote: remote, mode: mode, passphrase: pass)
            } else if let file = pending.file {
                try await model.backup.restore(file: file, mode: mode, passphrase: pass)
            }
            model.reloadLocal()
            onDone()
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }
    }
}
