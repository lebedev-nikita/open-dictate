import SwiftUI
import OpenDictateCore

struct TranscriptionPromptEditor: View {
    @ObservedObject var preferences: Preferences
    @Environment(\.dismiss) private var dismiss
    @State private var original: String?
    @State private var defaultText: String
    @State private var text: String
    @State private var usesDefault: Bool

    init(preferences: Preferences) {
        self.preferences = preferences
        let original = preferences.customTranscriptionPrompt
        let defaultText = TranscriptionPrompt.defaultText(languages: SpeechLanguage.normalize(preferences.speechLanguages))
        _original = State(initialValue: original)
        _defaultText = State(initialValue: defaultText)
        _text = State(initialValue: original ?? defaultText)
        _usesDefault = State(initialValue: original == nil)
    }
    private func t(_ en: String, _ ru: String) -> String { preferences.t(en, ru) }
    private var changed: Bool { usesDefault ? original != nil : original != text }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(t("Transcription prompt", "Промпт транскрипции")).font(.title2).fontWeight(.semibold)
            Text(t("Optional recording context for Live and Accurate: topics, names and terminology. Empty by default. Use Post-processing for text formatting. Languages and dictionary terms are sent separately.",
                   "Необязательный контекст для Live и Accurate: тема, имена и термины. По умолчанию пустой. Форматирование текста настраивается в постобработке. Языки и словарь передаются отдельно."))
                .foregroundStyle(.primary).fixedSize(horizontal: false, vertical: true)
            TextEditor(text: $text)
                .font(.body).scrollContentBackground(.hidden).padding(10)
                .background(Color(nsColor: .textBackgroundColor), in: RoundedRectangle(cornerRadius: 8))
                .accessibilityLabel(t("Prompt text", "Текст промпта"))
                .accessibilityIdentifier("transcription-prompt-text")
                .onChange(of: text) { if text != defaultText { usesDefault = false } }
            if !TranscriptionPrompt.isValid(text) {
                Text(t("Prompt is too long. Maximum: 16,384 UTF-8 bytes.", "Промпт слишком длинный. Максимум: 16 384 байта UTF-8."))
                    .foregroundStyle(.red).fixedSize(horizontal: false, vertical: true)
            }
            Text(t("Changes apply to the next dictation and sync with Google Drive when connected. An empty custom prompt sends no prompt.",
                   "Изменения действуют со следующей диктовки и синхронизируются через подключённый Google Drive. Пустой пользовательский текст отключает промпт."))
                .font(.caption).foregroundStyle(.primary).fixedSize(horizontal: false, vertical: true)
            HStack {
                Button(t("Reset to default", "Сбросить к умолчанию")) { usesDefault = true; text = defaultText }
                    .disabled(usesDefault && text == defaultText)
                Spacer()
                Button(t("Cancel", "Отмена")) { dismiss() }.keyboardShortcut(.cancelAction)
                Button(t("Save", "Сохранить")) {
                    preferences.customTranscriptionPrompt = usesDefault ? nil : text
                    dismiss()
                }.keyboardShortcut(.defaultAction)
                    .disabled(!changed || !TranscriptionPrompt.isValid(text))
            }
        }.padding(24).frame(minWidth: 570, idealWidth: 620, minHeight: 500, idealHeight: 600)
    }
}
