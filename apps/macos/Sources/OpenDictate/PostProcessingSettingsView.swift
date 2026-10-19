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
        Group {
            if preferences.mode == .accurate {
                VStack(alignment: .leading, spacing: 14) {
                    Toggle(t("Post-processing", "Постобработка"), isOn: Binding(
                        get: { preferences.postProcessing.enabled },
                        set: { preferences.postProcessing.enabled = $0 }))
                        .toggleStyle(.switch).fontWeight(.medium)
                    if preferences.postProcessing.enabled {
                        presetRow(t("Correct punctuation", "Исправить пунктуацию"), preset: .punctuation)
                        presetRow(t("Format numbers", "Отформатировать числа"), preset: .numbers)
                        HStack {
                            Button(t("Reset prompt", "Сбросить промпт")) { prompt = PostProcessingPreset.punctuation.text }
                                .disabled(prompt == PostProcessingPreset.punctuation.text)
                            Spacer()
                            if changed { Button(t("Cancel", "Отмена")) { load(preferences.postProcessing) } }
                            Button(t("Save", "Сохранить")) {
                                preferences.postProcessing = draft
                                load(preferences.postProcessing)
                            }.disabled(!changed || !draft.isValid)
                        }
                        VStack(alignment: .leading, spacing: 6) {
                            Text(t("Post-processing prompt", "Промпт постобработки"))
                            TextEditor(text: $prompt).font(.body).scrollContentBackground(.hidden)
                                .padding(8).frame(height: 150)
                                .background(Color(nsColor: .textBackgroundColor), in: RoundedRectangle(cornerRadius: 8))
                                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Color.primary.opacity(0.2)))
                                .accessibilityLabel(t("Post-processing prompt", "Промпт постобработки"))
                            if prompt.utf8.count > PostProcessingSettings.maximumPromptBytes {
                                Text(t("Use at most 16,384 UTF-8 bytes.", "Не более 16 384 байт UTF-8."))
                                    .font(.caption).foregroundStyle(.red)
                            }
                        }
                    }
                }
            }
        }
        .onAppear { if !loaded { load(preferences.postProcessing); loaded = true } }
        .onChange(of: preferences.postProcessing) { _, next in
            if !changed { load(next) }
        }
    }

    private func presetRow(_ title: String, preset: PostProcessingPreset) -> some View {
        HStack(spacing: 12) {
            Text(title).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 12)
            Button { prompt = preset.append(to: prompt) } label: {
                Image(systemName: "plus").frame(width: 16, height: 16)
            }
            .accessibilityLabel(t("Add instruction: ", "Добавить инструкцию: ") + title)
            .disabled(prompt.contains(preset.text))
        }
    }
    private func load(_ settings: PostProcessingSettings) {
        original = settings; prompt = settings.effectivePrompt
    }
}
