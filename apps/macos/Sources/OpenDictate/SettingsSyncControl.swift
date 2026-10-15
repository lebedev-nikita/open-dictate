import SwiftUI

extension VerticalAlignment {
    private enum SettingsControlCenter: AlignmentID {
        static func defaultValue(in context: ViewDimensions) -> CGFloat { context[VerticalAlignment.center] }
    }

    static let settingsControlCenter = VerticalAlignment(SettingsControlCenter.self)
}

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
                                .accessibilityLabel(preferences.t("Synchronizing…", "Синхронизация…"))
                        } else {
                            Image(systemName: "arrow.triangle.2.circlepath").frame(width: 14, height: 14)
                        }
                        Text(preferences.t("Sync now", "Синхронизировать"))
                    }
                }
                .keyboardShortcut("r", modifiers: .command)
                .disabled(!sync.canSyncNow)
                .help(preferences.t("Sync settings now (⌘R)", "Синхронизировать настройки сейчас (⌘R)"))
                // Align the button with the theme control, independently of the status below it.
                .alignmentGuide(.settingsControlCenter) { $0[VerticalAlignment.center] }
                Text(sync.statusText(preferences: preferences, compact: true))
                    .font(.caption).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .opacity(sync.status == .busy ? 0 : 1)
                    .accessibilityHidden(sync.status == .busy)
            }
        }
    }
}
