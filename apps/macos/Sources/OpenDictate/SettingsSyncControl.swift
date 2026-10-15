import SwiftUI

struct SettingsSyncControl: View {
    @ObservedObject var sync: GoogleDriveSync
    @ObservedObject var preferences: Preferences

    var body: some View {
        if preferences.driveSyncEnabled {
            HStack(spacing: 12) {
                Text(sync.statusText(preferences: preferences, compact: true))
                    .font(.caption).foregroundStyle(.secondary)
                    .lineLimit(1)
                    .help(sync.statusText(preferences: preferences))
                Button(action: sync.syncNow) {
                    HStack(spacing: 6) {
                        if sync.status == .busy {
                            ProgressView().controlSize(.small).frame(width: 14, height: 14)
                                .accessibilityLabel(preferences.t("Synchronizing…", "Синхронизация…"))
                        } else {
                            Image(systemName: "arrow.triangle.2.circlepath").frame(width: 14, height: 14)
                        }
                        Text(preferences.t("Sync now", "Синхронизировать"))
                    }
                }
                .keyboardShortcut("r", modifiers: .command)
                .disabled(!sync.canSyncNow)
                .fixedSize()
                .help(preferences.t("Sync settings now (⌘R)", "Синхронизировать настройки сейчас (⌘R)"))
            }
        }
    }
}
