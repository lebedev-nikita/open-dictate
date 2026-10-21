import SwiftUI

struct ShortcutSettingsView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var preferences: Preferences

    private func t(_ en: String, _ ru: String) -> String { preferences.t(en, ru) }

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            VStack(alignment: .leading, spacing: 8) {
                Text(t("Keyboard shortcuts", "Горячие клавиши"))
                    .font(.system(size: 27, weight: .semibold))
                Text(t("Start dictation from any app. Your shortcuts are saved on this Mac.",
                       "Запускайте диктовку из любого приложения. Сочетания сохраняются на этом Mac."))
                    .foregroundStyle(.primary)
            }

            VStack(alignment: .leading, spacing: 18) {
                shortcutSetting(t("Dictation", "Диктовка"),
                    description: t("Without post-processing. Press once to start, again to finish.", "Без постобработки. Нажмите, чтобы начать. Ещё раз — завершить."), binding: \.dictate)
                shortcutSetting(t("Dictation with post-processing", "Диктовка с постобработкой"),
                    description: t("Uses Accurate, then applies your post-processing rules. Press again to finish.",
                                   "Режим Accurate, затем ваши правила постобработки. Повторное нажатие завершает запись."), binding: \.dictateWithPostProcessing)
                shortcutSetting(t("Voice editing", "Голосовая правка"),
                    description: t("Select text first. With no selection, the whole field is edited.",
                                   "Сначала выделите текст. Без выделения изменится всё поле."), binding: \.transform)

                Text(t("Click a shortcut and press and release your keys. Escape clears it. The key menu also lets you choose a single key or turn the shortcut off.",
                       "Нажмите на сочетание, затем нажмите и отпустите нужные клавиши. Escape очищает сочетание. В меню справа можно выбрать одну клавишу или отключить сочетание."))
                    .font(.callout)
                if model.isActive {
                    Text(t("Finish dictation before changing shortcuts.", "Завершите диктовку, чтобы изменить сочетания."))
                        .font(.callout)
                }
                if !model.shortcutError.isEmpty {
                    Label(model.shortcutError, systemImage: "exclamationmark.triangle")
                        .font(.callout).fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("shortcutError")
                }
                Button(t("Restore default shortcuts", "Вернуть стандартные сочетания")) { model.setShortcuts(.defaults) }
                    .disabled(model.isActive || preferences.shortcuts == .defaults)
                Text(t("You can always start dictation and voice editing from the menu bar.",
                       "Диктовку и голосовую правку всегда можно запустить из строки меню."))
                    .font(.caption)
            }

            Divider()
            VStack(alignment: .leading, spacing: 10) {
                shortcutReference(t("Cancel dictation", "Отменить диктовку"), key: "Esc")
                Text(t("Available while recording or processing. Even if assigned above, Escape cancels the current dictation.",
                       "Работает во время записи и обработки. Даже если Escape назначена выше, она отменяет текущую диктовку."))
                    .font(.callout)
            }

            Divider()
            DisclosureGroup(t("Globe / Fn and function keys", "Globe / Fn и функциональные клавиши")) {
                VStack(alignment: .leading, spacing: 12) {
                    Text(t("To assign modifiers alone, press and release them. A modifier tap fires on release; using it with another key does not start dictation.",
                           "Чтобы назначить только модификаторы, нажмите и отпустите их. Сочетание срабатывает при отпускании; использование модификатора с другой клавишей не запускает диктовку."))
                    Text(t("For Globe/Fn, set “Press 🌐 key to” to “Do Nothing” in macOS Keyboard settings. Some external keyboards do not send Fn to macOS.",
                           "Для Globe/Fn выберите «При нажатии 🌐» → «Ничего не делать» в настройках клавиатуры macOS. Некоторые внешние клавиатуры не передают Fn в macOS."))
                    Text(t("For F1–F12, enable standard function keys in Keyboard → Keyboard Shortcuts → Function Keys, or hold Fn.",
                           "Для F1–F12 включите стандартные функциональные клавиши: «Клавиатура» → «Сочетания клавиш» → «Функциональные клавиши». Либо удерживайте Fn."))
                    Button(t("Open Keyboard Settings…", "Открыть настройки клавиатуры…")) {
                        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.Keyboard-Settings.extension")!)
                    }
                    if !model.accessibilityAllowed {
                        Text(t("Allow Accessibility for Globe/Fn and modifier taps to work in other apps.",
                               "Разрешите Универсальный доступ, чтобы Globe/Fn и модификаторы работали в других приложениях."))
                        Button(t("Allow Accessibility…", "Разрешить Универсальный доступ…"), action: model.requestAccessibility)
                    }
                }.font(.callout).padding(.top, 10)
            }
            DisclosureGroup(t("In the app window", "В окне приложения")) {
                VStack(spacing: 12) {
                    shortcutReference(t("Open settings", "Открыть настройки"), key: "⌘,")
                    shortcutReference(t("Close window", "Закрыть окно"), key: "⌘W")
                    shortcutReference(t("Close all windows", "Закрыть все окна"), key: "⇧⌘W")
                    shortcutReference(t("Quit OpenDictate", "Завершить OpenDictate"), key: "⌘Q")
                }.padding(.top, 10)
            }
        }.fixedSize(horizontal: false, vertical: true)
    }

    private func shortcutSetting(_ label: String, description: String, binding: WritableKeyPath<ShortcutBindings, KeyboardShortcut>) -> some View {
        let shortcut = preferences.shortcuts[keyPath: binding]
        let save: (KeyboardShortcut) -> Void = { value in
            var bindings = preferences.shortcuts
            bindings[keyPath: binding] = value
            model.setShortcuts(bindings)
        }
        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                Text(label).fontWeight(.medium)
                Spacer(minLength: 8)
                ShortcutRecorder(shortcut: shortcut, title: label, emptyLabel: t("Not set", "Не задано"),
                    prompt: t("Press and release…", "Нажмите и отпустите…"), enabled: !model.isActive,
                    onRecording: model.recordShortcut, onSave: save)
                    .frame(width: 175, height: 28)
                Menu {
                    Button(t("Turn off shortcut", "Отключить сочетание")) { save(.disabled) }
                        .disabled(!shortcut.isEnabled)
                    Divider()
                    Button("Globe / Fn") { save(.init(keyCode: nil, flags: .function)) }
                    ForEach(Array(KeyboardShortcut.functionKeyCodes.enumerated()), id: \.element) { index, code in
                        Button("F\(index + 1)") { save(.init(keyCode: code, flags: [], keyLabel: "F\(index + 1)")) }
                    }
                    Divider()
                    Button("Escape") { save(.init(keyCode: 53, flags: [], keyLabel: "Esc")) }
                } label: { Image(systemName: "keyboard") }
                    .menuStyle(.borderlessButton).fixedSize()
                    .accessibilityLabel(t("Choose a key for \(label)", "Выбрать клавишу: \(label)"))
                    .help(t("Choose a key or turn off this shortcut", "Выбрать клавишу или отключить сочетание"))
                    .disabled(model.isActive)
            }
            Text(description).font(.callout)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func shortcutReference(_ title: String, key: String) -> some View {
        HStack {
            Text(title)
            Spacer(minLength: 16)
            Text(key).font(.system(.body, design: .monospaced))
        }
    }
}
