import AppKit
import SwiftUI
import UniformTypeIdentifiers
import OpenDictateCore

/// Shared selection for the settings window, so other windows can open a page or a meeting.
@MainActor
final class SettingsNavigation: ObservableObject {
    @Published var page: String
    @Published var meeting: UUID?
    init(page: String = "dictation", meeting: UUID? = nil) { self.page = page; self.meeting = meeting }
}

struct MeetingsSettingsView: View {
    @ObservedObject var controller: MeetingController
    @ObservedObject var store: MeetingStore
    @ObservedObject var preferences: Preferences
    @ObservedObject var navigation: SettingsNavigation
    var recordMeeting: () -> Void
    @State private var confirmDeleteAll = false

    private var russian: Bool { preferences.isRussian }
    private func t(_ en: String, _ ru: String) -> String { preferences.t(en, ru) }

    var body: some View {
        if let id = navigation.meeting, let meeting = store.meeting(id) {
            MeetingDetailView(meeting: meeting, controller: controller, store: store, preferences: preferences,
                              back: { navigation.meeting = nil })
                .id(id)
        } else { list }
    }

    private var list: some View {
        VStack(alignment: .leading, spacing: Space.lg) {
            PageHeader(title: t("Meetings", "Встречи")) {
                Button(controller.isActive ? t("Show Meeting", "Показать встречу") : t("Record Meeting…", "Записать встречу…"),
                       action: recordMeeting)
            }
            SettingsSection(title: t("Export", "Экспорт"),
                            footer: t("Each finished meeting is also saved there as a Markdown file.",
                                      "Каждая завершённая встреча также сохраняется туда файлом Markdown.")) {
                SettingsRow(title: t("Auto-export folder", "Папка автоэкспорта"),
                            detail: preferences.meetingExportFolder.map { ($0 as NSString).abbreviatingWithTildeInPath }
                                ?? t("Off", "Выключено")) {
                    HStack(spacing: Space.xs) {
                        if preferences.meetingExportFolder != nil {
                            Button(t("Turn Off", "Выключить")) { preferences.meetingExportFolder = nil }
                        }
                        Button(t("Choose…", "Выбрать…"), action: chooseFolder)
                    }
                }
            }
            if let error = store.error { Text(error).font(.callout).foregroundStyle(.secondary) }
            SettingsCard {
                HStack {
                    Text(t("Saved meetings", "Сохранённые встречи")).font(.system(size: 13, weight: .semibold)).foregroundStyle(.secondary)
                    Spacer()
                    Button(t("Delete All…", "Удалить все…"), role: .destructive) { confirmDeleteAll = true }
                        .disabled(store.meetings.allSatisfy { $0.id == activeID })
                }.settingsRowPadding()
                RowDivider()
                if store.meetings.isEmpty {
                    ContentUnavailableView {
                        Label(t("No meetings yet", "Пока нет встреч"), systemImage: "person.2")
                    } description: {
                        Text(t("Record a meeting from the OpenDictate menu.", "Начните запись встречи из меню OpenDictate."))
                    }.frame(minHeight: 200)
                } else {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(store.meetings.enumerated()), id: \.element.id) { index, meeting in
                            if index > 0 { RowDivider() }
                            row(meeting)
                        }
                    }
                }
            }
        }
        .alert(t("Delete all meetings?", "Удалить все встречи?"), isPresented: $confirmDeleteAll) {
            Button(t("Delete", "Удалить"), role: .destructive) { store.deleteAll(except: activeID) }
            Button(t("Cancel", "Отмена"), role: .cancel) {}
        } message: { Text(t("This removes every saved meeting transcript from this Mac.", "Все сохранённые расшифровки встреч будут удалены с этого Mac.")) }
    }

    private var activeID: UUID? { controller.isActive ? controller.meeting?.id : nil }

    private func row(_ meeting: Meeting) -> some View {
        Button { navigation.meeting = meeting.id } label: {
            HStack(spacing: Space.sm) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(meeting.startedAt.formatted(Date.FormatStyle(date: .abbreviated, time: .shortened)
                        .locale(Locale(identifier: russian ? "ru_RU" : "en_US"))))
                    Text([meeting.sourceName ?? t("All system audio", "Весь системный звук"),
                          MeetingFormat.minutes(meeting.id == activeID ? controller.elapsedMillis : meeting.durationMillis, russian: russian)]
                        .joined(separator: " · "))
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: Space.sm)
                if meeting.id == activeID {
                    Label(t("Recording", "Запись"), systemImage: "record.circle.fill").font(.caption)
                        .foregroundStyle(.secondary).labelStyle(.titleAndIcon)
                } else if meeting.state == .interrupted {
                    Image(systemName: "exclamationmark.circle").foregroundStyle(.secondary)
                        .help(t("Recording stopped unexpectedly", "Запись прервалась"))
                }
                Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.tertiary)
            }
            .contentShape(Rectangle())
            .settingsRowPadding()
        }
        .buttonStyle(.plain)
        .accessibilityLabel(MeetingFormat.title(meeting, russian: russian))
    }

    private func chooseFolder() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true; panel.canChooseFiles = false; panel.allowsMultipleSelection = false
        panel.canCreateDirectories = true
        panel.prompt = t("Choose", "Выбрать")
        if panel.runModal() == .OK, let url = panel.url { preferences.meetingExportFolder = url.path }
    }
}

