import AppKit
import SwiftUI
import OpenDictateCore

/// One utterance: time and speaker above the text, like a transcript ledger.
struct MeetingUtteranceRow: View {
    let utterance: MeetingUtterance
    let speaker: String
    let russian: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: Space.xxs) {
            HStack(spacing: Space.xs) {
                Text(MeetingFormat.timestamp(utterance.startMillis))
                    .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                Text(speaker).font(.callout.weight(.semibold))
            }
            switch utterance.status {
            case .recognized:
                Text(utterance.text).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
            case .pending:
                Text(russian ? "Ждёт распознавания…" : "Waiting for recognition…").foregroundStyle(.secondary)
            case .failed:
                Text(russian ? "Не распознано" : "Not recognized").foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Space.md).padding(.vertical, Space.sm)
        .accessibilityElement(children: .combine)
    }
}

/// Lists every running application with a window, apps currently playing audio first.
enum MeetingSources {
    struct App: Identifiable, Hashable {
        let bundleID: String
        let name: String
        let playing: Bool
        var id: String { bundleID }
    }

    @MainActor static func runningApps() -> [App] {
        let processes = AudioProcesses.all()
        let own = Bundle.main.bundleIdentifier
        let apps = NSWorkspace.shared.runningApplications.compactMap { app -> App? in
            guard app.activationPolicy == .regular, let id = app.bundleIdentifier, id != own else { return nil }
            let main: Set<pid_t> = [app.processIdentifier]
            let playing = processes.contains {
                AudioProcesses.belongs($0, to: id, mainPIDs: main) && AudioProcesses.isRunningOutput($0.object)
            }
            return App(bundleID: id, name: app.localizedName ?? id, playing: playing)
        }
        return Dictionary(apps.map { ($0.bundleID, $0) }, uniquingKeysWith: { first, _ in first }).values
            .sorted { ($0.playing ? 0 : 1, $0.name.localizedLowercase) < ($1.playing ? 0 : 1, $1.name.localizedLowercase) }
    }

    static func icon(_ bundleID: String) -> NSImage? {
        guard let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundleID) else { return nil }
        let icon = NSWorkspace.shared.icon(forFile: url.path)
        icon.size = NSSize(width: 16, height: 16)
        return icon
    }
}

/// The meeting window: setup before recording, the live transcript while recording, and the
/// saved transcript afterwards. Closing it never stops a recording.
struct MeetingView: View {
    @ObservedObject var controller: MeetingController
    @ObservedObject var preferences: Preferences
    @ObservedObject var model: AppModel
    /// Renames and deletions made in Settings reach the finished view.
    @ObservedObject private var store: MeetingStore
    /// The finished meeting shown after recording, until a new one starts.
    @Binding var shownMeeting: UUID?
    var openSettings: (String, UUID?) -> Void
    @State private var apps = [MeetingSources.App]()
    @State private var confirmCancel = false
    @State private var copied = false

    init(controller: MeetingController, preferences: Preferences, model: AppModel, shownMeeting: Binding<UUID?>,
         openSettings: @escaping (String, UUID?) -> Void) {
        self.controller = controller; self.preferences = preferences; self.model = model
        store = controller.store; _shownMeeting = shownMeeting; self.openSettings = openSettings
    }

    private var russian: Bool { preferences.isRussian }
    private func t(_ en: String, _ ru: String) -> String { preferences.t(en, ru) }
    /// Local checks never read the saved key, so they do not need one.
    private var hasKey: Bool { model.hasKey || controller.localOnly }

    var body: some View {
        Group {
            if controller.isActive, let meeting = controller.meeting { live(meeting) }
            else if let id = shownMeeting, let meeting = store.meeting(id) { finished(meeting) }
            else { setup }
        }
        .frame(minWidth: 440, minHeight: 420)
        .background(Surface.page)
        .background(AppThemeAppearance(theme: preferences.theme))
        .alert(t("Cancel this meeting?", "Отменить встречу?"), isPresented: $confirmCancel) {
            Button(t("Delete Meeting", "Удалить встречу"), role: .destructive) { controller.cancel() }
            Button(t("Keep Recording", "Продолжить запись"), role: .cancel) {}
        } message: { Text(t("The recording and its transcript will be deleted.", "Запись и расшифровка будут удалены.")) }
    }

