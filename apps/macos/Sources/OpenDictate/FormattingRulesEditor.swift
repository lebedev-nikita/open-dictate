import SwiftUI
import OpenDictateCore

struct FormattingRulesEditor: View {
    @ObservedObject var preferences: Preferences
    @State private var draft: String

    init(preferences: Preferences) {
        self.preferences = preferences
        _draft = State(initialValue: preferences.formattingRules)
    }

    private func t(_ en: String, _ ru: String) -> String { preferences.t(en, ru) }
    private var isValid: Bool { draft.utf16.count <= PunctuationCorrection.maximumRulesLength }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(t("Formatting rules", "Правила форматирования")).fontWeight(.medium)
            Text(t("Describe punctuation, capitalization and paragraph breaks. Words and numbers are preserved. For example: “Do not add a final period.”",
                   "Опишите пунктуацию, регистр и абзацы. Слова и числа сохраняются. Например: «Не добавляй точку в конце»."))
                .font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            TextEditor(text: $draft).font(.body)
                .scrollContentBackground(.hidden).padding(10)
                .frame(height: 120)
                .background(Color(nsColor: .textBackgroundColor), in: RoundedRectangle(cornerRadius: 8))
                .accessibilityLabel(t("AI formatting rules", "Правила форматирования для AI"))
            if !isValid {
                Text(t("Use at most 4,000 characters. Shorten the rules to save them.",
                       "Не более 4000 символов. Сократите правила, чтобы сохранить их."))
                    .font(.callout).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true)
            }
            HStack {
                Button(t("Save rules", "Сохранить правила")) { preferences.formattingRules = draft }
                    .disabled(!isValid || draft == preferences.formattingRules)
                if draft != preferences.formattingRules {
                    Button(t("Revert", "Отменить изменения")) { draft = preferences.formattingRules }
                }
            }
            Text(t("Empty rules use the default punctuation correction. Saved rules sync through Google Drive, even when post-processing is off. Changes apply to the next dictation or retranscription.",
                   "Пустое поле — стандартное исправление пунктуации. Сохранённые правила синхронизируются через Google Drive, даже когда постобработка выключена. Изменения действуют со следующей диктовки или повторного распознавания."))
                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
        .onChange(of: preferences.formattingRules) { previous, next in
            if draft == previous { draft = next }
        }
    }
}