struct MeetingDetailView: View {
    let meeting: Meeting
    @ObservedObject var controller: MeetingController
    @ObservedObject var store: MeetingStore
    @ObservedObject var preferences: Preferences
    var back: () -> Void
    @State private var query = ""
    @State private var names = [String: String]()
    @State private var confirmDelete = false
    @State private var feedback: String?

    private var russian: Bool { preferences.isRussian }
    private func t(_ en: String, _ ru: String) -> String { preferences.t(en, ru) }
    /// The live copy while this meeting is being recorded.
    private var current: Meeting { controller.isActive && controller.meeting?.id == meeting.id ? controller.meeting ?? meeting : meeting }
    private var recording: Bool { controller.isActive && controller.meeting?.id == meeting.id }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.lg) {
            Button(action: back) { Label(t("Meetings", "Встречи"), systemImage: "chevron.left") }
                .buttonStyle(.borderless)
            PageHeader(title: MeetingFormat.title(current, russian: russian))
            HStack(spacing: Space.xs) {
                Button(t("Copy as Markdown", "Скопировать как Markdown")) {
                    NSPasteboard.general.clearContents()
                    feedback = NSPasteboard.general.setString(controller.markdown(current), forType: .string)
                        ? t("Copied to clipboard.", "Скопировано в буфер обмена.") : nil
                }
                Button(t("Save as…", "Сохранить как…"), action: save)
                Spacer(minLength: Space.xs)
                Button(t("Delete…", "Удалить…"), role: .destructive) { confirmDelete = true }.disabled(recording)
            }
            if let feedback { Text(feedback).font(.callout).foregroundStyle(.secondary) }
            if current.state == .interrupted {
                Label(t("Recording stopped unexpectedly. Text recognized before that is kept.",
                        "Запись прервалась. Распознанный до этого текст сохранён."), systemImage: "exclamationmark.circle")
                    .font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            SettingsSection(title: t("Speakers", "Участники"),
                            footer: t("A new name applies to the whole transcript.", "Новое имя применяется ко всей расшифровке.")) {
                ForEach(Array(current.speakers.enumerated()), id: \.element.id) { index, speaker in
                    if index > 0 { RowDivider() }
                    SettingsRow(title: speaker.role.title(russian: russian)) {
                        TextField(speaker.role.title(russian: russian), text: binding(speaker))
                            .settingsField().frame(width: 220)
                            .accessibilityLabel(t("Name for \(speaker.role.title(russian: false))",
                                                  "Имя для роли «\(speaker.role.title(russian: true))»"))
                    }
                }
            }
            SettingsCard {
                TextField(t("Search transcript", "Поиск по расшифровке"), text: $query).settingsField()
                    .controlSize(.large).padding(Space.md)
                RowDivider()
                let shown = current.search(query, russian: russian)
                if shown.isEmpty {
                    ContentUnavailableView {
                        Label(current.utterances.isEmpty ? t("No speech recognized", "Речь не распознана") : t("No matches", "Ничего не найдено"),
                              systemImage: "text.alignleft")
                    } description: {
                        if !current.utterances.isEmpty { Text(t("Try another search.", "Попробуйте другой запрос.")) }
                    }.frame(minHeight: 160)
                } else {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(shown.enumerated()), id: \.element.id) { index, utterance in
                            if index > 0 { RowDivider() }
                            MeetingUtteranceRow(utterance: utterance,
                                                speaker: current.speaker(utterance.speaker)?.displayName(russian: russian) ?? "",
                                                russian: russian)
                        }
                    }
                }
            }
        }
        .onAppear { names = Dictionary(uniqueKeysWithValues: meeting.speakers.map { ($0.id, $0.name ?? "") }) }
        .alert(t("Delete this meeting?", "Удалить встречу?"), isPresented: $confirmDelete) {
            Button(t("Delete", "Удалить"), role: .destructive) { store.delete(meeting.id); back() }
            Button(t("Cancel", "Отмена"), role: .cancel) {}
        } message: { Text(t("Its transcript will be removed from this Mac.", "Расшифровка будет удалена с этого Mac.")) }
    }

    /// Names apply as they are typed; the field keeps its own text while it is blank.
    private func binding(_ speaker: MeetingSpeaker) -> Binding<String> {
        Binding(get: { names[speaker.id] ?? speaker.name ?? "" }, set: { names[speaker.id] = $0; commit(speaker.id) })
    }

    private func commit(_ id: String) {
        guard let name = names[id], (current.speaker(id)?.name ?? "") != name.trimmingCharacters(in: .whitespacesAndNewlines) else { return }
        if recording { controller.rename(speaker: id, to: name) } else { store.rename(meeting.id, speaker: id, to: name) }
    }

    private func save() {
        let panel = NSSavePanel()
        panel.allowedContentTypes = [UTType(filenameExtension: "md") ?? .plainText]
        panel.nameFieldStringValue = MeetingFormat.fileName(current, russian: russian)
        panel.canCreateDirectories = true
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            try Data(controller.markdown(current).utf8).write(to: url, options: [.atomic])
            feedback = t("Saved.", "Сохранено.")
        } catch { feedback = t("Couldn't save the file.", "Не удалось сохранить файл.") }
    }
}
