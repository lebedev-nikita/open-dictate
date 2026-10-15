import SwiftUI

struct SettingsSyncControl: View {
    @ObservedObject var sync: GoogleDriveSync
    @ObservedObject var preferences: Preferences

    var body: some View {
        if preferences.driveSyncEnabled {
            VStack(alignment: .trailing, spacing: 6) {
                Button(action: sync.syncNow) {
                    HStack(spacing: 6) {
                        if sync.status == .busy {
                            ProgressView().controlSize(.small).frame(width: 14, height: 14)
                        } else {
                            Image(systemName: "arrow.triangle.2.circlepath").frame(width: 14, height: 14)
                        }
                        Text(preferences.t("Sync now", "Синхронизировать"))
                    }
                }
                .keyboardShortcut("r", modifiers: .command)
                .disabled(!sync.canSyncNow)
                .help(preferences.t("Sync settings now (⌘R)", "Синхронизировать настройки сейчас (⌘R)"))
                Text(sync.statusText(preferences: preferences, compact: true))
                    .font(.caption).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}
