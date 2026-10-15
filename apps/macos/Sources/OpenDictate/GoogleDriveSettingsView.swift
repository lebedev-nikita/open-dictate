import SwiftUI
import OpenDictateCore

struct GoogleDriveSettingsView: View {
    @ObservedObject var sync: GoogleDriveSync
    @ObservedObject var preferences: Preferences
    private func t(_ en: String, _ ru: String) -> String { preferences.t(en, ru) }
    private var connectionRequested: Bool { preferences.driveSyncEnabled || sync.status == .busy }
    var connection: Binding<Bool> {
        Binding(get: { connectionRequested }, set: { enabled in
            if enabled { sync.connect() } else { sync.disconnect() }
        })
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Toggle(isOn: connection) {
                HStack(spacing: 12) {
                    GoogleDriveMark()
                    Text("Google Drive").fontWeight(.medium)
                    Spacer(minLength: 12)
                }
            }
            .toggleStyle(.switch).controlSize(.regular)
            .accessibilityIdentifier("googleDriveConnection")
            .help(t("Turn off to sign out on this Mac. The next connection lets you choose a Google account.",
                    "Выключите, чтобы выйти на этом Mac. При следующем подключении можно выбрать аккаунт Google."))
            if sync.needsSignIn || sync.status == .failed || (sync.status == .busy && !preferences.driveSyncEnabled) {
                HStack(alignment: .top, spacing: 8) {
                    if sync.status == .busy { ProgressView().controlSize(.small) }
                    Text(sync.status == .busy
                         ? t("Finish signing in to Google in your browser.", "Завершите вход в Google в браузере.")
                         : sync.statusText(preferences: preferences))
                        .font(.caption).foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .fixedSize(horizontal: false, vertical: true)
                    if preferences.driveSyncEnabled && sync.needsSignIn {
                        Button(t("Sign in again", "Войти снова"), action: sync.connect)
                    }
                }.controlSize(.small)
            }
        }
        .sheet(isPresented: Binding(get: { sync.needsSourceSelection }, set: { shown in
            if !shown && sync.needsSourceSelection { sync.disconnect() }
        })) {
            DriveSourceSelectionView(preferences: preferences, onChoose: sync.chooseSource, onCancel: sync.disconnect)
        }
    }
}

/// The same vector geometry and provider colors as Android's ic_google_drive_24.xml.
private struct GoogleDriveMark: View {
    var body: some View {
        Canvas { context, size in
            func fill(_ rgb: UInt32, _ points: [CGPoint]) {
                var path = Path()
                path.addLines(points.map { CGPoint(x: $0.x * size.width / 24, y: $0.y * size.height / 24) })
                path.closeSubpath()
                context.fill(path, with: .color(Color(red: Double((rgb >> 16) & 255) / 255,
                    green: Double((rgb >> 8) & 255) / 255, blue: Double(rgb & 255) / 255)))
            }
            fill(0x00832D, [.init(x: 9, y: 3), .init(x: 15, y: 3), .init(x: 12, y: 8.25)])
            fill(0x00AC47, [.init(x: 9, y: 3), .init(x: 12, y: 8.25), .init(x: 7, y: 17), .init(x: 1, y: 17)])
            fill(0xFFBA00, [.init(x: 15, y: 3), .init(x: 23, y: 17), .init(x: 17, y: 17), .init(x: 12, y: 8.25)])
            fill(0x0066DA, [.init(x: 1, y: 17), .init(x: 7, y: 17), .init(x: 4, y: 22)])
            fill(0x2684FC, [.init(x: 7, y: 17), .init(x: 17, y: 17), .init(x: 20, y: 22), .init(x: 4, y: 22)])
            fill(0xEA4335, [.init(x: 17, y: 17), .init(x: 23, y: 17), .init(x: 20, y: 22)])
        }.frame(width: 24, height: 24).accessibilityHidden(true)
    }
}

