import SwiftUI

struct ShortcutRecorder: NSViewRepresentable {
    let shortcut: KeyboardShortcut
    let title: String
    let emptyLabel: String
    let prompt: String
    let enabled: Bool
    let onRecording: (Bool) -> Void
    let onSave: (KeyboardShortcut) -> Void

    func makeNSView(context: Context) -> ShortcutRecorderButton {
        let button = ShortcutRecorderButton()
        button.bezelStyle = .rounded
        button.target = button; button.action = #selector(ShortcutRecorderButton.toggleRecording)
        return button
    }
    func updateNSView(_ button: ShortcutRecorderButton, context: Context) {
        button.shortcutLabel = shortcut.isEnabled ? shortcut.label : emptyLabel; button.prompt = prompt
        button.onRecording = onRecording; button.onSave = onSave
        button.isEnabled = enabled
        button.setAccessibilityLabel(title)
        button.setAccessibilityHelp(prompt)
        button.refreshTitle()
    }
    static func dismantleNSView(_ button: ShortcutRecorderButton, coordinator: ()) { button.finish() }
}

@MainActor
final class ShortcutRecorderButton: NSButton {
    var shortcutLabel = ""
    var prompt = ""
    var onRecording: ((Bool) -> Void)?
    var onSave: ((KeyboardShortcut) -> Void)?
    private(set) var recording = false
    private var monitor: Any?
    private var windowObserver: NSObjectProtocol?
    private var modifierCandidate: NSEvent.ModifierFlags = []
    private var pendingShortcut: KeyboardShortcut?
    override var acceptsFirstResponder: Bool { true }

    override class var cellClass: AnyClass? { get { LegibleButtonCell.self } set {} }

    func refreshTitle() { title = recording ? prompt : shortcutLabel }

    @objc func toggleRecording() {
        if recording { finish(); return }
        guard window?.makeFirstResponder(self) == true else { return }
        modifierCandidate = []; pendingShortcut = nil
        recording = true; onRecording?(true); refreshTitle()
        // Local and temporary: captures menu equivalents (e.g. Command+Q) only
        // while this control is focused. The rest of the app keeps native editing.
        monitor = NSEvent.addLocalMonitorForEvents(matching: [.keyDown, .keyUp, .flagsChanged]) { [weak self] event in
            let consumed = MainActor.assumeIsolated {
                guard let self, self.recording, self.window?.isKeyWindow == true else { return false }
                self.capture(event)
                return true
            }
            return consumed ? nil : event
        }
        windowObserver = NotificationCenter.default.addObserver(forName: NSWindow.didResignKeyNotification,
                                                               object: window, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.finish() }
        }
    }

    private func capture(_ event: NSEvent) {
        if event.type == .keyDown {
            guard !event.isARepeat else { return }
            if event.keyCode == 53 && event.modifierFlags.intersection(KeyboardShortcut.modifierMask).isEmpty {
                pendingShortcut = .disabled; return
            }
            if pendingShortcut == nil { pendingShortcut = KeyboardShortcut.recorded(event) }
        } else if event.type == .keyUp {
            if let shortcut = pendingShortcut, (shortcut == .disabled && event.keyCode == 53) || shortcut.keyCode == event.keyCode {
                // Register only after release: no held key/autorepeat can activate
                // the new shortcut, and Carbon starts with a clean press state.
                finish(); onSave?(shortcut)
            }
        } else {
            guard pendingShortcut == nil else { return }
            let flags = event.modifierFlags.intersection(KeyboardShortcut.modifierMask)
            if !modifierCandidate.isEmpty && flags.isSubset(of: modifierCandidate) && flags != modifierCandidate {
                let shortcut = KeyboardShortcut(keyCode: nil, flags: modifierCandidate)
                finish(); onSave?(shortcut)
            } else { modifierCandidate = flags }
        }
    }

    func finish() {
        guard recording else { return }
        recording = false
        if let monitor { NSEvent.removeMonitor(monitor) }; monitor = nil
        if let windowObserver { NotificationCenter.default.removeObserver(windowObserver) }; windowObserver = nil
        modifierCandidate = []; pendingShortcut = nil; refreshTitle(); onRecording?(false)
    }
    override func resignFirstResponder() -> Bool { finish(); return super.resignFirstResponder() }
    override func viewWillMove(toWindow newWindow: NSWindow?) {
        if newWindow == nil { finish() }
        super.viewWillMove(toWindow: newWindow)
    }
}

/// AppKit fades a disabled title nearly into the bezel; this cell draws it in a readable color.
final class LegibleButtonCell: NSButtonCell {
    override func drawTitle(_ title: NSAttributedString, withFrame frame: NSRect, in controlView: NSView) -> NSRect {
        guard !isEnabled else { return super.drawTitle(title, withFrame: frame, in: controlView) }
        let readable = NSMutableAttributedString(attributedString: title)
        readable.addAttribute(.foregroundColor, value: ControlText.disabledNSColor,
                              range: NSRange(location: 0, length: readable.length))
        controlView.effectiveAppearance.performAsCurrentDrawingAppearance {
            readable.draw(with: frame, options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine])
        }
        return frame
    }
}
