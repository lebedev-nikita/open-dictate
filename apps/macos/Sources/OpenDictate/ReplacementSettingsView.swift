import SwiftUI
import OpenDictateCore

struct ReplacementSettingsView: View {
    @ObservedObject var store: ReplacementStore
    @ObservedObject var preferences: Preferences
    @State private var draft = ReplacementDraft()
    @State private var editingID: String?
    @State private var edit = ReplacementDraft()
    @FocusState private var addFocused: Bool
    @FocusState private var editFocused: Bool
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
            SettingsSection(title: t("Add a replacement", "Добавить замену")) {
                editor($draft, id: nil, focus: $addFocused, isDefault: editingID == nil).padding(Space.md)
            }
            if store.status == .storageError {
                Text(t("Couldn't read saved replacements. Your saved data was kept. Restore your preferences before editing.",
                       "Не удалось прочитать автозамены. Данные сохранены. Восстановите настройки перед редактированием."))
                    .foregroundStyle(.red)
            }
            SettingsSection(title: t("Rules", "Правила"),
                            footer: t("Phrases replace whole words only; regular expressions do so when “Whole word” is on. Case is ignored unless “Match case” is on. $1 inserts a group.",
                                      "Фразы заменяют только целые слова, регулярные выражения — если включено «Слово целиком». Регистр не учитывается, если не включено «Учитывать регистр». $1 — группа.")) {
                if store.rules.isEmpty {
                    Text(t("No replacements yet. For example: “open dictate” → “OpenDictate”.", "Пока нет замен. Например: «опен диктейт» → «OpenDictate»."))
                        .foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true).settingsRowPadding()
                } else {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(store.rules.enumerated()), id: \.element.id) { index, rule in
                            if index > 0 { RowDivider() }
                            // The rule is edited in its own row, so the list never moves under the pointer.
                            if editingID == rule.id { editor($edit, id: rule.id, focus: $editFocused, isDefault: true).padding(Space.md) }
                            else { ruleRow(rule) }
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
                            if rule.wholeWord { badge("ab", t("Whole word", "Слово целиком"), underline: true) }
                        }
                    }
                }
                Text(rule.replacement).textSelection(.enabled)
            }.frame(maxWidth: .infinity, alignment: .leading)
            Button {
                editingID = rule.id; edit = ReplacementDraft(rule); draft.invalid = false; editFocused = true
            } label: { Image(systemName: "pencil") }.help(t("Edit replacement", "Изменить замену"))
                .accessibilityLabel(t("Edit replacement for ", "Изменить замену для ") + rule.source)
            Button {
                store.delete(rule); if editingID == rule.id { editingID = nil }
            } label: { Image(systemName: "trash") }.help(t("Delete replacement", "Удалить замену"))
                .accessibilityLabel(t("Delete replacement for ", "Удалить замену для ") + rule.source)
        }.buttonStyle(.borderless).padding(Space.md)
    }
    private func editor(_ draft: Binding<ReplacementDraft>, id: String?, focus: FocusState<Bool>.Binding, isDefault: Bool) -> some View {
        ReplacementEditor(draft: draft, sourceFocused: focus, status: store.status, isDefault: isDefault, t: t,
                          onSave: { save(draft, id: id) }, onCancel: id == nil ? nil : { editingID = nil })
    }
    private func save(_ draft: Binding<ReplacementDraft>, id: String?) {
        let value = draft.wrappedValue
        guard value.canSave else { return }
        if store.save(id: id, source: value.source, replacement: value.replacement, regex: value.regex, matchCase: value.matchCase,
                      wholeWord: value.wholeWord) {
            if id == nil { draft.wrappedValue = ReplacementDraft() } else { editingID = nil }
        } else { draft.wrappedValue.invalid = true }
    }
    /// Marks a rule option with the glyph find-and-replace fields use for it, so the list needs no translated tag.
    private func badge(_ glyph: String, _ label: String, design: Font.Design = .default, underline: Bool = false) -> some View {
        // A tint rather than an outline: the tag is a label, not a control.
        Text(glyph).font(.system(size: 11, weight: .medium, design: design)).underline(underline).foregroundStyle(.secondary)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(.primary.opacity(0.08), in: RoundedRectangle(cornerRadius: 4)).help(label).accessibilityLabel(label)
    }
}

