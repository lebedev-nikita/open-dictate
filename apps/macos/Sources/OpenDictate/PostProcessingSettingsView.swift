import SwiftUI
import OpenDictateCore

struct PostProcessingSettingsView: View {
    @ObservedObject var preferences: Preferences
    @State private var formatsOpen = false
    private func t(_ en: String, _ ru: String) -> String { preferences.t(en, ru) }
    private var settings: PostProcessingSettings { preferences.postProcessing }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(t("Post-processing", "Постобработка")).fontWeight(.medium)
            Text(t("Applied when you use Dictation with post-processing. Set its shortcut in Keyboard shortcuts.",
                   "Применяется при диктовке с постобработкой. Назначьте ей сочетание во вкладке «Горячие клавиши»."))
                .font(.callout).fixedSize(horizontal: false, vertical: true)
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
                    ValueFormattingMenu(initial: settings.formats ?? .systemDefaults(), russian: preferences.isRussian, selectedLanguages: preferences.speechLanguages,
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
        }
    }
}

struct ValueFormattingMenu: View {
    @State private var draft: ValueFormatting
    @State private var editing: String?
    @State private var typesOpen = false
    private let defaults: ValueFormatting
    let russian: Bool
    let selectedLanguages: Set<String>
    let onCancel: () -> Void
    let onSave: (ValueFormatting) -> Void
    init(initial: ValueFormatting, russian: Bool, selectedLanguages: Set<String>, defaults: ValueFormatting = .systemDefaults(),
         onCancel: @escaping () -> Void, onSave: @escaping (ValueFormatting) -> Void) {
        self._draft = State(initialValue: initial); self.defaults = defaults; self.russian = russian; self.selectedLanguages = selectedLanguages
        self.onCancel = onCancel; self.onSave = onSave
    }
    private func t(_ en: String, _ ru: String) -> String { russian ? ru : en }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            if let editing {
                ValueFormatRuleEditor(initial: draft.rules.first { $0.id == editing } ?? .init(kind: .integer, example: draft[.integer].example),
                    existingRules: draft.rules, replacing: editing, russian: russian, selectedLanguages: selectedLanguages, defaults: draft,
                    canSave: { rule in var next = draft; next.setRule(rule, replacing: editing); return next.isValid },
                    onCancel: { self.editing = nil }, onSave: { rule in
                        draft.setRule(rule, replacing: editing); self.editing = nil
                    }).id(editing)
            } else {
                Text(t("Format values", "Отформатировать значения")).font(.headline)
                HStack {
                    DisclosureGroup(t("Value types", "Типы значений"), isExpanded: $typesOpen) {
                        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], alignment: .leading) {
                            ForEach(ValueKind.allCases, id: \.self) { kind in
                                Toggle(kind.title(russian: russian), isOn: Binding(get: { draft[kind].enabled }, set: { draft[kind].enabled = $0 }))
                                    .toggleStyle(.checkbox)
                            }
                        }.padding(.top, 8)
                    }
                    Spacer(minLength: 12)
                    Button { editing = "new" } label: { Label(t("Add rule", "Добавить правило"), systemImage: "plus") }
                        .disabled(draft.rules.count >= 64)
                }
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        if draft.rules.isEmpty {
                            Text(t("Formats follow the dictation language and regional preferences. Add a rule to change a format.",
                                   "Форматы выбираются по языку диктовки и региональным настройкам. Чтобы изменить формат, добавьте правило."))
                                .foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        }
                        ForEach(ValueKind.allCases, id: \.self) { kind in
                            let rules = draft.sortedRules(russian: russian).filter { $0.kind == kind }
                            if !rules.isEmpty {
                                Text(kind.title(russian: russian)).font(.subheadline.weight(.semibold)).padding(.top, 6)
                                ForEach(rules) { rule in
                                    HStack(spacing: 12) {
                                        Button { editing = rule.id } label: {
                                            VStack(alignment: .leading, spacing: 4) {
                                                Text(rule.languageTitle(russian: russian))
                                                Text(rule.example).font(.callout).foregroundStyle(.primary)
                                            }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                                        }.buttonStyle(.plain)
                                        Button { draft.removeRule(id: rule.id) } label: { Image(systemName: "trash") }
                                            .buttonStyle(.borderless)
                                            .accessibilityLabel(t("Delete rule: ", "Удалить правило: ") + kind.title(russian: russian) + ", " + rule.languageTitle(russian: russian))
                                    }.padding(.vertical, 4)
                                }
                                Divider()
                            }
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading).padding(.trailing, 4)
                }.frame(height: 350)
                HStack {
                    Button(t("System defaults", "Как в системе")) { draft = defaults }
                    Spacer()
                    Button(t("Cancel", "Отмена"), action: onCancel).keyboardShortcut(.cancelAction)
                    Button(t("Done", "Готово")) { onSave(draft) }.keyboardShortcut(.defaultAction).disabled(!draft.isValid)
                }
            }
        }.padding(20).frame(width: 440)
    }
}

