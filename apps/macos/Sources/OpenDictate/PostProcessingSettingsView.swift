import SwiftUI
import OpenDictateCore

struct PostProcessingSettingsView: View {
    @ObservedObject var preferences: Preferences
    @State private var prompt = PostProcessingPreset.punctuation.text
    @State private var loaded = false
    @State private var original = PostProcessingSettings()

    private func t(_ en: String, _ ru: String) -> String { preferences.t(en, ru) }
    private var draft: PostProcessingSettings {
        PostProcessingSettings(enabled: preferences.postProcessing.enabled,
            prompt: prompt == PostProcessingPreset.punctuation.text ? nil : prompt)
    }
    private var changed: Bool { prompt != original.effectivePrompt }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Toggle(t("Post-processing", "Постобработка"), isOn: Binding(
                get: { preferences.postProcessing.enabled },
                set: { preferences.postProcessing.enabled = $0 }))
                .toggleStyle(.switch).fontWeight(.medium)
            if preferences.postProcessing.enabled {
                Text(t("Applies after Live or Accurate finishes. Sends the transcript to OpenAI in an additional request.",
                    "Применяется после завершения Live или Accurate. Отправляет расшифровку в OpenAI дополнительным запросом."))
                    .font(.callout).fixedSize(horizontal: false, vertical: true)
                VStack(alignment: .leading, spacing: 6) {
                    Text(t("Post-processing prompt", "Промпт постобработки"))
                    TextEditor(text: $prompt).font(.body).scrollContentBackground(.hidden)
                        .padding(8).frame(height: 150)
                        .background(Color(nsColor: .textBackgroundColor), in: RoundedRectangle(cornerRadius: 8))
                        .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Color.primary.opacity(0.2)))
                        .accessibilityLabel(t("Post-processing prompt", "Промпт постобработки"))
                    if prompt.utf8.count > PostProcessingSettings.maximumPromptBytes {
                        Text(t("Use at most 16,384 UTF-8 bytes. Your draft has been kept.",
                            "Не более 16 384 байт UTF-8. Черновик сохранён в редакторе."))
                            .font(.caption).foregroundStyle(.red)
                    }
                }
                Text(t("Add an instruction", "Добавить инструкцию")).font(.caption)
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) { presetButtons }
                    VStack(alignment: .leading, spacing: 8) { presetButtons }
                }
                Text(t("The buttons add editable text. An empty prompt skips post-processing. Save to apply changes to the next dictation.",
                    "Кнопки добавляют редактируемый текст. Пустой промпт пропускает постобработку. Сохраните изменения для следующей диктовки."))
                    .font(.caption).fixedSize(horizontal: false, vertical: true)
                HStack {
                    Button(t("Reset prompt", "Сбросить промпт")) { prompt = PostProcessingPreset.punctuation.text }
                        .disabled(prompt == PostProcessingPreset.punctuation.text)
                    Spacer()
                    if changed {
                        Button(t("Cancel", "Отмена")) { load(preferences.postProcessing) }
                    }
                    Button(t("Save", "Сохранить")) {
                        preferences.postProcessing = draft
                        load(preferences.postProcessing)
                    }.disabled(!changed || !draft.isValid)
                }
            }
        }
        .onAppear { if !loaded { load(preferences.postProcessing); loaded = true } }
        .onChange(of: preferences.postProcessing) { _, next in
            // Incoming sync may update the section, but must not replace a local draft.
            if !changed { load(next) }
        }
    }

    private var presetButtons: some View {
        Group {
            Button(t("Correct punctuation", "Исправить пунктуацию")) { prompt = PostProcessingPreset.punctuation.append(to: prompt) }
                .disabled(prompt.contains(PostProcessingPreset.punctuation.text))
            Button(t("Format numbers", "Отформатировать числа")) { prompt = PostProcessingPreset.numbers.append(to: prompt) }
                .disabled(prompt.contains(PostProcessingPreset.numbers.text))
        }
    }
    private func load(_ settings: PostProcessingSettings) {
        original = settings; prompt = settings.effectivePrompt
    }
}