    // MARK: Setup

    private var setup: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.lg) {
                PageHeader(title: t("Record a meeting", "Запись встречи"))
                if !preferences.meetingNoticeAccepted {
                    SettingsSection(title: t("Before you record", "Перед записью")) {
                        VStack(alignment: .leading, spacing: Space.sm) {
                            Text(t("Everyone's speech in the call is sent to OpenAI for transcription. Make sure the other participants agree to be recorded.",
                                   "Речь всех участников звонка отправляется в OpenAI для распознавания. Убедитесь, что собеседники согласны на запись."))
                                .fixedSize(horizontal: false, vertical: true)
                            Button(t("I Understand", "Понятно")) { preferences.meetingNoticeAccepted = true }
                                .keyboardShortcut(.defaultAction)
                        }.padding(Space.md)
                    }
                }
                SettingsSection(title: t("Sources", "Источники"),
                                footer: t("Choose the call app so music and notifications stay out of the transcript.",
                                          "Выберите приложение звонка, чтобы музыка и уведомления не попали в расшифровку.")) {
                    SettingsRow(title: t("Call audio", "Звук звонка"), detail: t("Recorded as Other speaker", "Записывается как «Собеседник»")) {
                        sourcePicker
                    }
                    RowDivider()
                    SettingsRow(title: t("Microphone", "Микрофон"), detail: t("Recorded as Me", "Записывается как «Я»")) {
                        if model.microphoneAllowed { StatusLabel(title: t("Allowed", "Разрешён")) }
                        else { Button(t("Allow…", "Разрешить…"), action: model.requestMicrophone) }
                    }
                    if !hasKey {
                        RowDivider()
                        SettingsRow(title: t("OpenAI API key", "Ключ API OpenAI"), detail: t("Not saved yet", "Ещё не сохранён")) {
                            Button(t("Open Settings…", "Открыть настройки…")) { openSettings("dictation", nil) }
                        }
                    }
                }
                Button(t("Start Recording", "Начать запись"), action: start)
                    .controlSize(.large).keyboardShortcut(.defaultAction)
                    .disabled(!preferences.meetingNoticeAccepted || !hasKey || !model.microphoneAllowed)
            }
            .padding(Space.xl).frame(maxWidth: 560, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .onAppear { model.refreshPermissions(); apps = MeetingSources.runningApps() }
    }

    private var sourcePicker: some View {
        Picker(t("Call audio", "Звук звонка"), selection: $preferences.meetingSource) {
            Text(t("All system audio", "Весь системный звук")).tag(MeetingAudioSource.system)
            Divider()
            ForEach(sourceApps) { app in
                Label {
                    Text(app.name)
                } icon: {
                    if let icon = MeetingSources.icon(app.bundleID) { Image(nsImage: icon) }
                }.tag(MeetingAudioSource.application(bundleID: app.bundleID, name: app.name))
            }
        }
        .labelsHidden().fixedSize()
    }

    /// Running apps, plus the remembered app if it is not running.
    private var sourceApps: [MeetingSources.App] {
        guard case .application(let id, let name) = preferences.meetingSource, !apps.contains(where: { $0.bundleID == id })
        else { return apps }
        return apps + [MeetingSources.App(bundleID: id, name: name, playing: false)]
    }

    private func start() {
        do {
            try controller.start(source: preferences.meetingSource)
            shownMeeting = nil
        } catch MeetingController.StartError.microphone { model.requestMicrophone() }
        catch { model.refreshPermissions() }
    }

    // MARK: Recording

    private func live(_ meeting: Meeting) -> some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: Space.sm) {
                HStack(alignment: .firstTextBaseline, spacing: Space.sm) {
                    statusBadge
                    Text(MeetingFormat.elapsed(controller.elapsedMillis))
                        .font(.system(size: 22, weight: .semibold).monospacedDigit())
                    Spacer(minLength: Space.sm)
                    Text(meeting.sourceName ?? t("All system audio", "Весь системный звук"))
                        .foregroundStyle(.secondary).lineLimit(1)
                }
                if controller.phase != .finishing {
                    MeetingMeters(levels: controller.levels, microphone: controller.microphone,
                                  callAudio: controller.callAudio, russian: russian)
                }
                notices
            }
            .padding(.horizontal, Space.lg).padding(.top, Space.lg).padding(.bottom, Space.md)
            Divider()
            transcript(meeting, live: true)
            Divider()
            HStack(spacing: Space.xs) {
                switch controller.phase {
                case .recording:
                    Button(t("Pause", "Пауза")) { controller.pause() }
                    Button(t("Finish", "Завершить")) { controller.finish() }.keyboardShortcut(.defaultAction)
                case .paused:
                    Button(t("Resume", "Продолжить")) { controller.resume() }.keyboardShortcut(.defaultAction)
                    Button(t("Finish", "Завершить")) { controller.finish() }
                case .finishing:
                    Button(t("Finish Now", "Завершить сейчас")) { controller.finishNow() }
                        .help(t("Phrases still waiting are marked as not recognized.", "Ожидающие фразы будут отмечены как нераспознанные."))
                case .idle: EmptyView()
                }
                Spacer(minLength: Space.xs)
                Button(t("Cancel Meeting…", "Отменить встречу…"), role: .destructive) { confirmCancel = true }
            }
            .controlSize(.large)
            .padding(.horizontal, Space.lg).padding(.vertical, Space.md)
            .background(Surface.sidebar)
        }
    }

    private var statusBadge: some View {
        let recording = controller.phase == .recording
        return Label {
            Text(controller.phase == .recording ? t("Recording", "Запись") :
                 controller.phase == .paused ? t("Paused", "Пауза") : t("Finishing", "Завершение"))
        } icon: {
            Image(systemName: recording ? "record.circle.fill" : controller.phase == .paused ? "pause.circle" : "hourglass")
                .foregroundStyle(recording ? AnyShapeStyle(Color.red) : AnyShapeStyle(.secondary))
        }
        .font(.callout.weight(.medium))
    }

    @ViewBuilder private var notices: some View {
        if controller.phase == .finishing {
            HStack(spacing: Space.xs) {
                ProgressView().controlSize(.small)
                Text(t("Recognizing the remaining phrases: \(controller.waitingCount)", "Распознаём оставшиеся фразы: \(controller.waitingCount)"))
                    .font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
        }
        if controller.phase == .paused, let reason = controller.pauseReason, reason != .user {
            notice(reason == .sleep ? t("Paused while your Mac slept.", "Пауза: Mac уходил в сон.")
                   : t("Paused: recognition is behind. Check your connection, then resume.",
                       "Пауза: распознавание отстаёт. Проверьте соединение и продолжите."), symbol: "pause.circle")
        }
        if controller.callAudio == .failed {
            notice(t("Call audio is unavailable. Only your microphone is recorded.",
                     "Звук звонка недоступен. Записывается только микрофон."), symbol: "speaker.slash")
        } else if controller.callAudioSilent {
            HStack(alignment: .top, spacing: Space.xs) {
                notice(t("No call audio yet. If the other side is speaking, allow OpenDictate under System Audio Recording.",
                         "Звука звонка пока нет. Если собеседник говорит, разрешите OpenDictate запись системного звука."),
                       symbol: "speaker.slash")
                Button(t("System Settings…", "Системные настройки…"), action: MeetingController.openAudioCapturePrivacy)
                    .controlSize(.small)
            }
        }
        if controller.microphone == .failed {
            notice(t("The microphone stopped. Check it in System Settings.", "Микрофон остановился. Проверьте его в системных настройках."),
                   symbol: "mic.slash")
        }
        if let issue = controller.issue { notice(issue, symbol: "exclamationmark.triangle") }
    }

    private func notice(_ text: String, symbol: String) -> some View {
        Label(text, systemImage: symbol).font(.callout).foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func transcript(_ meeting: Meeting, live: Bool) -> some View {
        ScrollViewReader { proxy in
            ScrollView {
                if meeting.utterances.isEmpty {
                    Text(live ? t("Speech appears here a few seconds after each phrase.",
                                  "Речь появится здесь через несколько секунд после каждой фразы.")
                              : t("No speech was recognized.", "Речь не распознана."))
                        .foregroundStyle(.secondary).multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity).padding(Space.xl)
                } else {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(meeting.utterances) { utterance in
                            MeetingUtteranceRow(utterance: utterance,
                                                speaker: meeting.speaker(utterance.speaker)?.displayName(russian: russian) ?? "",
                                                russian: russian).id(utterance.id)
                        }
                        Color.clear.frame(height: 1).id("end")
                    }.padding(.vertical, Space.xs)
                }
            }
            .onChange(of: meeting.utterances.count) { _ in
                if live { withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo("end", anchor: .bottom) } }
            }
        }
    }

    // MARK: Finished

    private func finished(_ meeting: Meeting) -> some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: Space.xxs) {
                Label(t("Meeting saved", "Встреча сохранена"), systemImage: "checkmark.circle")
                    .font(.callout.weight(.medium)).foregroundStyle(.secondary)
                Text(MeetingFormat.title(meeting, russian: russian)).font(.system(size: 22, weight: .semibold))
                    .fixedSize(horizontal: false, vertical: true)
                // The auto-export failure of the meeting just finished.
                if meeting.id == controller.lastFinishedID, let issue = controller.issue {
                    notice(issue, symbol: "exclamationmark.triangle").padding(.top, Space.xs)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Space.lg).padding(.top, Space.lg).padding(.bottom, Space.md)
            Divider()
            transcript(meeting, live: false)
            Divider()
            // Russian labels need two rows at the default width; wrap instead of truncating.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: Space.xs) {
                    copyButton(meeting); openButton(meeting)
                    Spacer(minLength: Space.xs)
                    newMeetingButton
                }
                VStack(alignment: .leading, spacing: Space.xs) {
                    HStack(spacing: Space.xs) { copyButton(meeting); openButton(meeting) }
                    newMeetingButton
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .controlSize(.large)
            .padding(.horizontal, Space.lg).padding(.vertical, Space.md)
            .background(Surface.sidebar)
        }
        .onChange(of: meeting.id) { _ in copied = false }
    }

    private func copyButton(_ meeting: Meeting) -> some View {
        Button(copied ? t("Copied", "Скопировано") : t("Copy as Markdown", "Скопировать как Markdown")) {
            NSPasteboard.general.clearContents()
            copied = NSPasteboard.general.setString(controller.markdown(meeting), forType: .string)
        }
    }

    private func openButton(_ meeting: Meeting) -> some View {
        Button(t("Open in Meetings", "Открыть во встречах")) { openSettings("meetings", meeting.id) }
    }

    private var newMeetingButton: some View {
        Button(t("New Meeting", "Новая встреча")) { shownMeeting = nil; copied = false }
    }
}

