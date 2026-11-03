import SwiftUI
import OpenDictateCore

struct ReplacementSettingsView: View {
    @ObservedObject var store: ReplacementStore
    @ObservedObject var preferences: Preferences
    @State private var editingID: String?
    @State private var source = ""
    @State private var replacement = ""
    @State private var regex = false
    @State private var invalid = false
    @FocusState private var sourceFocused: Bool
    private func t(_ en: String, _ ru: String) -> String { preferences.t(en, ru) }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text(t("Word replacements", "Автозамена")).font(.system(size: 27, weight: .semibold))
            Text(t("Replace recognized words and phrases with your preferred spelling in Live and Accurate dictation.",
                   "Заменяйте распознанные слова и фразы на нужное написание в Live и Accurate."))
                .foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            Toggle(t("Enable word replacements", "Включить автозамену"), isOn: $store.enabled).toggleStyle(.switch)
            Divider()
            VStack(alignment: .leading, spacing: 12) {
                Text(editingID == nil ? t("Add a replacement", "Добавить замену") : t("Edit replacement", "Изменить замену")).fontWeight(.medium)
                TextField(regex ? t("Pattern", "Шаблон") : t("Recognized word or phrase", "Распознанное слово или фраза"), text: $source)
                    .textFieldStyle(.roundedBorder).focused($sourceFocused).font(regex ? .body.monospaced() : .body)
                TextField(regex ? t("Replace with, $1 for a group", "Заменить на, $1 — группа") : t("Replace with", "Заменить на"), text: $replacement)
                    .textFieldStyle(.roundedBorder).onSubmit(save)
                Toggle(t("Regular expression", "Регулярное выражение"), isOn: $regex).toggleStyle(.checkbox)
                    .onChange(of: regex) { invalid = false }
                HStack {
                    Button(editingID == nil ? t("Add", "Добавить") : t("Save", "Сохранить"), action: save)
                        .disabled(!canSave || store.status == .storageError)
                    if editingID != nil { Button(t("Cancel", "Отмена"), action: reset) }
                }
                if invalid {
                    Text(store.status == .invalidPattern
                         ? t("Invalid pattern. It must not match empty text, and $n must refer to an existing group.",
                             "Неверный шаблон. Он не должен совпадать с пустым текстом, а $n — ссылаться на существующую группу.")
                         : t("Use a unique phrase (up to 256 characters) and a replacement (up to 2,048). Maximum 500 rules.",
                             "Введите уникальную фразу до 256 символов и замену до 2048. Максимум 500 правил."))
                        .font(.callout).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true)
                }
            }
            if store.status == .storageError {
                Text(t("Couldn't read saved replacements. Your saved data was kept. Restore your preferences before editing.",
                       "Не удалось прочитать автозамены. Данные сохранены. Восстановите настройки перед редактированием."))
                    .foregroundStyle(.red)
            }
            Divider()
            if store.rules.isEmpty {
                Text(t("No replacements yet. For example: “open dictate” → “OpenDictate”.", "Пока нет замен. Например: «опен диктейт» → «OpenDictate»."))
                    .foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            } else {
                LazyVStack(alignment: .leading, spacing: 16) {
                    ForEach(store.rules) { rule in
                        HStack(alignment: .top, spacing: 12) {
                            Toggle(t("Enable replacement", "Включить замену"), isOn: Binding(get: { rule.enabled }, set: { store.update(rule, enabled: $0) }))
                                .labelsHidden().toggleStyle(.checkbox).accessibilityLabel("\(rule.source): " + t("Enable replacement", "Включить замену"))
                            VStack(alignment: .leading, spacing: 4) {
                                // Pattern rules read as `/pattern/`, distinct from phrases without a translated badge.
                                Text(rule.regex ? "/\(rule.source)/" : rule.source).foregroundStyle(.secondary)
                                    .font(rule.regex ? .body.monospaced() : .body)
                                    .accessibilityLabel(rule.regex ? t("Regular expression ", "Регулярное выражение ") + rule.source : rule.source)
                                Text(rule.replacement).textSelection(.enabled)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                            Button {
                                editingID = rule.id; source = rule.source; replacement = rule.replacement; regex = rule.regex; invalid = false; sourceFocused = true
                            } label: { Image(systemName: "pencil") }.help(t("Edit replacement", "Изменить замену"))
                                .accessibilityLabel(t("Edit replacement for ", "Изменить замену для ") + rule.source)
                            Button {
                                store.delete(rule); if editingID == rule.id { reset() }
                            } label: { Image(systemName: "trash") }.help(t("Delete replacement", "Удалить замену"))
                                .accessibilityLabel(t("Delete replacement for ", "Удалить замену для ") + rule.source)
                        }.buttonStyle(.borderless)
                        Divider()
                    }
                }
            }
            Text(t("Whole words and phrases, ignoring case. Replacements keep exactly the spelling you enter. Regular expressions also ignore case: use \\b for word boundaries and $1 for groups. Changes apply to the next dictation. Connect Google Drive in Settings to sync rules across devices.",
                   "Целые слова и фразы без учёта регистра. Написание замены сохраняется как введено. Регулярные выражения тоже без учёта регистра: \\b — граница слова, $1 — группа. Изменения действуют со следующей диктовки. Подключите Google Drive в настройках для синхронизации правил между устройствами."))
                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
    }
    private var canSave: Bool {
        regex ? !source.isEmpty : !source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !replacement.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
    private func save() {
        guard canSave else { return }
        if store.save(id: editingID, source: source, replacement: replacement, regex: regex) { reset() }
        else { invalid = true }
    }
    private func reset() { editingID = nil; source = ""; replacement = ""; regex = false; invalid = false }
}
