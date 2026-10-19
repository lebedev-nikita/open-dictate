import SwiftUI

struct ChatGPTSettingsView: View {
    @ObservedObject var connection: ChatGPTConnection
    let hasKey: Bool
    let active: Bool
    let russian: Bool
    let connect: () -> Void
    var selectAccount: (String?) -> Void = { _ in }
    private func t(_ en: String, _ ru: String) -> String { russian ? ru : en }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Toggle(isOn: Binding(get: { connection.connected || connection.busy }, set: { enabled in
                if enabled { connect() } else { connection.disconnect() }
            })) {
                HStack(spacing: 12) {
                    ChatGPTMark().fill(.primary).frame(width: 24, height: 24).accessibilityHidden(true)
                    Text("ChatGPT").fontWeight(.medium)
                    Spacer(minLength: 12)
                }
            }
            .toggleStyle(.switch).controlSize(.regular)
            .disabled(active || connection.busy || (!hasKey && !connection.connected))
            .accessibilityIdentifier("chatGPTConnection")
            .help(t("Turn on to continue with ChatGPT. Turn off to sign out on this Mac.",
                    "Включите, чтобы подключить ChatGPT. Выключите, чтобы выйти на этом Mac."))
            VStack(alignment: .leading, spacing: 8) {
                if connection.busy {
                    HStack(alignment: .top, spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text(t("Waiting for ChatGPT…", "Ожидание ChatGPT…"))
                            .font(.caption).foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading).fixedSize(horizontal: false, vertical: true)
                        Button(t("Cancel", "Отмена"), action: connection.cancel)
                    }.controlSize(.small)
                }
                if connection.connected {
                    Text(t("Using ChatGPT plan", "Используется подписка ChatGPT")).font(.caption).foregroundStyle(.secondary)
                    if !connection.email.isEmpty { Text(connection.email).font(.caption).foregroundStyle(.secondary) }
                }
                if !connection.accounts.isEmpty {
                    Menu(t("ChatGPT accounts", "Аккаунты ChatGPT")) {
                        ForEach(connection.accounts, id: \.client) { account in
                            Button((account.email.isEmpty ? "ChatGPT" : account.email) + " · " + account.client.suffix(6)) { selectAccount(account.client) }
                        }
                        Button(t("Add another account", "Добавить другой аккаунт")) { selectAccount(nil) }
                    }.disabled(!hasKey || active || connection.busy)
                }
                Text(!hasKey
                     ? t("First save an API key for transcription. ChatGPT plan usage covers text requests only.",
                         "Сначала сохраните API-ключ для транскрипции. Подписка ChatGPT используется только для текста.")
                     : connection.connected ? t("Transcription uses your API key. Text transformation and post-processing use your ChatGPT plan.",
                         "Транскрипция использует API-ключ. Преобразование текста и постобработка — подписку ChatGPT.")
                     : t("Transcription uses your API key. Connect ChatGPT to use your plan for text; otherwise text uses API billing.",
                         "Транскрипция использует API-ключ. Подключите ChatGPT для оплаты текста из подписки; иначе текст оплачивается через API."))
                    .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                if connection.connected && connection.failed {
                    Button(t("Reconnect ChatGPT", "Войти в ChatGPT снова"), action: connect).disabled(!hasKey || active || connection.busy)
                }
                if connection.connected || connection.failed || connection.revocationUnconfirmed {
                    Link(t("Manage ChatGPT usage", "Управление расходом ChatGPT"), destination: URL(string: "https://chatgpt.com/settings/usage")!)
                }
                if connection.failed {
                    Text(t("Could not use ChatGPT. Check sign-in, Luna access and plan usage. No API fallback was used.",
                           "ChatGPT недоступен. Проверьте вход, доступ к Luna и лимиты подписки. Платный API не использован."))
                        .font(.caption)
                }
                if connection.revocationUnconfirmed {
                    Text(t("Signed out on this Mac. Remote sign-out was not confirmed; disconnect the app in ChatGPT settings.",
                           "На этом Mac выполнен выход. Отзыв доступа не подтверждён; отключите приложение в настройках ChatGPT."))
                        .font(.caption)
                }
            }.padding(.leading, 36)
        }
        .alert(t("You're using your ChatGPT plan", "Используется ваша подписка ChatGPT"), isPresented: $connection.welcome) {
            Button(t("Got it", "Понятно"), action: connection.dismissWelcome)
        } message: {
            Text(t("Eligible text requests use your ChatGPT plan or credits. Manage limits in ChatGPT settings. Transcription still uses your API key.",
                   "Текстовые запросы используют лимиты подписки ChatGPT или кредиты. Управляйте лимитами в настройках ChatGPT. Для транскрипции по-прежнему нужен API-ключ."))
        }
    }
}