/// Input levels of both channels, so the user sees that each side is heard.
struct MeetingMeters: View {
    @ObservedObject var levels: MeetingLevels
    let microphone: MeetingController.ChannelState
    let callAudio: MeetingController.ChannelState
    let russian: Bool

    var body: some View {
        HStack(spacing: Space.lg) {
            meter(russian ? "Я" : "Me", symbol: "mic", level: levels.me, state: microphone)
            meter(russian ? "Собеседник" : "Other speaker", symbol: "speaker.wave.2", level: levels.other, state: callAudio)
            Spacer(minLength: 0)
        }
    }

    private func meter(_ title: String, symbol: String, level: Float, state: MeetingController.ChannelState) -> some View {
        HStack(spacing: Space.xs) {
            Image(systemName: symbol).foregroundStyle(.secondary).frame(width: 16)
            Text(title).font(.callout)
            HStack(spacing: 2) {
                ForEach(0..<5) { index in
                    Capsule().fill(Float(index) < (sqrt(level) * 5).rounded(.up) && state == .live
                                   ? Color.primary.opacity(0.7) : Color.primary.opacity(0.15))
                        .frame(width: 4, height: 10)
                }
            }
            if state == .starting { ProgressView().controlSize(.mini) }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(state == .live ? "\(Int(level * 100))%" : (russian ? "нет сигнала" : "no signal"))
    }
}

extension MeetingController {
    /// Privacy & Security → Screen & System Audio Recording, where System Audio Recording Only lives.
    static func openAudioCapturePrivacy() {
        let audio = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_AudioCapture")!
        let screen = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture")!
        if !NSWorkspace.shared.open(audio) { NSWorkspace.shared.open(screen) }
    }
}

/// Owns the meeting window. It never stops a recording when closed.
@MainActor
final class MeetingWindowController: NSObject, NSWindowDelegate {
    private var window: NSWindow?
    private let controller: MeetingController
    private let model: AppModel
    private let openSettings: (String, UUID?) -> Void
    private let onClose: () -> Void
    let state = MeetingWindowState()

