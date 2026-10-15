import SwiftUI

struct SettingsSyncControl: View {
    @ObservedObject var sync: GoogleDriveSync
    @ObservedObject var preferences: Preferences

    var body: some View {
        if preferences.driveSyncEnabled {
            HStack(spacing: 8) {
                statusLabel
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .help(sync.statusText(preferences: preferences))
                Button(action: sync.syncNow) {
                    ZStack {
                        if sync.status == .busy {
                            ProgressView().controlSize(.small).frame(width: 14, height: 14)
                        } else {
                            Image(systemName: "arrow.triangle.2.circlepath").frame(width: 14, height: 14)
                        }
                    }.frame(width: 24, height: 24).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .keyboardShortcut("r", modifiers: .command)
                .disabled(!sync.canSyncNow)
                .accessibilityLabel(preferences.t("Sync now", "Синхронизировать"))
                .help(preferences.t("Sync settings now (⌘R)", "Синхронизировать настройки сейчас (⌘R)"))
            }
        }
    }

    @ViewBuilder private var statusLabel: some View {
        if sync.status == .synced, let date = sync.lastSyncedAt {
            TimelineView(.periodic(from: .now, by: 60)) { context in
                Text(preferences.t("Synced", "Синхронизировано") + " · " + relativeDate(date, now: context.date))
            }
        } else {
            Text(sync.statusText(preferences: preferences, compact: true))
        }
    }

    private func relativeDate(_ date: Date, now: Date) -> String {
        guard now.timeIntervalSince(date) >= 60 else {
            return preferences.t("just now", "только что")
        }
        let formatter = RelativeDateTimeFormatter()
        formatter.locale = Locale(identifier: preferences.isRussian ? "ru_RU" : "en_US")
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: now)
    }
}
