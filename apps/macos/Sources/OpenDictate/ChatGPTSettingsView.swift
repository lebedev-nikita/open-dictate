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
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                if connection.connected {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(t("Using ChatGPT plan", "Используется подписка ChatGPT"))
                        if !connection.email.isEmpty { Text(connection.email).font(.caption) }
                    }
                    Spacer()
                    Button(t("Disconnect", "Отключить"), action: connection.disconnect).disabled(active || connection.busy)
                } else {
                    Button(t("Continue with ChatGPT", "Подключить ChatGPT"), action: connect)
                        .disabled(!hasKey || active || connection.busy)
                }
                if connection.busy {
                    ProgressView().controlSize(.small)
                    Button(t("Cancel", "Отмена"), action: connection.cancel)
                }
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
                .font(.caption).fixedSize(horizontal: false, vertical: true)
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
        }
        .alert(t("You're using your ChatGPT plan", "Используется ваша подписка ChatGPT"), isPresented: $connection.welcome) {
            Button(t("Got it", "Понятно"), action: connection.dismissWelcome)
        } message: {
            Text(t("Eligible text requests use your ChatGPT plan or credits. Manage limits in ChatGPT settings. Transcription still uses your API key.",
                   "Текстовые запросы используют лимиты подписки ChatGPT или кредиты. Управляйте лимитами в настройках ChatGPT. Для транскрипции по-прежнему нужен API-ключ."))
        }
    }
}
