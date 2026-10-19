import SwiftUI
import OpenDictateCore

struct PostProcessingSettingsView: View {
    @ObservedObject var preferences: Preferences
    @State private var formatsOpen = false
    private func t(_ en: String, _ ru: String) -> String { preferences.t(en, ru) }
    private var settings: PostProcessingSettings { preferences.postProcessing }
    var body: some View {
        if preferences.mode == .accurate {
            VStack(alignment: .leading, spacing: 14) {
                Toggle(isOn: Binding(get: { settings.enabled }, set: { preferences.postProcessing.enabled = $0 })) {
                    HStack { Text(t("Post-processing", "Постобработка")).fontWeight(.medium); Spacer(minLength: 12) }
                }.toggleStyle(.switch)
                if settings.enabled {
                    Toggle(isOn: Binding(get: { settings.punctuation }, set: { preferences.postProcessing.punctuation = $0 })) {
                        HStack { Text(t("Correct punctuation", "Исправить пунктуацию")); Spacer(minLength: 12) }
                    }.toggleStyle(.switch)
                    HStack(spacing: 12) {
                        Button { formatsOpen = true } label: {
                            HStack {
                                Text(t("Format values", "Отформатировать значения"))
                                Image(systemName: "chevron.right").font(.caption)
                            }
                        }.buttonStyle(.plain)
                        .popover(isPresented: $formatsOpen, arrowEdge: .bottom) {
                            ValueFormattingMenu(initial: settings.formats ?? .systemDefaults(), russian: preferences.isRussian,
                                onCancel: { formatsOpen = false }, onSave: { formats in
                                    var next = settings
                                    next.formats = formats; next.formatValues = true
                                    preferences.postProcessing = next
                                    formatsOpen = false
                                })
                        }
                        Spacer()
                        Toggle(t("Format values", "Отформатировать значения"), isOn: Binding(get: { settings.formatValues }, set: { enabled in
                            var next = settings
                            if enabled && next.formats == nil { next.formats = .systemDefaults() }
                            next.formatValues = enabled
                            preferences.postProcessing = next
                        })).labelsHidden().toggleStyle(.switch)
                    }
                    Button(t("Reset settings", "Сбросить настройки")) { preferences.postProcessing = .init(enabled: settings.enabled) }
                        .disabled(settings == PostProcessingSettings(enabled: settings.enabled))
                }
            }
        }
    }
}

struct ValueFormattingMenu: View {
    @State private var draft: ValueFormatting
    private let defaults: ValueFormatting
    let russian: Bool
    let onCancel: () -> Void
    let onSave: (ValueFormatting) -> Void
    init(initial: ValueFormatting, russian: Bool, defaults: ValueFormatting = .systemDefaults(),
         onCancel: @escaping () -> Void, onSave: @escaping (ValueFormatting) -> Void) {
        self._draft = State(initialValue: initial); self.defaults = defaults; self.russian = russian
        self.onCancel = onCancel; self.onSave = onSave
    }
    private func t(_ en: String, _ ru: String) -> String { russian ? ru : en }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(t("Format values", "Отформатировать значения")).font(.headline)
            Text(t("Edit the examples or choose a format.",
                   "Измените образцы или выберите формат."))
                .font(.callout).fixedSize(horizontal: false, vertical: true)
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    ForEach(ValueKind.allCases, id: \.self) { kind in
                        VStack(alignment: .leading, spacing: 6) {
                            Toggle(kind.title(russian: russian), isOn: Binding(get: { draft[kind].enabled }, set: { draft[kind].enabled = $0 }))
                                .toggleStyle(.checkbox).fontWeight(.medium)
                            HStack(spacing: 8) {
                                TextField(kind.title(russian: russian), text: Binding(get: { draft[kind].example }, set: { draft[kind].example = $0 }))
                                    .textFieldStyle(.roundedBorder).disabled(!draft[kind].enabled)
                                Menu {
                                    Button(t("System: ", "Системный: ") + defaults[kind].example) { draft[kind].example = defaults[kind].example }
                                    Divider()
                                    ForEach(kind.examples, id: \.self) { example in Button(example) { draft[kind].example = example } }
                                } label: { Image(systemName: "chevron.down") }
                                    .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize().disabled(!draft[kind].enabled)
                                    .accessibilityLabel(t("Choose format: ", "Выбрать формат: ") + kind.title(russian: russian))
                            }
                            if !draft[kind].isValid {
                                Text(t("Use an example with digits, at most 96 characters and no line breaks.",
                                       "Образец должен содержать цифры, не более 96 символов и без переноса строк."))
                                    .font(.caption).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true)
                            }
                        }
                    }
                }.padding(.trailing, 4)
            }.frame(height: 370)
            HStack {
                Button(t("System defaults", "Как в системе")) { draft = defaults }
                Spacer()
                Button(t("Cancel", "Отмена"), action: onCancel).keyboardShortcut(.cancelAction)
                Button(t("Done", "Готово")) { onSave(draft) }.keyboardShortcut(.defaultAction).disabled(!draft.isValid)
            }
        }.padding(20).frame(width: 420)
    }
}
