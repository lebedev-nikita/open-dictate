import SwiftUI
import OpenDictateCore

struct SpeechLanguagePicker: View {
    @ObservedObject var preferences: Preferences
    @State private var isPresented = false

    private var selected: [SpeechLanguage] {
        SpeechLanguage.all.filter { preferences.speechLanguages.contains($0.id) }
    }
    private var summary: String {
        if selected.isEmpty { return preferences.t("Automatic", "Автоматически") }
        let names = selected.prefix(2).map(\.nativeName).joined(separator: ", ")
        return selected.count > 2 ? "\(names) +\(selected.count - 2)" : names
    }

    var body: some View {
        Button { isPresented.toggle() } label: {
            HStack(spacing: 8) {
                Text(summary).lineLimit(1).truncationMode(.tail)
                Spacer(minLength: 0)
                Image(systemName: "chevron.down").font(.caption)
            }.frame(width: 200)
        }
        .help(selected.isEmpty ? summary : selected.map(\.nativeName).joined(separator: ", "))
        .accessibilityLabel(preferences.t("Speech languages", "Языки речи"))
        .accessibilityValue(selected.isEmpty ? summary : selected.map(\.nativeName).joined(separator: ", "))
        .popover(isPresented: $isPresented, arrowEdge: .bottom) {
            SpeechLanguageList(preferences: preferences, close: { isPresented = false })
                .background(AppThemeAppearance(theme: preferences.theme))
        }
        .onChange(of: isEnabled) { _, enabled in if !enabled { isPresented = false } }
    }

    @Environment(\.isEnabled) private var isEnabled
}

struct SpeechLanguageList: View {
    @ObservedObject var preferences: Preferences
    var close: () -> Void
    @State private var query = ""
    @FocusState private var searchFocused: Bool

    private func t(_ en: String, _ ru: String) -> String { preferences.t(en, ru) }
    private var languages: [SpeechLanguage] {
        SpeechLanguage.all.filter { $0.matches(query) }.sorted {
            $0.title(russian: preferences.isRussian).localizedStandardCompare(
                $1.title(russian: preferences.isRussian)) == .orderedAscending
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(t("Speech languages", "Языки речи")).font(.headline)
            Text(t("Select the languages you speak. Changes save automatically.",
                   "Отметьте языки, на которых говорите. Выбор сохраняется автоматически."))
                .font(.callout).foregroundStyle(.primary).fixedSize(horizontal: false, vertical: true)
            VStack(alignment: .leading, spacing: 4) {
                Text(t("Search languages", "Поиск языков")).font(.caption).foregroundStyle(.primary)
                TextField("", text: $query)
                    .textFieldStyle(.roundedBorder).focused($searchFocused)
                    .accessibilityLabel(t("Search languages", "Поиск языков"))
            }
            Button { preferences.speechLanguages = [] } label: {
                HStack(spacing: 10) {
                    Image(systemName: preferences.speechLanguages.isEmpty ? "checkmark.circle.fill" : "circle")
                    Text(t("Automatic detection", "Автоопределение"))
                    Spacer()
                }.contentShape(Rectangle())
            }.buttonStyle(.plain)
                .accessibilityValue(preferences.speechLanguages.isEmpty ? t("Selected", "Выбрано") : t("Not selected", "Не выбрано"))
                .help(t("Clear language hints and detect automatically.", "Снять все отметки и определять языки автоматически."))
            Divider()
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    if languages.isEmpty {
                        Text(t("No languages found. Try another name or language code.",
                               "Языки не найдены. Попробуйте другое название или код языка."))
                            .foregroundStyle(.primary).padding(.vertical, 12)
                    }
                    ForEach(languages) { language in
                        Toggle(isOn: Binding(
                            get: { preferences.speechLanguages.contains(language.id) },
                            set: { selected in
                                if selected { preferences.speechLanguages.insert(language.id) }
                                else { preferences.speechLanguages.remove(language.id) }
                            })) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(language.title(russian: preferences.isRussian))
                                    if language.nativeName != language.title(russian: preferences.isRussian) {
                                        Text(language.nativeName).font(.caption).foregroundStyle(.primary)
                                    }
                                }
                            }.toggleStyle(.checkbox)
                            .accessibilityLabel("\(language.title(russian: preferences.isRussian)), \(language.nativeName)")
                    }
                }.padding(4).frame(maxWidth: .infinity, alignment: .leading)
            }.frame(height: 280)
            Divider()
            HStack {
                Text(preferences.speechLanguages.isEmpty ? t("Automatic detection", "Автоопределение")
                     : t("Selected: \(preferences.speechLanguages.count)", "Выбрано: \(preferences.speechLanguages.count)"))
                    .font(.caption).foregroundStyle(.primary)
                Spacer()
                Button(t("Done", "Готово"), action: close).keyboardShortcut(.defaultAction)
            }
        }.padding(16).frame(width: 340)
            .onAppear { searchFocused = true }
            .onExitCommand(perform: close)
    }
}