    init(controller: MeetingController, model: AppModel, openSettings: @escaping (String, UUID?) -> Void,
         onClose: @escaping () -> Void) {
        self.controller = controller; self.model = model; self.openSettings = openSettings; self.onClose = onClose
    }

    var isVisible: Bool { window?.isVisible == true }

    /// Shows the window. `activate` is false when recording starts from a shortcut during a
    /// call, so the call app keeps keyboard focus.
    func show(activate: Bool, finished: UUID? = nil) {
        if let finished { state.shownMeeting = finished }
        // A meeting started from the menu or shortcut replaces the one shown before; if it is
        // cancelled, the window returns to setup instead of the earlier saved meeting.
        else if controller.isActive { state.shownMeeting = nil }
        if window == nil {
            let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 520, height: 620),
                                  styleMask: [.titled, .closable, .miniaturizable, .resizable],
                                  backing: .buffered, defer: false)
            window.title = model.preferences.t("Meeting", "Встреча")
            window.isReleasedWhenClosed = false
            window.contentView = NSHostingView(rootView: MeetingWindowRoot(controller: controller, model: model, state: state,
                                                                           openSettings: openSettings))
            window.minSize = NSSize(width: 440, height: 420)
            window.setFrameAutosaveName("OpenDictateMeeting")
            window.delegate = self
            if !window.setFrameUsingName("OpenDictateMeeting") { window.center() }
            self.window = window
        }
        window?.title = model.preferences.t("Meeting", "Встреча")
        if activate {
            NSApp.setActivationPolicy(.regular)
            NSApp.activate(ignoringOtherApps: true)
            window?.makeKeyAndOrderFront(nil)
        } else {
            window?.orderFrontRegardless()
        }
    }

    func windowWillClose(_ notification: Notification) { onClose() }

    #if DEBUG
    func closeForTesting() { window?.delegate = nil; window?.close() }
    #endif
}

@MainActor
final class MeetingWindowState: ObservableObject {
    @Published var shownMeeting: UUID?
}

private struct MeetingWindowRoot: View {
    @ObservedObject var controller: MeetingController
    @ObservedObject var model: AppModel
    @ObservedObject var state: MeetingWindowState
    let openSettings: (String, UUID?) -> Void

    var body: some View {
        MeetingView(controller: controller, preferences: model.preferences, model: model,
                    shownMeeting: $state.shownMeeting, openSettings: openSettings)
            .onChange(of: controller.lastFinishedID) { id in if let id { state.shownMeeting = id } }
    }
}
