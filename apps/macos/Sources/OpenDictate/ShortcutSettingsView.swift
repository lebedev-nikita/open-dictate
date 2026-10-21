import SwiftUI

struct ShortcutSettingsView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var preferences: Preferences

    private func t(_ en: String, _ ru: String) -> String { preferences.t(en, ru) }

    @State private var showsVoiceEditingHelp = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(t("Keyboard shortcuts", "Горячие клавиши"))
                .font(.system(size: 27, weight: .semibold))

            VStack(alignment: .leading, spacing: 0) {
                shortcutSetting(t("Dictation", "Диктовка"), binding: \.dictate)
                shortcutSetting(t("Dictation with post-processing", "Диктовка с постобработкой"),
                    binding: \.dictateWithPostProcessing)
                shortcutSetting(t("Voice editing", "Голосовая правка"),
                    help: t("Select text first. With no selection, the whole field is edited.",
                            "Сначала выделите текст. Без выделения изменится всё поле."), binding: \.transform)
                Divider().padding(.vertical, 8)
                HStack(spacing: 12) {
                    Text(t("Cancel dictation", "Отменить диктовку"))
                    Spacer(minLength: 8)
                    Text("Esc").font(.system(.body, design: .monospaced))
                        .foregroundStyle(.secondary).frame(width: 175)
                    Color.clear.frame(width: 32, height: 1).accessibilityHidden(true)
                }.frame(minHeight: 44)
            }

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
        }.fixedSize(horizontal: false, vertical: true)
    }

    private func shortcutSetting(_ label: String, help: String? = nil, binding: WritableKeyPath<ShortcutBindings, KeyboardShortcut>) -> some View {
        let shortcut = preferences.shortcuts[keyPath: binding]
        let save: (KeyboardShortcut) -> Void = { value in
            var bindings = preferences.shortcuts
            bindings[keyPath: binding] = value
            model.setShortcuts(bindings)
        }
        return HStack(spacing: 12) {
            HStack(spacing: 6) {
                Text(label).fontWeight(.medium).fixedSize(horizontal: false, vertical: true)
                if let help {
                    Button { showsVoiceEditingHelp.toggle() } label: {
                        Image(systemName: "info.circle").foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(t("About voice editing", "О голосовой правке"))
                    .help(help)
                    .popover(isPresented: $showsVoiceEditingHelp) {
                        Text(help).font(.callout).padding(12).frame(width: 260)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
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
                .menuStyle(.borderlessButton).fixedSize().frame(width: 32)
                .accessibilityLabel(t("Choose a key for \(label)", "Выбрать клавишу: \(label)"))
                .help(t("Choose a key or turn off this shortcut", "Выбрать клавишу или отключить сочетание"))
                .disabled(model.isActive)
        }.frame(minHeight: 44)
    }
}
