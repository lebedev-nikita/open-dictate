import SwiftUI
import OpenDictateCore

struct ReplacementSettingsView: View {
    @ObservedObject var store: ReplacementStore
    @ObservedObject var preferences: Preferences
    @State private var editingID: String?
    @State private var source = ""
    @State private var replacement = ""
    @State private var regex = false
    @State private var matchCase = false
    @State private var invalid = false
    @FocusState private var sourceFocused: Bool
    private func t(_ en: String, _ ru: String) -> String { preferences.t(en, ru) }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.lg) {
            PageHeader(title: t("Word replacements", "Автозамена"))
            SettingsCard {
                SettingsRow(title: t("Enable word replacements", "Включить автозамену")) {
                    Toggle(t("Enable word replacements", "Включить автозамену"), isOn: $store.enabled)
                        .labelsHidden().toggleStyle(.switch).controlSize(.small)
                }
            }
            SettingsSection(title: editingID == nil ? t("Add a replacement", "Добавить замену") : t("Edit replacement", "Изменить замену")) {
                VStack(alignment: .leading, spacing: Space.sm) {
                    TextField(regex ? t("Pattern", "Шаблон") : t("Recognized word or phrase", "Распознанное слово или фраза"), text: $source)
                        .font(regex ? .body.monospaced() : .body).settingsField(focus: $sourceFocused)
                    TextField(regex ? t("Replace with, $1 for a group", "Заменить на, $1 — группа") : t("Replace with", "Заменить на"), text: $replacement)
                        .settingsField().onSubmit(save)
                    HStack(spacing: Space.md) {
                        Toggle(t("Regular expression", "Регулярное выражение"), isOn: $regex)
                        Toggle(t("Match case", "Учитывать регистр"), isOn: $matchCase)
                        Spacer(minLength: 0)
                        if editingID != nil { Button(t("Cancel", "Отмена"), action: reset) }
                        Button(editingID == nil ? t("Add", "Добавить") : t("Save", "Сохранить"), action: save)
                            .keyboardShortcut(.defaultAction)
                            .disabled(!canSave || store.status == .storageError)
                    }.toggleStyle(.checkbox).onChange(of: regex) { invalid = false }.onChange(of: matchCase) { invalid = false }
                    if invalid {
                        Text(store.status == .invalidPattern
                             ? t("Invalid pattern. It must not match empty text, and $n must refer to an existing group.",
                                 "Неверный шаблон. Он не должен совпадать с пустым текстом, а $n — ссылаться на существующую группу.")
                             : t("Use a unique phrase (up to 256 characters) and a replacement (up to 2,048). Maximum 500 rules.",
                                 "Введите уникальную фразу до 256 символов и замену до 2048. Максимум 500 правил."))
                            .font(.callout).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true)
                    }
                }.padding(Space.md)
            }
            if store.status == .storageError {
                Text(t("Couldn't read saved replacements. Your saved data was kept. Restore your preferences before editing.",
                       "Не удалось прочитать автозамены. Данные сохранены. Восстановите настройки перед редактированием."))
                    .foregroundStyle(.red)
            }
            SettingsSection(title: t("Rules", "Правила"),
                            footer: t("Whole words and phrases. Case is ignored unless “Match case” is on. In regular expressions, \\b is a word boundary and $1 is a group.",
                                      "Целые слова и фразы. Регистр не учитывается, если не включено «Учитывать регистр». В регулярных выражениях \\b — граница слова, $1 — группа.")) {
                if store.rules.isEmpty {
                    Text(t("No replacements yet. For example: “open dictate” → “OpenDictate”.", "Пока нет замен. Например: «опен диктейт» → «OpenDictate»."))
                        .foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true).settingsRowPadding()
                } else {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(store.rules.enumerated()), id: \.element.id) { index, rule in
                            if index > 0 { RowDivider() }
                            ruleRow(rule)
                        }
                    }
                }
            }
        }
    }
    private func ruleRow(_ rule: WordReplacement) -> some View {
        HStack(alignment: .top, spacing: Space.sm) {
            Toggle(t("Enable replacement", "Включить замену"), isOn: Binding(get: { rule.enabled }, set: { store.update(rule, enabled: $0) }))
                .labelsHidden().toggleStyle(.checkbox).accessibilityLabel("\(rule.source): " + t("Enable replacement", "Включить замену"))
            VStack(alignment: .leading, spacing: Space.xxs) {
                HStack(alignment: .firstTextBaseline, spacing: Space.xs) {
                    Text(rule.source).foregroundStyle(.secondary).font(rule.regex ? .body.monospaced() : .body)
                    // The tags sit further from the source than from each other, so they read as one group.
                    if rule.regex || rule.matchCase {
                        HStack(alignment: .firstTextBaseline, spacing: Space.xxs) {
                            if rule.regex { badge(".*", t("Regular expression", "Регулярное выражение"), design: .monospaced) }
                            if rule.matchCase { badge("Aa", t("Match case", "Учитывать регистр")) }
                        }
                    }
                }
                Text(rule.replacement).textSelection(.enabled)
            }.frame(maxWidth: .infinity, alignment: .leading)
            Button {
                editingID = rule.id; source = rule.source; replacement = rule.replacement; regex = rule.regex; matchCase = rule.matchCase; invalid = false; sourceFocused = true
            } label: { Image(systemName: "pencil") }.help(t("Edit replacement", "Изменить замену"))
                .accessibilityLabel(t("Edit replacement for ", "Изменить замену для ") + rule.source)
            Button {
                store.delete(rule); if editingID == rule.id { reset() }
            } label: { Image(systemName: "trash") }.help(t("Delete replacement", "Удалить замену"))
                .accessibilityLabel(t("Delete replacement for ", "Удалить замену для ") + rule.source)
        }.buttonStyle(.borderless).padding(Space.md)
    }
    private var canSave: Bool {
        regex ? !source.isEmpty : !source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !replacement.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
    private func save() {
        guard canSave else { return }
        if store.save(id: editingID, source: source, replacement: replacement, regex: regex, matchCase: matchCase) { reset() }
        else { invalid = true }
    }
    private func reset() { editingID = nil; source = ""; replacement = ""; regex = false; matchCase = false; invalid = false }
    /// Marks a rule option with the glyph find-and-replace fields use for it, so the list needs no translated tag.
    private func badge(_ glyph: String, _ label: String, design: Font.Design = .default) -> some View {
        // A tint rather than an outline: the tag is a label, not a control.
        Text(glyph).font(.system(size: 11, weight: .medium, design: design)).foregroundStyle(.secondary)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(.primary.opacity(0.08), in: RoundedRectangle(cornerRadius: 4)).help(label).accessibilityLabel(label)
    }
}
