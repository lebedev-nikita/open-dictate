import SwiftUI
import OpenDictateCore

struct SettingsView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var preferences: Preferences
    @ObservedObject var history: HistoryStore
    @ObservedObject var replacements: ReplacementStore
    @State private var selection = "dictation"
    @State private var apiKey = ""
    @State private var query = ""
    @State private var confirmClear = false
    @State private var confirmDeleteKey = false

    private func t(_ en: String, _ ru: String) -> String { preferences.t(en, ru) }

    var body: some View {
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 24) {
                HStack(spacing: 9) {
                    Image(systemName: "mic.fill").font(.system(size: 19))
                    Text("OpenDictate").font(.system(size: 15, weight: .semibold))
                }.padding(.top, 9)
                VStack(spacing: 5) {
                    navigation("dictation", t("Dictation", "Диктовка"), "waveform")
                    navigation("dictionary", t("Dictionary", "Словарь") + " (\(DictionaryTerms.normalize(preferences.dictionary).count))", "book.closed")
                    navigation("replacements", t("Replacements", "Автозамена") + " (\(replacements.document.rules.count))", "arrow.left.arrow.right")
                    navigation("history", t("History", "История"), "clock")
                    navigation("general", t("Settings", "Настройки"), "slider.horizontal.3")
                }
                Spacer()
                VStack(alignment: .leading, spacing: 7) {
                    Text(preferences.shortcuts.dictate.isEnabled ? preferences.shortcutLabel : t("Not set", "Не задано")).font(.system(.body, design: .monospaced)).foregroundStyle(.primary)
                    Text(preferences.shortcuts.dictate.isEnabled ? t("Press once to start.\nPress again to finish.", "Нажмите, чтобы начать.\nЕщё раз — завершить.") : t("Start dictation from the menu.", "Начните диктовку из меню."))
                        .font(.caption).foregroundStyle(.secondary).lineSpacing(3)
                }
                Divider()
                Text("macOS · \(Bundle.main.object(forInfoDictionaryKey: "OpenDictateReleaseVersion") as? String ?? Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.2.0-rc.3")")
                    .font(.caption).foregroundStyle(.secondary)
            }.padding(20).frame(width: 220).background(Color(nsColor: .controlBackgroundColor))
            Divider()
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .settingsControlCenter, spacing: 16) {
                    Spacer()
                    SettingsSyncControl(sync: model.driveSync, preferences: preferences)
                    themeSwitcher
                }.padding(.horizontal, 28).padding(.top, 16)
                ScrollView {
                    VStack(alignment: .leading, spacing: 24) {
                        switch selection {
                        case "dictionary": dictionaryPage
                        case "replacements": ReplacementSettingsView(store: model.replacements, preferences: preferences)
                        case "history": historyPage
                        case "general": generalPage
                        default: dictationPage
                        }
                    }.padding(28).frame(maxWidth: .infinity, alignment: .leading)
                }
                if !model.message.isEmpty {
                    Divider()
                    HStack(alignment: .top, spacing: 12) {
                        Text(model.message).font(.callout).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                        Button(action: model.clearMessage) { Image(systemName: "xmark") }
                            .buttonStyle(.plain).help(t("Dismiss", "Закрыть сообщение"))
                    }.padding(16).background(Color(nsColor: .controlBackgroundColor))
                }
            }.frame(maxWidth: .infinity, maxHeight: .infinity).background(Color(nsColor: .windowBackgroundColor))
        }
        .frame(minWidth: 740, minHeight: 590)
        .preferredColorScheme(preferences.theme.colorScheme)
        .onAppear { model.refreshPermissions() }
        .alert(t("Delete all history?", "Удалить всю историю?"), isPresented: $confirmClear) {
            Button(t("Delete", "Удалить"), role: .destructive) { model.cancelSearch(); history.clear() }
            Button(t("Cancel", "Отмена"), role: .cancel) {}
        } message: { Text(t("This removes every saved transcript from this Mac.", "Все сохранённые расшифровки будут удалены с этого Mac.")) }
        .alert(t("Remove API key?", "Удалить ключ API?"), isPresented: $confirmDeleteKey) {
            Button(t("Remove", "Удалить"), role: .destructive) { model.deleteKey() }
            Button(t("Cancel", "Отмена"), role: .cancel) {}
        }
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

    private func navigation(_ id: String, _ title: String, _ symbol: String) -> some View {
        Button { selection = id } label: {
            HStack(spacing: 10) {
                Image(systemName: symbol).frame(width: 18)
                Text(title).lineLimit(1)
                Spacer(minLength: 0)
            }.font(.system(size: 13, weight: selection == id ? .medium : .regular))
                .padding(.horizontal, 12).padding(.vertical, 10)
                .background(selection == id ? Color.primary.opacity(0.09) : Color.clear, in: RoundedRectangle(cornerRadius: 7))
                .contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityAddTraits(selection == id ? .isSelected : [])
    }

    private func heading(_ title: String, _ description: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.system(size: 27, weight: .semibold))
            Text(description).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
    }

    private var dictationPage: some View {
        VStack(alignment: .leading, spacing: 25) {
            heading(t("Speak. Keep your flow.", "Говорите. Не отвлекайтесь."),
                    t("Your words appear where you're typing.", "Ваши слова появятся там, где вы печатаете."))

            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text(t("OpenAI API key", "Ключ API OpenAI")).fontWeight(.medium)
                    Spacer()
                    if model.hasKey { Label(t("Saved", "Сохранён"), systemImage: "checkmark").font(.caption).foregroundStyle(.secondary) }
                }
                HStack(spacing: 10) {
                    SecureField(model.hasKey ? t("Enter a replacement key", "Введите новый ключ") : "sk-…", text: $apiKey)
                        .textFieldStyle(.roundedBorder).onSubmit(saveKey)
                        .accessibilityLabel(t("OpenAI API key", "Ключ API OpenAI"))
                    Button { model.copyKey(apiKey) } label: { Image(systemName: "doc.on.doc") }
                        .help(t("Copy API key", "Скопировать ключ API"))
                        .accessibilityLabel(t("Copy API key", "Скопировать ключ API"))
                        .disabled(!model.hasKey && apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    Button(t("Save", "Сохранить"), action: saveKey).disabled(apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.isActive)
                }
                Text(t("Stored in your Mac's Keychain. Audio goes directly to OpenAI.",
                       "Хранится в Keychain. Звук отправляется напрямую в OpenAI."))
                    .font(.caption).foregroundStyle(.secondary)
            }

            VStack(spacing: 0) {
                permission(t("Microphone", "Микрофон"), granted: model.microphoneAllowed, action: model.requestMicrophone)
                Divider().padding(.vertical, 12)
                permission(t("Accessibility", "Универсальный доступ"), granted: model.accessibilityAllowed, action: model.requestAccessibility)
            }

            Divider()
            VStack(alignment: .leading, spacing: 12) {
                Text(t("Transcription", "Распознавание")).fontWeight(.medium)
                Picker(t("Transcription mode", "Режим распознавания"), selection: $preferences.mode) {
                    Text("Live").tag(DictationMode.live)
                    Text("Accurate").tag(DictationMode.accurate)
                }.pickerStyle(.segmented).labelsHidden().disabled(model.isActive)
                Text(preferences.mode == .live
                     ? t("Recognition streams as you speak; text is pasted when you finish.", "Речь распознаётся сразу; текст вставляется после остановки.")
                     : t("Text arrives when you finish. Best when accuracy matters.", "Текст появляется после записи. Когда важна точность."))
                    .font(.callout).foregroundStyle(.secondary)
            }
            modelField(t("Live model", "Модель Live"), $preferences.liveModel)
            modelField(t("Accurate model", "Модель Accurate"), $preferences.accurateModel)
            if preferences.mode == .accurate {
                VStack(alignment: .leading, spacing: 6) {
                    settingToggle(t("Correct punctuation", "Исправлять пунктуацию"), $preferences.accuratePunctuationEnabled)
                    Text(t("Via OpenAI. Only on this device.", "Через OpenAI. Только на этом устройстве."))
                        .font(.caption).foregroundStyle(.secondary)
                }.disabled(model.isActive)
            }
            HStack {
                Text(t("Speech languages", "Языки речи"))
                Spacer()
                SpeechLanguagePicker(preferences: preferences).disabled(model.isActive)
            }
            Divider()
            VStack(alignment: .leading, spacing: 10) {
                shortcutRow(t("Start / finish dictation", "Начать / завершить диктовку"), preferences.shortcutLabel)
                shortcutRow(t("Edit selected text by voice", "Изменить выделение голосом"), preferences.editShortcutLabel)
                shortcutRow(t("Cancel dictation", "Отменить диктовку"), "Esc")
                Text(t("For voice edits, select text first. With no selection, the whole field is edited.",
                       "Для голосовой правки выделите текст. Без выделения изменится всё поле."))
                    .font(.caption).foregroundStyle(.secondary).padding(.top, 3)
            }
        }
    }

    private func modelField(_ title: String, _ binding: Binding<String>) -> some View {
        HStack {
            Text(title); Spacer()
            Text(binding.wrappedValue).font(.system(.callout, design: .monospaced))
                .foregroundStyle(.secondary).textSelection(.enabled)
        }
    }

    private func saveKey() {
        if model.saveKey(apiKey) { apiKey = "" }
    }
    private func permission(_ title: String, granted: Bool, action: @escaping () -> Void) -> some View {
        HStack {
            Text(title)
            Spacer()
            if granted { Label(t("Allowed", "Разрешён"), systemImage: "checkmark").foregroundStyle(.secondary).font(.callout) }
            else { Button(t("Allow…", "Разрешить…"), action: action) }
        }
    }
    private func shortcutRow(_ label: String, _ key: String) -> some View {
        HStack {
            Text(label).font(.callout)
            Spacer()
            Text(key).font(.system(.callout, design: .monospaced)).foregroundStyle(.secondary)
        }
    }

    private var dictionaryPage: some View {
        VStack(alignment: .leading, spacing: 20) {
            heading(t("The words you use.", "Ваши слова."),
                    t("Names, products and terms you want OpenAI to recognize. One per line.",
                      "Имена, продукты и термины для распознавания. По одному на строку."))
            TextEditor(text: $preferences.dictionary).font(.body)
                .scrollContentBackground(.hidden).padding(10)
                .frame(minHeight: 290).background(Color(nsColor: .textBackgroundColor), in: RoundedRectangle(cornerRadius: 8))
                .accessibilityLabel(t("Dictionary terms", "Слова в словаре"))
            HStack {
                Text(t("\(DictionaryTerms.normalize(preferences.dictionary).count) \(DictionaryTerms.normalize(preferences.dictionary).count == 1 ? "term" : "terms") · saved automatically", "Слов в словаре: \(DictionaryTerms.normalize(preferences.dictionary).count) · сохранено автоматически"))
                    .font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button(t("Clean up", "Упорядочить")) { preferences.dictionary = DictionaryTerms.normalize(preferences.dictionary).joined(separator: "\n") }
            }
            Text(t("You can also add a selection from the menu bar. The dictionary is sent to OpenAI only during transcription.",
                   "Выделенный текст можно добавить из строки меню. Словарь отправляется в OpenAI только при распознавании."))
                .font(.callout).foregroundStyle(.secondary)
        }
    }

    private var displayedHistory: [HistoryEntry] {
        if let ids = model.searchMatches { return ids.compactMap { id in history.entries.first { $0.id == id } } }
        return history.entries.filter { HistorySearch.matches($0.text, query: query) }
    }
    private var historyPage: some View {
        VStack(alignment: .leading, spacing: 20) {
            heading(t("Your recent words.", "Недавние слова."),
                    t("Saved on this Mac. Copy, search or delete them anytime.", "Хранятся на этом Mac. Можно скопировать, найти или удалить."))
            HStack {
                TextField(t("Search history", "Поиск по истории"), text: $query).textFieldStyle(.roundedBorder)
                    .onChange(of: query) { model.cancelSearch() }
                if model.isSearching { Button(t("Cancel", "Отмена"), action: model.cancelSearch) }
                else { Button(t("AI search", "AI-поиск")) { model.searchHistory(query) }
                    .disabled(query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || history.entries.isEmpty) }
            }
            Text(t("Local search stays on your Mac. AI search sends the query and saved transcripts directly to OpenAI.",
                   "Обычный поиск работает на Mac. AI-поиск отправляет запрос и сохранённые тексты в OpenAI."))
                .font(.caption).foregroundStyle(.secondary)
            if let error = history.error { Text(error).font(.callout).foregroundStyle(.secondary) }
            if model.isSearching { ProgressView().controlSize(.small).frame(maxWidth: .infinity) }
            if displayedHistory.isEmpty {
                ContentUnavailableView {
                    Label(history.entries.isEmpty ? t("No dictations yet", "Пока нет диктовок") : t("No matches", "Ничего не найдено"), systemImage: "text.alignleft")
                } description: {
                    Text(history.entries.isEmpty ? t("Your completed dictations will appear here.", "Завершённые диктовки появятся здесь.") : t("Try another search.", "Попробуйте другой запрос."))
                }.frame(minHeight: 230)
            } else {
                LazyVStack(alignment: .leading, spacing: 18) {
                    ForEach(displayedHistory) { entry in
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Text(entry.date, format: .dateTime.day().month().hour().minute()).foregroundStyle(.secondary)
                                Spacer()
                                Button { model.copy(entry.text) } label: { Image(systemName: "doc.on.doc") }
                                    .help(t("Copy transcript", "Скопировать текст"))
                                Button { model.cancelSearch(); history.delete(entry.id) } label: { Image(systemName: "trash") }
                                    .help(t("Delete transcript", "Удалить текст"))
                            }.font(.caption).buttonStyle(.borderless)
                            Text(entry.text).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                            Divider().padding(.top, 8)
                        }
                    }
                }
            }
            HStack {
                Toggle(t("Save history", "Сохранять историю"), isOn: $preferences.saveHistory).toggleStyle(.switch).controlSize(.small)
                Spacer()
                Button(t("Delete all…", "Удалить всё…"), role: .destructive) { confirmClear = true }.disabled(history.entries.isEmpty)
            }
        }
    }

    private var generalPage: some View {
        VStack(alignment: .leading, spacing: 24) {
            heading(t("Make it yours.", "Настройте под себя."), t("A few preferences. Nothing in your way.", "Всё нужное. Ничего лишнего."))
            VStack(alignment: .leading, spacing: 14) {
                Text(t("Keyboard shortcuts", "Сочетания клавиш")).fontWeight(.medium)
                shortcutSetting(t("Dictation", "Диктовка"), transform: false)
                shortcutSetting(t("Voice editing", "Голосовая правка"), transform: true)
                Text(t("Click a shortcut and press any key combination, or press and release modifiers. Escape clears the shortcut.",
                       "Нажмите на сочетание и введите новое, либо нажмите и отпустите модификаторы. Escape очищает сочетание."))
                    .font(.caption).foregroundStyle(.secondary)
                Text(t("For F1–F12, enable standard function keys in macOS Keyboard settings (or hold Fn). For Globe/Fn taps, set “Press 🌐 key to” to “Do Nothing”; Accessibility must be allowed.",
                       "Для F1–F12 включите стандартные функциональные клавиши в настройках клавиатуры macOS (либо удерживайте Fn). Для нажатий Globe/Fn выберите «При нажатии 🌐» → «Ничего не делать»; нужен доступ к Универсальному доступу."))
                    .font(.caption).foregroundStyle(.secondary)
                Button(t("Reset shortcuts", "Сбросить сочетания")) { model.setShortcuts(.defaults) }
                    .disabled(model.isActive)
                if !model.shortcutError.isEmpty { Text(model.shortcutError).font(.callout) }
            }
            Divider()
            VStack(spacing: 16) {
                settingToggle(t("Launch at login", "Запускать при входе"), Binding(get: { model.loginEnabled }, set: model.setLoginEnabled))
                settingToggle(t("Show recording status", "Показывать состояние записи"), $preferences.showStatus)
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text(t("Recording indicator", "Индикатор записи"))
                        Spacer()
                        Picker(t("Recording indicator", "Индикатор записи"), selection: $preferences.indicatorStyle) {
                            ForEach(RecordingIndicatorStyle.allCases, id: \.self) { style in
                                Text(style.title(russian: preferences.isRussian)).tag(style)
                            }
                        }.labelsHidden().frame(width: 180)
                            .accessibilityIdentifier("recordingIndicatorStyle")
                    }
                    Text(t("Waveform bars show recent microphone volume. Click the indicator to finish, or use your shortcut.",
                           "Полоски показывают изменение громкости микрофона. Нажмите на индикатор или сочетание клавиш, чтобы завершить."))
                        .font(.caption).foregroundStyle(.secondary)
                }.disabled(!preferences.showStatus)
                HStack {
                    Text(t("Interface language", "Язык интерфейса")); Spacer()
                    Picker(t("Interface language", "Язык интерфейса"), selection: $preferences.interfaceLanguage) {
                        Text(t("System", "Системный")).tag("auto"); Text("English").tag("en"); Text("Русский").tag("ru")
                    }.labelsHidden().frame(width: 180)
                }
                HStack {
                    Text(t("Voice editing model", "Модель голосовой правки")); Spacer()
                    Picker(t("Voice editing model", "Модель голосовой правки"), selection: $preferences.textModel) {
                        ForEach(Array(Set(["gpt-6-luna", "gpt-6-sol", preferences.textModel])).sorted(), id: \.self) { id in Text(id).tag(id) }
                    }.labelsHidden().frame(width: 180)
                }
                HStack {
                    Text(t("Response timeout", "Ожидание ответа")); Spacer()
                    Picker(t("Response timeout", "Ожидание ответа"), selection: $preferences.timeout) {
                        ForEach([30, 60, 120], id: \.self) { seconds in Text(t("\(seconds) seconds", "\(seconds) секунд")).tag(seconds) }
                    }.labelsHidden().frame(width: 180)
                }
            }.toggleStyle(.switch).controlSize(.small)
            Divider()
            VStack(alignment: .leading, spacing: 12) {
                Text(t("Excluded apps", "Исключённые приложения")).fontWeight(.medium)
                Text(t("Dictation and voice editing will not start in these apps.", "Диктовка и голосовая правка не запускаются в этих приложениях."))
                    .font(.caption).foregroundStyle(.secondary)
                ForEach(preferences.excludedApps, id: \.self) { id in
                    HStack {
                        Text(id).font(.callout).textSelection(.enabled); Spacer()
                        Button { preferences.excludedApps.removeAll { $0 == id } } label: { Image(systemName: "minus.circle") }
                            .buttonStyle(.borderless).help(t("Remove exclusion", "Убрать исключение"))
                    }
                }
                Button(t("Add application…", "Добавить приложение…"), action: addExcludedApp)
            }
            Divider()
            GoogleDriveSettingsView(sync: model.driveSync, preferences: preferences)
            Divider()
            VStack(alignment: .leading, spacing: 12) {
                Text(t("Privacy", "Приватность")).fontWeight(.medium)
                Text(t("No backend or analytics. Audio stays in memory and is discarded after transcription. Password fields are excluded. Insertion briefly uses the clipboard; its previous contents are restored if unchanged.",
                       "Без сервера и аналитики. Звук хранится в памяти и удаляется после распознавания. Поля паролей исключены. Для вставки кратко используется буфер обмена; прежнее содержимое восстанавливается, если оно не изменилось."))
                    .font(.callout).foregroundStyle(.secondary)
                HStack {
                    Link(t("Privacy policy", "Политика приватности"), destination: URL(string: "https://github.com/OpenDictate/open-dictate/blob/main/PRIVACY.md")!)
                    Spacer()
                    Button(t("Remove API key…", "Удалить ключ API…"), role: .destructive) { confirmDeleteKey = true }
                        .disabled(!model.hasKey || model.isActive)
                }
            }
        }
    }

    private func shortcutSetting(_ label: String, transform: Bool) -> some View {
        let shortcut = transform ? preferences.shortcuts.transform : preferences.shortcuts.dictate
        let save: (KeyboardShortcut) -> Void = { value in
            var bindings = preferences.shortcuts
            if transform { bindings.transform = value } else { bindings.dictate = value }
            model.setShortcuts(bindings)
        }
        return HStack {
            Text(label)
            Spacer()
            ShortcutRecorder(shortcut: shortcut, title: label, emptyLabel: t("Not set", "Не задано"),
                             prompt: t("Press and release…", "Нажмите и отпустите…"), enabled: !model.isActive,
                             onRecording: model.recordShortcut, onSave: save)
                .frame(width: 175, height: 28)
            Menu {
                Button("Globe / Fn") { save(.init(keyCode: nil, flags: .function)) }
                Divider()
                ForEach(Array(KeyboardShortcut.functionKeyCodes.enumerated()), id: \.element) { index, code in
                    Button("F\(index + 1)") { save(.init(keyCode: code, flags: [], keyLabel: "F\(index + 1)")) }
                }
                Divider()
                Button("Escape") { save(.init(keyCode: 53, flags: [], keyLabel: "Esc")) }
            } label: { Image(systemName: "keyboard") }
                .menuStyle(.borderlessButton).fixedSize()
                .accessibilityLabel(t("Choose a key for \(label)", "Выбрать клавишу: \(label)"))
                .disabled(model.isActive)
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
        HStack {
            Text(label); Spacer()
            Toggle(label, isOn: binding).labelsHidden().accessibilityLabel(label)
        }
    }
}