struct ReplacementDraft {
    /// New rules match whole words; rules saved before the option keep matching inside words until it is turned on.
    var source = "", replacement = "", regex = false, matchCase = false, wholeWord = true, invalid = false
    init() {}
    init(_ rule: WordReplacement) {
        source = rule.source; replacement = rule.replacement; regex = rule.regex; matchCase = rule.matchCase
        // Phrases already match whole words, so turning one into a pattern keeps that behavior.
        wholeWord = rule.regex ? rule.wholeWord : true
    }
    var canSave: Bool {
        regex ? !source.isEmpty : !source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !replacement.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

/// Fields for a new rule, or for an existing one in place of its row.
private struct ReplacementEditor: View {
    @Binding var draft: ReplacementDraft
    var sourceFocused: FocusState<Bool>.Binding
    let status: ReplacementStore.Status
    /// Only one editor answers Return, so the add form stays inert while a rule is edited.
    let isDefault: Bool
    let t: (String, String) -> String
    let onSave: () -> Void
    let onCancel: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: Space.sm) {
            TextField(draft.regex ? t("Pattern", "Шаблон") : t("Recognized word or phrase", "Распознанное слово или фраза"), text: $draft.source)
                .font(draft.regex ? .body.monospaced() : .body).settingsField(focus: sourceFocused)
            TextField(draft.regex ? t("Replace with, $1 for a group", "Заменить на, $1 — группа") : t("Replace with", "Заменить на"), text: $draft.replacement)
                .settingsField().onSubmit(onSave)
            // The buttons move below the options rather than wrapping the option labels.
            // Option labels never wrap: the buttons move below them, then the options stack.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: Space.md) { HStack(spacing: Space.md) { options }.fixedSize(); Spacer(minLength: 0); buttons }
                VStack(alignment: .trailing, spacing: Space.sm) {
                    HStack(spacing: Space.md) { HStack(spacing: Space.md) { options }.fixedSize(); Spacer(minLength: 0) }
                    HStack(spacing: Space.md) { buttons }
                }
                VStack(alignment: .leading, spacing: Space.sm) {
                    HStack(spacing: Space.md) { regexToggle; matchCaseToggle }.fixedSize()
                    HStack(spacing: Space.md) { wholeWordToggle.fixedSize(); Spacer(minLength: 0); buttons }
                }
                VStack(alignment: .trailing, spacing: Space.sm) {
                    VStack(alignment: .leading, spacing: Space.sm) { options }.frame(maxWidth: .infinity, alignment: .leading)
                    HStack(spacing: Space.md) { buttons }
                }
            }.toggleStyle(.checkbox).onChange(of: draft.regex) { draft.invalid = false }.onChange(of: draft.matchCase) { draft.invalid = false }
                .onChange(of: draft.wholeWord) { draft.invalid = false }
            if draft.invalid {
                Text(status == .invalidPattern
                     ? t("Invalid pattern. It must not match empty text, and $n must refer to an existing group.",
                         "Неверный шаблон. Он не должен совпадать с пустым текстом, а $n — ссылаться на существующую группу.")
                     : t("Use a unique phrase (up to 256 characters) and a replacement (up to 2,048). Maximum 500 rules.",
                         "Введите уникальную фразу до 256 символов и замену до 2048. Максимум 500 правил."))
                    .font(.callout).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true)
            }
        }
    }
    @ViewBuilder private var options: some View { regexToggle; matchCaseToggle; wholeWordToggle }
    private var regexToggle: some View { Toggle(t("Regular expression", "Регулярное выражение"), isOn: $draft.regex) }
    private var matchCaseToggle: some View { Toggle(t("Match case", "Учитывать регистр"), isOn: $draft.matchCase) }
    /// Phrases always match whole words, so the option belongs to patterns only.
    @ViewBuilder private var wholeWordToggle: some View {
        if draft.regex { Toggle(t("Whole word", "Слово целиком"), isOn: $draft.wholeWord) }
    }
    @ViewBuilder private var buttons: some View {
        if let onCancel { Button(t("Cancel", "Отмена"), action: onCancel).keyboardShortcut(.cancelAction) }
        Button(onCancel == nil ? t("Add", "Добавить") : t("Save", "Сохранить"), action: onSave)
            .keyboardShortcut(isDefault ? .defaultAction : nil)
            .disabled(!draft.canSave || status == .storageError)
    }
}