extension GoogleDriveSync {
    func statusText(preferences: Preferences, compact: Bool = false) -> String {
        func t(_ en: String, _ ru: String) -> String { preferences.t(en, ru) }
        switch status {
        case .disconnected: return t("Not connected", "Не подключено")
        case .waiting: return compact ? t("Waiting to sync", "Ожидание синхронизации")
            : t("Waiting to synchronize", "Ожидание синхронизации")
        case .busy: return compact ? t("Synchronizing…", "Синхронизация…")
            : t("Synchronizing or waiting for Google sign-in…", "Синхронизация или ожидание входа в Google…")
        case .synced:
            let date = lastSyncedAt?.formatted(date: .abbreviated, time: .shortened) ?? ""
            if compact { return t("Last synced: \(date)", "Синхронизировано: \(date)") }
            return t("Last synced: \(date). Changes sync automatically while the app is running.",
                     "Последняя синхронизация: \(date). Изменения синхронизируются автоматически, пока приложение работает.")
        case .sourceSelection: return compact ? t("Choose settings source", "Выберите источник настроек")
            : t("Choose a settings source to finish connecting.", "Выберите источник настроек, чтобы завершить подключение.")
        case .failed: return compact ? t("Sync failed", "Ошибка синхронизации")
            : t("Could not sync. Local settings are saved. Check your connection and try again.",
                               "Не удалось синхронизировать. Локальные настройки сохранены. Проверьте соединение и повторите.")
        case .authorization: return compact ? t("Reconnect Google Drive", "Подключите Google Drive снова")
            : t("Google authorization expired or was revoked. Connect again to resume synchronization.",
                                     "Авторизация Google истекла или была отозвана. Подключитесь снова, чтобы возобновить синхронизацию.")
        case .signInTimedOut: return compact ? t("Sign-in timed out", "Время входа истекло")
            : t("Google sign-in timed out. Connect again and finish sign-in within 10 minutes.",
                                      "Время ожидания входа в Google истекло. Подключитесь снова и завершите вход в течение 10 минут.")
        case .configuration, .clientConfiguration:
            return compact ? t("Google Drive not configured", "Google Drive не настроен")
                : t("Google Drive is not configured in this build. Install an official build or contact support.",
                     "Google Drive не настроен в этой сборке. Установите официальную сборку или обратитесь в поддержку.")
        }
    }
}

struct DriveSourceSelectionView: View {
    @ObservedObject var preferences: Preferences
    let onChoose: (SettingsSyncDocument.ConnectionSource) -> Void
    let onCancel: () -> Void
    @State private var source: SettingsSyncDocument.ConnectionSource = .cloud
    private func t(_ en: String, _ ru: String) -> String { preferences.t(en, ru) }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text(t("Choose settings to sync", "Какие настройки синхронизировать?"))
                .font(.headline)
            Text(t("Google Drive and this Mac have different settings. Choose which dictionary, word replacements, dictation mode and models to use. Sync waits for your choice.",
                   "Настройки на Google Диске и этом Mac различаются. Выберите источник словаря, автозамен, режима диктовки и моделей. Синхронизация ждёт вашего выбора."))
                .fixedSize(horizontal: false, vertical: true)
            Picker(t("Settings source", "Источник настроек"), selection: $source) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(t("Cloud settings", "Облачные настройки"))
                    Text(t("Replace synced settings on this Mac with those from Google Drive.",
                           "Заменить синхронизируемые настройки на этом Mac настройками с Google Диска."))
                        .font(.callout).foregroundStyle(.primary)
                }.tag(SettingsSyncDocument.ConnectionSource.cloud)
                VStack(alignment: .leading, spacing: 4) {
                    Text(t("Local settings", "Локальные настройки"))
                    Text(t("Publish this Mac’s synced settings to Google Drive. Other connected devices will receive them.",
                           "Отправить синхронизируемые настройки этого Mac на Google Диск. Их получат остальные подключённые устройства."))
                        .font(.callout).foregroundStyle(.primary)
                }.tag(SettingsSyncDocument.ConnectionSource.local)
            }.pickerStyle(.radioGroup).labelsHidden()
            HStack {
                Button(t("Cancel", "Отмена"), action: onCancel).keyboardShortcut(.cancelAction)
                Spacer()
                Button(source == .cloud ? t("Use cloud settings", "Использовать облачные")
                       : t("Use local settings", "Использовать локальные")) { onChoose(source) }
                    .keyboardShortcut(.defaultAction)
            }
        }.padding(24).frame(width: 480)
    }
}
