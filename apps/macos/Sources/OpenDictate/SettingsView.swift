import SwiftUI
import OpenDictateCore

struct SettingsView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var preferences: Preferences
    @ObservedObject var history: HistoryStore
    @ObservedObject var replacements: ReplacementStore
    @State private var selection: String
    @State private var apiKey = ""
    @State private var query = ""
    @State private var confirmClear = false
    @State private var confirmDeleteKey = false

    init(model: AppModel, preferences: Preferences, history: HistoryStore, replacements: ReplacementStore, page: String = "dictation") {
        self.model = model; self.preferences = preferences; self.history = history; self.replacements = replacements
        _selection = State(initialValue: page)
    }

    private func t(_ en: String, _ ru: String) -> String { preferences.t(en, ru) }
    /// Clears the 28 pt title bar with the window controls, then the page's own top margin.
    static let titleBarClearance: CGFloat = 28 + Space.lg

    var body: some View {
        HStack(spacing: 0) {
            sidebar
            Divider()
            VStack(alignment: .leading, spacing: 0) {
                // The dictionary editor scrolls itself, so its page fills the window instead of nesting scroll views.
                if selection == "dictionary" {
                    page { dictionaryPage }.frame(maxHeight: .infinity, alignment: .top)
                } else {
                    ScrollView {
                        page {
                            switch selection {
                            case "replacements": ReplacementSettingsView(store: model.replacements, preferences: preferences)
                            case "history": historyPage
                            case "shortcuts": ShortcutSettingsView(model: model, preferences: preferences)
                            case "general": generalPage
                            default: dictationPage
                            }
                        }
                    }
                }
                if !model.message.isEmpty {
                    Divider()
                    HStack(alignment: .top, spacing: Space.sm) {
                        Image(systemName: "exclamationmark.circle").foregroundStyle(.secondary)
                        Text(model.message).font(.callout).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                        Button(action: model.clearMessage) { Image(systemName: "xmark") }
                            .buttonStyle(.borderless).help(t("Dismiss", "Закрыть сообщение"))
                    }.padding(.horizontal, Space.xl).padding(.vertical, Space.md).background(Surface.card)
                }
            }.frame(maxWidth: .infinity, maxHeight: .infinity).background(Surface.page)
        }
        // The window extends content under its transparent title bar; padding keeps clear of the window controls.
        .ignoresSafeArea()
        .frame(minWidth: 740, minHeight: 590)
        .background(AppThemeAppearance(theme: preferences.theme))
        .onAppear { model.refreshPermissions() }
        .alert(t("Delete all history?", "Удалить всю историю?"), isPresented: $confirmClear) {
            Button(t("Delete", "Удалить"), role: .destructive) { history.clear() }
            Button(t("Cancel", "Отмена"), role: .cancel) {}
        } message: { Text(t("This removes every saved transcript from this Mac.", "Все сохранённые расшифровки будут удалены с этого Mac.")) }
        .alert(t("Remove API key?", "Удалить ключ API?"), isPresented: $confirmDeleteKey) {
            Button(t("Remove", "Удалить"), role: .destructive) { model.deleteKey() }
            Button(t("Cancel", "Отмена"), role: .cancel) {}
        }
    }

    private func page<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: Space.lg) { content() }
            .frame(maxWidth: 600, alignment: .leading)
            .padding(.horizontal, Space.xl).padding(.top, Self.titleBarClearance).padding(.bottom, Space.xl)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: Space.xs) {
                Image(systemName: "mic.fill").font(.system(size: 12, weight: .semibold)).foregroundStyle(.white)
                    .frame(width: 24, height: 24).background(Color.accentColor, in: RoundedRectangle(cornerRadius: 6))
                Text("OpenDictate").font(.system(size: 13, weight: .semibold))
            }.padding(.horizontal, Space.xs).padding(.bottom, Space.lg)
            VStack(spacing: 2) {
                navigation("dictation", t("Dictation", "Диктовка"), "waveform")
                navigation("dictionary", t("Dictionary", "Словарь"), "book.closed", count: DictionaryTerms.normalize(preferences.dictionary).count)
                navigation("replacements", t("Replacements", "Автозамена"), "arrow.left.arrow.right", count: replacements.document.rules.count)
                navigation("history", t("History", "История"), "clock")
                navigation("shortcuts", t("Keyboard shortcuts", "Горячие клавиши"), "keyboard")
                navigation("general", t("Settings", "Настройки"), "slider.horizontal.3")
            }
            Spacer(minLength: Space.lg)
            VStack(alignment: .leading, spacing: Space.xs) {
                Text(preferences.shortcuts.dictate.isEnabled ? preferences.shortcutLabel : t("Not set", "Не задано"))
                    .font(.system(size: 12, weight: .medium)).monospacedDigit()
                    .padding(.horizontal, Space.xs).padding(.vertical, Space.xxs)
                    .background(Surface.card, in: RoundedRectangle(cornerRadius: 6))
                    .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(Surface.hairline))
                Text(preferences.shortcuts.dictate.isEnabled ? t("Press once to start.\nPress again to finish.", "Нажмите, чтобы начать.\nЕщё раз — завершить.") : t("Start dictation from the menu.", "Начните диктовку из меню."))
                    .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }.padding(.horizontal, Space.xs)
            Divider().padding(.vertical, Space.md)
            VStack(alignment: .leading, spacing: Space.xs) {
                SettingsSyncControl(sync: model.driveSync, preferences: preferences)
                HStack {
                    Text("macOS · \(Bundle.main.object(forInfoDictionaryKey: "OpenDictateReleaseVersion") as? String ?? Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.2.0-rc.3")")
                        .font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    themeSwitcher
                }
            }.padding(.horizontal, Space.xs)
        }.padding(.horizontal, Space.xs).padding(.top, Self.titleBarClearance).padding(.bottom, Space.md)
            .frame(width: 220).background(Surface.sidebar)
    }

    private var themeSwitcher: some View {
        Menu {
            Picker(t("App theme", "Тема приложения"), selection: $preferences.theme) {
                ForEach(AppTheme.allCases, id: \.self) { theme in
                    Label(theme.title(russian: preferences.isRussian), systemImage: theme.symbol).tag(theme)
                }
            }
            .pickerStyle(.inline)
        } label: {
            Image(systemName: preferences.theme.symbol).frame(width: 24, height: 24)
        }
        .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
        .help(t("Change app theme", "Изменить тему приложения"))
        .accessibilityLabel(t("Change app theme", "Изменить тему приложения"))
        .accessibilityValue(preferences.theme.title(russian: preferences.isRussian))
    }

    private func navigation(_ id: String, _ title: String, _ symbol: String, count: Int? = nil) -> some View {
        let selected = selection == id
        return Button { selection = id } label: {
            HStack(spacing: Space.xs) {
                Image(systemName: symbol).frame(width: 18)
                    .foregroundStyle(selected ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(.secondary))
                Text(title).lineLimit(1)
                Spacer(minLength: 0)
                if let count, count > 0 {
                    Text("\(count)").font(.caption).monospacedDigit().foregroundStyle(.secondary)
                }
            }.font(.system(size: 13, weight: selected ? .medium : .regular))
                .padding(.horizontal, Space.xs).padding(.vertical, 6)
                .background(selected ? Surface.selection : Color.clear, in: RoundedRectangle(cornerRadius: 6))
                .contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityAddTraits(selected ? .isSelected : [])
            .accessibilityLabel(count.map { "\(title), \($0)" } ?? title)
    }

    private var dictationPage: some View {
        VStack(alignment: .leading, spacing: Space.lg) {
            PageHeader(title: t("Dictation", "Диктовка"))

            SettingsSection(title: t("Access", "Доступ")) {
                VStack(alignment: .leading, spacing: Space.xs) {
                    HStack {
                        Text(t("OpenAI API key", "Ключ API OpenAI"))
                        Spacer()
                        if model.hasKey { StatusLabel(title: t("Saved", "Сохранён")) }
                    }
                    HStack(spacing: Space.xs) {
                        SecureField(model.hasKey ? t("Enter a replacement key", "Введите новый ключ") : "sk-…", text: $apiKey)
                            .settingsField().onSubmit(saveKey)
                            .accessibilityLabel(t("OpenAI API key", "Ключ API OpenAI"))
                        Button { model.copyKey(apiKey) } label: { Image(systemName: "doc.on.doc") }
                            .help(t("Copy API key", "Скопировать ключ API"))
                            .accessibilityLabel(t("Copy API key", "Скопировать ключ API"))
                            .disabled(!model.hasKey && apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        Button(t("Save", "Сохранить"), action: saveKey).disabled(apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.isActive)
                    }.controlSize(.large)
                    Text(t("Stored in your Mac's Keychain. Audio goes directly to OpenAI.",
                           "Хранится в Keychain. Звук отправляется напрямую в OpenAI."))
                        .font(.caption).foregroundStyle(.secondary)
                }.padding(Space.md)
                RowDivider()
                permission(t("Microphone", "Микрофон"), granted: model.microphoneAllowed, action: model.requestMicrophone)
                RowDivider()
                permission(t("Accessibility", "Универсальный доступ"), granted: model.accessibilityAllowed, action: model.requestAccessibility)
            }

            SettingsSection(title: t("Transcription", "Распознавание")) {
                SettingsRow(title: t("Mode", "Режим"), detail: preferences.mode == .live
                            ? t("Recognition streams as you speak; text is pasted when you finish.", "Речь распознаётся сразу; текст вставляется после остановки.")
                            : t("Text arrives when you finish. Best when accuracy matters.", "Текст появляется после записи. Когда важна точность.")) {
                    Picker(t("Transcription mode", "Режим распознавания"), selection: $preferences.mode) {
                        Text("Live").tag(DictationMode.live)
                        Text("Accurate").tag(DictationMode.accurate)
                    }.pickerStyle(.segmented).labelsHidden().fixedSize().disabled(model.isActive)
                }
                RowDivider()
                modelField(t("Live model", "Модель Live"), $preferences.liveModel)
                RowDivider()
                modelField(t("Accurate model", "Модель Accurate"), $preferences.accurateModel)
                RowDivider()
                SettingsRow(title: t("Text model", "Текстовая модель")) {
                    Picker(t("Text model", "Текстовая модель"), selection: $preferences.textProcessingSelection) {
                        ForEach(TextProcessingModel.defaults, id: \.self) { id in
                            Text(TextProcessingModel.title(id)).tag(id)
                        }
                    }.labelsHidden().fixedSize().disabled(model.isActive)
                }
                RowDivider()
                SettingsRow(title: t("Speech languages", "Языки речи")) {
                    SpeechLanguagePicker(preferences: preferences).disabled(model.isActive)
                }
            }

            PostProcessingSettingsView(preferences: preferences)
        }
    }

    private func modelField(_ title: String, _ binding: Binding<String>) -> some View {
        SettingsRow(title: title) {
            Text(binding.wrappedValue).font(.system(.callout, design: .monospaced))
                .foregroundStyle(.secondary).textSelection(.enabled)
        }
    }

    private func saveKey() {
        if model.saveKey(apiKey) { apiKey = "" }
    }
    private func permission(_ title: String, granted: Bool, action: @escaping () -> Void) -> some View {
        SettingsRow(title: title) {
            if granted { StatusLabel(title: t("Allowed", "Разрешён")) }
            else { Button(t("Allow…", "Разрешить…"), action: action) }
        }
    }
    private var dictionaryPage: some View {
        VStack(alignment: .leading, spacing: Space.lg) {
            PageHeader(title: t("Dictionary", "Словарь"),
                       subtitle: t("Names, products and terms you want OpenAI to recognize. One per line.",
                                   "Имена, продукты и термины для распознавания. По одному на строку.")) {
                Button(t("Clean up", "Упорядочить")) { preferences.dictionary = DictionaryTerms.normalize(preferences.dictionary).joined(separator: "\n") }
                    .help(t("Remove duplicates and empty lines", "Убрать повторы и пустые строки"))
            }
            TextEditor(text: $preferences.dictionary).font(.body)
                .scrollContentBackground(.hidden).padding(Space.xs)
                .frame(minHeight: 200, maxHeight: .infinity)
                // The editor is the page's content, so it takes the card surface rather than an inset field.
                .background(Surface.card, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Surface.fieldBorder))
                .accessibilityLabel(t("Dictionary terms", "Слова в словаре"))
        }.frame(maxHeight: .infinity, alignment: .top)
    }

    private var displayedHistory: [HistoryEntry] {
        return history.entries.filter { HistorySearch.matches($0.text, query: query) }
    }
    private var historyPage: some View {
        VStack(alignment: .leading, spacing: Space.lg) {
            PageHeader(title: t("History", "История"))
            SettingsCard {
                SettingsRow(title: t("Save history", "Сохранять историю")) {
                    Toggle(t("Save history", "Сохранять историю"), isOn: $preferences.saveHistory)
                        .labelsHidden().toggleStyle(.switch).controlSize(.small)
                }
            }
            if let error = history.error { Text(error).font(.callout).foregroundStyle(.secondary) }
            SettingsCard {
                // Search and bulk deletion head the list they act on, so the field sits on a card like every input.
                HStack(spacing: Space.xs) {
                    TextField(t("Search history", "Поиск по истории"), text: $query).settingsField()
                    Button(t("Delete all…", "Удалить всё…"), role: .destructive) { confirmClear = true }.disabled(history.entries.isEmpty)
                }.controlSize(.large).padding(Space.md)
                RowDivider()
                if displayedHistory.isEmpty {
                    ContentUnavailableView {
                        Label(history.entries.isEmpty ? t("No dictations yet", "Пока нет диктовок") : t("No matches", "Ничего не найдено"), systemImage: "text.alignleft")
                    } description: {
                        Text(history.entries.isEmpty ? t("Your completed dictations will appear here.", "Завершённые диктовки появятся здесь.") : t("Try another search.", "Попробуйте другой запрос."))
                    }.frame(minHeight: 200)
                } else {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(displayedHistory.enumerated()), id: \.element.id) { index, entry in
                            if index > 0 { RowDivider() }
                            HistoryEntryView(entry: entry, russian: preferences.isRussian,
                                onCopy: { model.copy(entry.text) },
                                onDelete: { history.delete(entry.id) })
                        }
                    }
                }
            }
        }
    }

    private var generalPage: some View {
        VStack(alignment: .leading, spacing: Space.lg) {
            PageHeader(title: t("Settings", "Настройки"))
            SettingsSection(title: t("General", "Общие")) {
                settingToggle(t("Launch at login", "Запускать при входе"), Binding(get: { model.loginEnabled }, set: model.setLoginEnabled))
                RowDivider()
                SettingsRow(title: t("Interface language", "Язык интерфейса")) {
                    Picker(t("Interface language", "Язык интерфейса"), selection: $preferences.interfaceLanguage) {
                        Text(t("System", "Системный")).tag("auto"); Text("English").tag("en"); Text("Русский").tag("ru")
                    }.labelsHidden().fixedSize()
                }
                RowDivider()
                SettingsRow(title: t("Response timeout", "Ожидание ответа")) {
                    Picker(t("Response timeout", "Ожидание ответа"), selection: $preferences.timeout) {
                        ForEach([30, 60, 120], id: \.self) { seconds in Text(t("\(seconds) seconds", "\(seconds) секунд")).tag(seconds) }
                    }.labelsHidden().fixedSize()
                }
            }
            SettingsSection(title: t("Recording indicator", "Индикатор записи"),
                            footer: t("Click the indicator or press your shortcut to finish.",
                                      "Нажмите на индикатор или сочетание клавиш, чтобы завершить.")) {
                settingToggle(t("Show recording status", "Показывать состояние записи"), $preferences.showStatus)
                RowDivider()
                SettingsRow(title: t("Style", "Вид")) {
                    Picker(t("Recording indicator", "Индикатор записи"), selection: $preferences.indicatorStyle) {
                        ForEach(RecordingIndicatorStyle.allCases, id: \.self) { style in
                            Text(style.title(russian: preferences.isRussian)).tag(style)
                        }
                    }.labelsHidden().fixedSize()
                        .accessibilityIdentifier("recordingIndicatorStyle")
                }.disabled(!preferences.showStatus)
            }
            SettingsSection(title: t("Excluded apps", "Исключённые приложения"),
                            footer: t("Dictation and voice editing will not start in these apps.", "Диктовка и голосовая правка не запускаются в этих приложениях.")) {
                ForEach(preferences.excludedApps, id: \.self) { id in
                    HStack {
                        Text(id).font(.callout).textSelection(.enabled); Spacer()
                        Button { preferences.excludedApps.removeAll { $0 == id } } label: { Image(systemName: "minus.circle") }
                            .buttonStyle(.borderless).help(t("Remove exclusion", "Убрать исключение"))
                    }.settingsRowPadding()
                    RowDivider()
                }
                Button(t("Add application…", "Добавить приложение…"), action: addExcludedApp).settingsRowPadding()
            }
            SettingsSection(title: t("Integrations", "Интеграции")) {
                GoogleDriveSettingsView(sync: model.driveSync, preferences: preferences).padding(Space.md)
                RowDivider()
                ChatGPTSettingsView(connection: model.chatGPT, hasKey: model.hasKey, active: model.isActive, russian: preferences.isRussian,
                    connect: { model.chatGPT.connect(hasKey: { model.hasKey }) }).padding(Space.md)
            }
            SettingsSection(title: t("Privacy", "Приватность")) {
                Text(t("No backend or analytics. Audio stays in memory only. Password fields are excluded. Insertion briefly uses the clipboard.",
                       "Без сервера и аналитики. Звук хранится только в памяти. Поля паролей исключены. Для вставки ненадолго используется буфер обмена."))
                    .font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    .padding(Space.md)
                RowDivider()
                HStack {
                    Link(t("Privacy policy", "Политика приватности"), destination: URL(string: "https://github.com/OpenDictate/open-dictate/blob/main/PRIVACY.md")!)
                    Spacer()
                    Button(t("Remove API key…", "Удалить ключ API…"), role: .destructive) { confirmDeleteKey = true }
                        .disabled(!model.hasKey || model.isActive)
                }.settingsRowPadding()
            }
        }
    }

    private func addExcludedApp() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = false; panel.allowsMultipleSelection = false
        panel.allowedContentTypes = [.applicationBundle]
        panel.directoryURL = URL(fileURLWithPath: "/Applications")
        if panel.runModal() == .OK, let url = panel.url, let id = Bundle(url: url)?.bundleIdentifier,
           !preferences.excludedApps.contains(id) { preferences.excludedApps.append(id) }
    }

    private func settingToggle(_ label: String, _ binding: Binding<Bool>) -> some View {
        SettingsRow(title: label) {
            Toggle(label, isOn: binding).labelsHidden().toggleStyle(.switch).controlSize(.small).accessibilityLabel(label)
        }
    }
}
