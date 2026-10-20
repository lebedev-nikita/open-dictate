import AppKit
import SwiftUI
import OpenDictateCore

@main
struct OpenDictateApp {
    @MainActor static func main() {
        if CommandLine.arguments.contains("--verify-bundled-resources") {
            do { try CoreResources.verify(); print("Bundled resources verified.") }
            catch { fputs("Bundled resource verification failed.\n", stderr); exit(1) }
            return
        }
        let application = NSApplication.shared
        let delegate = AppDelegate()
        application.delegate = delegate
        application.setActivationPolicy(.accessory)
        application.run()
        withExtendedLifetime(delegate) {}
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private var model: AppModel!
    private var statusItem: NSStatusItem!
    private var settingsWindow: NSWindow?
    private var accessibilityActivationObserver: NSObjectProtocol?
    private let recordingIndicator = RecordingIndicator()
    private lazy var errorNotifications = ErrorNotifications()
    #if DEBUG
    private var localChecks: LocalSmokeChecks?
    private var hudPreviewPhase: AppModel.Phase?
    #endif

    func applicationDidFinishLaunching(_ notification: Notification) {
        #if DEBUG
        let localOnly = CommandLine.arguments.contains("--local-smoke-test") ||
            Bundle.main.object(forInfoDictionaryKey: "OpenDictateLocalSmokeTest") as? Bool == true
        model = AppModel(localOnly: localOnly, notifyError: errorNotifications.post)
        #else
        model = AppModel(notifyError: errorNotifications.post)
        #endif
        #if DEBUG
        if localOnly {
            let checks = LocalSmokeChecks(model: model); localChecks = checks
            model.hotKeys.onAction = { action in
                switch action {
                case .dictate, .transform: Task { await checks.toggle() }
                case .cancel: self.model.cancel(); Task { await checks.cancel() }
                }
            }
        }
        #endif
        // Electron publishes its tree asynchronously. Prepare when the user
        // enters an app so the first shortcut can capture an already exposed field.
        accessibilityActivationObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didActivateApplicationNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.prepareAccessibility() }
        }
        prepareAccessibility()
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        model.onStateChange = { [weak self] in self?.updateState() }
        let menu = NSMenu(); menu.delegate = self
        statusItem.menu = menu
        updateState()
        #if DEBUG
        if localChecks != nil && CommandLine.arguments.contains("--hud-preview") {
            hudPreviewPhase = .recording; updateState(); return
        }
        #endif
        if !model.hasKey || !model.microphoneAllowed || !model.accessibilityAllowed { openSettings() }
        #if DEBUG
        if localChecks != nil { openSettings() }
        #endif
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        openSettings(); return true
    }
    func applicationWillTerminate(_ notification: Notification) {
        if let accessibilityActivationObserver {
            NSWorkspace.shared.notificationCenter.removeObserver(accessibilityActivationObserver)
        }
        model.shutdown()
    }

    private func prepareAccessibility() {
        ApplicationAccessibility.prepareFrontmost(exclusions: model.preferences.excludedApps)
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        let p = model.preferences
        let status = NSMenuItem(title: model.isActive ? model.stateLabel : "OpenDictate", action: nil, keyEquivalent: "")
        menu.addItem(status)
        if !model.message.isEmpty {
            let item = NSMenuItem(title: String(model.message.prefix(110)), action: nil, keyEquivalent: "")
            menu.addItem(item)
        }
        menu.addItem(.separator())
        add(menu, model.isActive ? p.t("Finish dictation", "Завершить диктовку") : p.t("Start dictation", "Начать диктовку"),
            #selector(dictate), key: p.shortcutLabel, enabled: model.phase != .processing)
        add(menu, model.alternatePostProcessingEnabled ? p.t("Dictate with post-processing", "Диктовать с постобработкой") :
            p.t("Dictate without post-processing", "Диктовать без постобработки"),
            #selector(alternateDictation), enabled: !model.isActive)
        add(menu, p.t("Edit text by voice", "Изменить текст голосом"), #selector(transform), key: p.editShortcutLabel, enabled: !model.isActive)
        if model.isActive { add(menu, p.t("Cancel", "Отменить"), #selector(cancel), key: "Esc") }
        menu.addItem(.separator())
        add(menu, p.t("Retranscribe last recording", "Перетранскрибировать последнюю запись"), #selector(retranscribeLast), enabled: model.canRetranscribe)
        add(menu, p.t("Copy last transcript", "Скопировать последний текст"), #selector(copyLast), enabled: !model.lastTranscript.isEmpty)
        add(menu, p.t("Paste last transcript", "Вставить последний текст"), #selector(pasteLast), enabled: !model.isActive && !model.lastTranscript.isEmpty)
        add(menu, p.t("Add selection to dictionary", "Добавить выделение в словарь"), #selector(addSelection), enabled: !model.isActive)
        menu.addItem(.separator())
        let modes = NSMenu(title: p.t("Transcription", "Распознавание"))
        for mode in DictationMode.allCases {
            let item = NSMenuItem(title: mode == .live ? "Live" : "Accurate", action: #selector(setMode(_:)), keyEquivalent: "")
            item.target = self; item.representedObject = mode.rawValue
            item.state = p.mode == mode ? .on : .off; item.isEnabled = !model.isActive
            modes.addItem(item)
        }
        let modeItem = NSMenuItem(title: p.t("Transcription", "Распознавание") + ": " + (p.mode == .live ? "Live" : "Accurate"), action: nil, keyEquivalent: "")
        modeItem.submenu = modes; menu.addItem(modeItem)
        add(menu, p.t("Settings…", "Настройки…"), #selector(settings))
        add(menu, p.t("Quit OpenDictate", "Завершить OpenDictate"), #selector(quit))
        #if DEBUG
        if localChecks != nil {
            menu.addItem(.separator())
            add(menu, "Local check: record microphone (2 seconds)", #selector(checkMicrophone))
            add(menu, "Local check: show error notification", #selector(checkErrorNotification))
            add(menu, "Local check: missing editable field", #selector(checkMissingField))
        }
        #endif
    }

    private func add(_ menu: NSMenu, _ title: String, _ action: Selector, key: String = "", enabled: Bool = true) {
        let item = NSMenuItem(title: title + (key.isEmpty ? "" : "    \(key)"), action: action, keyEquivalent: "")
        item.target = self; item.isEnabled = enabled; menu.addItem(item)
    }

    func makeMainMenu(preferences: Preferences) -> NSMenu {
        let main = NSMenu()
        let applicationItem = NSMenuItem(); main.addItem(applicationItem)
        let applicationMenu = NSMenu(title: "OpenDictate")
        let settings = NSMenuItem(title: preferences.t("Settings…", "Настройки…"), action: #selector(settings), keyEquivalent: ",")
        settings.target = self; applicationMenu.addItem(settings)
        #if DEBUG
        if localChecks != nil {
            let focusRecording = NSMenuItem(title: "Local check: start recording in 3 seconds", action: #selector(checkFocusRecording), keyEquivalent: "")
            focusRecording.target = self; applicationMenu.addItem(focusRecording)
            let microphone = NSMenuItem(title: "Local check: record microphone (2 seconds)", action: #selector(checkMicrophone), keyEquivalent: "")
            microphone.target = self; applicationMenu.addItem(microphone)
            let insertion = NSMenuItem(title: "Local check: insert in 3 seconds", action: #selector(checkInsertion), keyEquivalent: "")
            insertion.target = self; applicationMenu.addItem(insertion)
            let cancellation = NSMenuItem(title: "Local check: cancel in 3 seconds", action: #selector(checkCancellation), keyEquivalent: "")
            cancellation.target = self; applicationMenu.addItem(cancellation)
            let sequence = NSMenuItem(title: "Local check: verified clipboard insertion in 3 seconds", action: #selector(checkSequence), keyEquivalent: "")
            sequence.target = self; applicationMenu.addItem(sequence)
            let session = NSMenuItem(title: "Local check: recording and insertion in 5 seconds", action: #selector(checkRecordingSession), keyEquivalent: "")
            session.target = self; applicationMenu.addItem(session)
            let retranscription = NSMenuItem(title: "Local check: retranscribe last recording in 3 seconds", action: #selector(checkRetranscription), keyEquivalent: "")
            retranscription.target = self; applicationMenu.addItem(retranscription)
            let recording = NSMenuItem(title: "Local check: preview recording indicator", action: #selector(previewRecording), keyEquivalent: "")
            recording.target = self; applicationMenu.addItem(recording)
            let processing = NSMenuItem(title: "Local check: preview processing indicator", action: #selector(previewProcessing), keyEquivalent: "")
            processing.target = self; applicationMenu.addItem(processing)
        }
        #endif
        applicationMenu.addItem(.separator())
        let quit = NSMenuItem(title: preferences.t("Quit OpenDictate", "Завершить OpenDictate"), action: #selector(quit), keyEquivalent: "q")
        quit.target = self; applicationMenu.addItem(quit)
        applicationItem.submenu = applicationMenu
        let fileItem = NSMenuItem(), fileMenu = NSMenu(title: preferences.t("File", "Файл"))
        main.addItem(fileItem); fileItem.submenu = fileMenu
        fileMenu.addItem(NSMenuItem(title: preferences.t("Close Window", "Закрыть окно"),
                                   action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w"))
        let closeAll = NSMenuItem(title: preferences.t("Close All Windows", "Закрыть все окна"),
                                  action: #selector(closeAllWindows(_:)), keyEquivalent: "w")
        closeAll.keyEquivalentModifierMask = [.command, .shift]
        closeAll.target = self; fileMenu.addItem(closeAll)
        let editItem = NSMenuItem(), editMenu = NSMenu(title: preferences.t("Edit", "Правка"))
        main.addItem(editItem); editItem.submenu = editMenu
        editMenu.addItem(NSMenuItem(title: preferences.t("Undo", "Отменить"), action: NSSelectorFromString("undo:"), keyEquivalent: "z"))
        let redo = NSMenuItem(title: preferences.t("Redo", "Повторить"), action: NSSelectorFromString("redo:"), keyEquivalent: "z")
        redo.keyEquivalentModifierMask = [.command, .shift]; editMenu.addItem(redo)
        editMenu.addItem(.separator())
        editMenu.addItem(NSMenuItem(title: preferences.t("Cut", "Вырезать"), action: #selector(NSText.cut(_:)), keyEquivalent: "x"))
        editMenu.addItem(NSMenuItem(title: preferences.t("Copy", "Скопировать"), action: #selector(NSText.copy(_:)), keyEquivalent: "c"))
        editMenu.addItem(NSMenuItem(title: preferences.t("Paste", "Вставить"), action: #selector(NSText.paste(_:)), keyEquivalent: "v"))
        editMenu.addItem(NSMenuItem(title: preferences.t("Select All", "Выбрать всё"), action: #selector(NSText.selectAll(_:)), keyEquivalent: "a"))
        return main
    }

    private func updateState() {
        NSApp.mainMenu = makeMainMenu(preferences: model.preferences)
        #if DEBUG
        let phase = hudPreviewPhase ?? model.phase
        #else
        let phase = model.phase
        #endif
        statusItem.button?.image = MenuBarIcon.image(for: phase)
        statusItem.button?.title = ""
        statusItem.button?.toolTip = "OpenDictate · \(model.stateLabel) · \(model.preferences.shortcutLabel)"
        #if DEBUG
        if let phase = hudPreviewPhase {
            recordingIndicator.show(phase: phase, style: model.preferences.indicatorStyle,
                                    audioLevels: model.audioLevels, isRussian: model.preferences.isRussian) { [weak self] in
                self?.hudPreviewPhase = .processing; self?.updateState()
            }
            return
        }
        #endif
        if model.isActive && model.preferences.showStatus {
            recordingIndicator.show(phase: model.phase, style: model.preferences.indicatorStyle,
                                    audioLevels: model.audioLevels, isRussian: model.preferences.isRussian,
                                    stage: model.processingStage, showsStageIcon: model.showsProcessingStageIcon) { [weak model] in model?.stop() }
        } else { recordingIndicator.hide() }
    }

    private func openSettings() {
        model.refreshPermissions()
        model.driveSync.refreshIfStale()
        if settingsWindow == nil {
            #if DEBUG
            let compact = localChecks != nil && CommandLine.arguments.contains("--compact-ui-check")
            #else
            let compact = false
            #endif
            let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: compact ? 740 : 790, height: compact ? 590 : 650),
                                  styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
            window.title = "OpenDictate"; window.isReleasedWhenClosed = false
            window.contentView = NSHostingView(rootView: SettingsView(model: model, preferences: model.preferences, history: model.history, replacements: model.replacements))
            window.minSize = NSSize(width: 740, height: 620)
            window.center(); settingsWindow = window
        }
        NSApp.activate(ignoringOtherApps: true)
        settingsWindow?.makeKeyAndOrderFront(nil)
    }

    // Menu actions run after the menu releases focus, before capturing the frontmost field.
    private func afterMenu(_ action: @escaping @MainActor @Sendable () -> Void) {
        Task { try? await Task.sleep(nanoseconds: 120_000_000); action() }
    }
    @objc private func dictate() { afterMenu { self.model.toggle(transform: false) } }
    @objc private func alternateDictation() {
        let enabled = model.alternatePostProcessingEnabled
        afterMenu { self.model.toggle(transform: false, postProcessingOverride: enabled) }
    }
    @objc private func transform() { afterMenu { self.model.toggle(transform: true) } }
    @objc private func cancel() { model.cancel() }
    @objc private func copyLast() { model.copyLast() }
    @objc private func retranscribeLast() { afterMenu { self.model.retranscribeLast() } }
    @objc private func pasteLast() { afterMenu { self.model.pasteLast() } }
    @objc private func addSelection() { afterMenu { self.model.addSelectionToDictionary() } }
    @objc private func setMode(_ sender: NSMenuItem) {
        if let raw = sender.representedObject as? String, let mode = DictationMode(rawValue: raw) { model.preferences.mode = mode }
    }
    @objc private func settings() { openSettings() }
    @objc private func closeAllWindows(_ sender: Any?) {
        for window in NSApp.windows where window.isVisible && window.styleMask.contains(.closable) {
            window.performClose(sender)
        }
    }
    @objc private func quit() { NSApp.terminate(nil) }
    #if DEBUG
    @objc private func checkFocusRecording() {
        hudPreviewPhase = nil
        Task { try? await Task.sleep(nanoseconds: 3_000_000_000); model.toggleLocalRecording() }
    }
    @objc private func checkRecordingSession() {
        hudPreviewPhase = nil
        Task { try? await Task.sleep(nanoseconds: 5_000_000_000); model.toggleLocalRecording(autoStop: true) }
    }
    @objc private func checkRetranscription() {
        Task { try? await Task.sleep(nanoseconds: 3_000_000_000); model.retranscribeLast() }
    }
    @objc private func checkMicrophone() {
        hudPreviewPhase = .recording; updateState(); localChecks?.record()
    }
    @objc private func previewRecording() { hudPreviewPhase = .recording; updateState() }
    @objc private func previewProcessing() { hudPreviewPhase = .processing; updateState() }
    @objc private func checkErrorNotification() { model.present(DictationError.connection) }
    @objc private func checkMissingField() { model.present(DictationError.noField) }
    @objc private func checkInsertion() {
        Task { try? await Task.sleep(nanoseconds: 3_000_000_000); await localChecks?.toggle() }
    }
    @objc private func checkCancellation() {
        Task { try? await Task.sleep(nanoseconds: 3_000_000_000); await localChecks?.cancel() }
    }
    @objc private func checkSequence() {
        Task { try? await Task.sleep(nanoseconds: 3_000_000_000); await localChecks?.verifyClipboardInsertion() }
    }
    #endif
}