struct ValueFormatRuleEditor: View {
    @State private var rule: ValueFormatRule
    let existingRules: [ValueFormatRule]
    let replacing: String
    let russian: Bool
    let selectedLanguages: Set<String>
    let defaults: ValueFormatting
    let canSave: (ValueFormatRule) -> Bool
    let onCancel: () -> Void
    let onSave: (ValueFormatRule) -> Void
    init(initial: ValueFormatRule, existingRules: [ValueFormatRule], replacing: String, russian: Bool, selectedLanguages: Set<String>, defaults: ValueFormatting, canSave: @escaping (ValueFormatRule) -> Bool = { _ in true },
         onCancel: @escaping () -> Void, onSave: @escaping (ValueFormatRule) -> Void) {
        _rule = State(initialValue: initial); self.existingRules = existingRules; self.replacing = replacing; self.russian = russian; self.selectedLanguages = selectedLanguages
        self.defaults = defaults; self.canSave = canSave; self.onCancel = onCancel; self.onSave = onSave
    }
    private func t(_ en: String, _ ru: String) -> String { russian ? ru : en }
    var languageOptions: [SpeechLanguage] {
        SpeechLanguage.all.filter { selectedLanguages.contains($0.id) }.sorted {
            $0.title(russian: russian).compare($1.title(russian: russian), locale: Locale(identifier: russian ? "ru" : "en")) == .orderedAscending
        }
    }
    var hasAvailableLanguage: Bool { rule.language.map { selectedLanguages.contains($0) } ?? true }
    private var duplicate: Bool { existingRules.contains { $0.id == rule.id && $0.id != replacing } }
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(t("Custom format", "Свой формат")).font(.headline)
            VStack(alignment: .leading, spacing: 12) {
                Picker(t("Value type", "Тип значения"), selection: $rule.kind) {
                    ForEach(ValueKind.allCases, id: \.self) { kind in Text(kind.title(russian: russian)).tag(kind) }
                }.onChange(of: rule.kind) { _, kind in rule.example = defaults[kind].example }
                Picker(t("Language", "Язык"), selection: Binding(get: { hasAvailableLanguage ? rule.language ?? "*" : "" }, set: { rule.language = $0 == "*" ? nil : $0 })) {
                    if !hasAvailableLanguage { Text(t("Choose language", "Выберите язык")).tag("").disabled(true) }
                    Text(t("All languages", "Все языки")).tag("*")
                    ForEach(languageOptions) { language in
                        Text(language.title(russian: russian)).tag(language.id)
                    }
                }.accessibilityIdentifier("valueRuleLanguage")
            }
            VStack(alignment: .leading, spacing: 6) {
                Text(t("Source value", "Исходное значение")).font(.subheadline.weight(.medium))
                Text(rule.kind.source(russian: russian)).fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
            }
            VStack(alignment: .leading, spacing: 6) {
                Text(t("Result in your format", "Результат в нужном формате")).font(.subheadline.weight(.medium))
                HStack {
                    TextField(t("Result in your format", "Результат в нужном формате"), text: $rule.example).textFieldStyle(.roundedBorder)
                        .accessibilityIdentifier("valueRuleExample")
                    Menu {
                        ForEach(rule.kind.examples, id: \.self) { example in Button(example) { rule.example = example } }
                    } label: { Image(systemName: "chevron.down") }.menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
                        .accessibilityLabel(t("Choose format", "Выбрать формат"))
                }
                if !rule.isValid { Text(t("Use an example with digits, at most 96 characters and no line breaks.", "Образец должен содержать цифры, не более 96 символов и без переноса строк.")).font(.caption).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true) }
                if rule.isValid && !canSave(rule) { Text(t("Shorten the example or remove unused rules.", "Сократите образец или удалите ненужные правила.")).font(.caption).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true) }
                if duplicate { Text(t("A rule already exists for this type and language. Edit that rule instead.", "Правило для этого типа и языка уже есть. Отредактируйте его.")).font(.caption).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true) }
            }
            HStack {
                Spacer()
                Button(t("Cancel", "Отмена"), action: onCancel).keyboardShortcut(.cancelAction)
                Button(t("Save rule", "Сохранить правило")) { onSave(rule) }.keyboardShortcut(.defaultAction).disabled(!hasAvailableLanguage || !rule.isValid || duplicate || !canSave(rule))
            }.padding(.top, 6)
        }
    }
}
