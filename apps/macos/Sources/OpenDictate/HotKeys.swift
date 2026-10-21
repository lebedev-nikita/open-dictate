import AppKit
import Carbon

@MainActor
final class HotKeys {
    enum Action: UInt32 { case dictate = 1, transform, cancel, dictateWithPostProcessing }
    private var registrations = [Action: EventHotKeyRef]()
    private var handler: EventHandlerRef?
    private var pressed = Set<Action>()
    private var bindings: ShortcutBindings?
    private var gestures = [Action: ShortcutGesture]()
    private var globalMonitor: Any?
    private var localMonitor: Any?
    private var cancelEnabled = false
    var onAction: ((Action) -> Void)?

    init() {
        var types = [EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed)),
                     EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyReleased))]
        InstallEventHandler(GetApplicationEventTarget(), { _, event, context in
            guard let event, let context else { return noErr }
            var id = EventHotKeyID()
            guard GetEventParameter(event, EventParamName(kEventParamDirectObject), EventParamType(typeEventHotKeyID),
                                    nil, MemoryLayout<EventHotKeyID>.size, nil, &id) == noErr,
                  id.signature == 0x4f444354,
                  let action = Action(rawValue: id.id) else { return noErr }
            let keys = Unmanaged<HotKeys>.fromOpaque(context).takeUnretainedValue()
            MainActor.assumeIsolated {
                if GetEventKind(event) == UInt32(kEventHotKeyReleased) { keys.pressed.remove(action) }
                else if keys.pressed.insert(action).inserted { keys.deliver(action) }
            }
            return noErr
        }, 2, &types, Unmanaged.passUnretained(self).toOpaque(), &handler)
    }

    /// Restore the previous working bindings if either registration fails.
    func configure(_ candidate: ShortcutBindings) throws {
        guard candidate.isValid else { throw DictationError.shortcut }
        let previous = bindings
        clearShortcuts()
        do {
            try install(candidate)
            bindings = candidate
        } catch {
            clearShortcuts()
            if let previous { try? install(previous) }
            throw error
        }
    }

    // Release Carbon reservations while the focused recorder captures any key.
    func suspend() { clearShortcuts() }

    func setCancelEnabled(_ enabled: Bool) {
        cancelEnabled = enabled
        unregister(.cancel)
        // If Escape itself is assigned, its existing registration routes to cancel.
        let escape = KeyboardShortcut(keyCode: 53, flags: [], keyLabel: "Esc")
        if enabled && bindings?.dictate.matches(escape) != true && bindings?.transform.matches(escape) != true &&
            bindings?.dictateWithPostProcessing.matches(escape) != true {
            try? register(.cancel, shortcut: escape)
        }
    }

    func shutdown() {
        clearShortcuts(); unregister(.cancel)
        if let handler { RemoveEventHandler(handler) }; handler = nil
    }

    private func install(_ candidate: ShortcutBindings) throws {
        for (action, shortcut) in actionBindings(candidate) {
            guard shortcut.isEnabled else { continue }
            if shortcut.usesEventMonitor { gestures[action] = ShortcutGesture() }
            else { try register(action, shortcut: shortcut) }
        }
        guard !gestures.isEmpty else { return }
        // Fn and modifier-only taps cannot be Carbon hotkeys. No characters are read.
        let mask: NSEvent.EventTypeMask = [.flagsChanged, .keyDown, .keyUp]
        globalMonitor = NSEvent.addGlobalMonitorForEvents(matching: mask) { [weak self] event in
            MainActor.assumeIsolated { self?.handle(event, bindings: candidate) }
        }
        localMonitor = NSEvent.addLocalMonitorForEvents(matching: mask) { [weak self] event in
            MainActor.assumeIsolated { self?.handle(event, bindings: candidate) }
            return event
        }
    }

    private func handle(_ event: NSEvent, bindings: ShortcutBindings) {
        for (action, shortcut) in actionBindings(bindings) {
            guard var gesture = gestures[action] else { continue }
            let fire = gesture.handle(type: event.type, keyCode: event.keyCode, flags: event.modifierFlags,
                                      isRepeat: event.type == .keyDown && event.isARepeat, shortcut: shortcut)
            gestures[action] = gesture
            if fire { deliver(action) }
        }
    }

    private func deliver(_ action: Action) {
        // Carbon consumes its key events before AppKit monitors see them.
        // A modifier used for a registered shortcut must not also fire a tap.
        for action in Array(gestures.keys) { gestures[action]?.cancelTap() }
        let shortcut = bindings.flatMap { value in actionBindings(value).first { $0.0 == action }?.1 }
        if cancelEnabled && shortcut?.keyCode == 53 && shortcut?.flags.isEmpty == true { onAction?(.cancel) }
        else { onAction?(action) }
    }

    private func clearShortcuts() {
        unregister(.dictate); unregister(.transform); unregister(.dictateWithPostProcessing)
        if let globalMonitor { NSEvent.removeMonitor(globalMonitor) }; globalMonitor = nil
        if let localMonitor { NSEvent.removeMonitor(localMonitor) }; localMonitor = nil
        gestures.removeAll()
    }

    private func actionBindings(_ bindings: ShortcutBindings) -> [(Action, KeyboardShortcut)] {
        [(.dictate, bindings.dictate), (.transform, bindings.transform), (.dictateWithPostProcessing, bindings.dictateWithPostProcessing)]
    }

    private func register(_ action: Action, shortcut: KeyboardShortcut) throws {
        guard let code = shortcut.keyCode else { throw DictationError.shortcut }
        var reference: EventHotKeyRef?
        let id = EventHotKeyID(signature: 0x4f444354, id: action.rawValue)
        guard RegisterEventHotKey(UInt32(code), shortcut.carbonModifiers, id, GetApplicationEventTarget(),
                                  OptionBits(kEventHotKeyExclusive), &reference) == noErr,
              let reference else { throw DictationError.shortcut }
        registrations[action] = reference
    }
    private func unregister(_ action: Action) {
        pressed.remove(action)
        if let reference = registrations.removeValue(forKey: action) { UnregisterEventHotKey(reference) }
    }
}
