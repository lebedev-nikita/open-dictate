import AppKit
import Carbon

/// Physical key identity is independent of the current keyboard layout.
struct KeyboardShortcut: Codable, Equatable {
    let keyCode: UInt16?
    let modifiers: UInt
    let keyLabel: String

    static let modifierMask: NSEvent.ModifierFlags = [.control, .option, .shift, .command, .function]
    var flags: NSEvent.ModifierFlags { NSEvent.ModifierFlags(rawValue: modifiers).intersection(Self.modifierMask) }
    static let disabled = Self(keyCode: nil, flags: [])
    var isEnabled: Bool { keyCode != nil || !flags.isEmpty }
    var usesEventMonitor: Bool { isEnabled && (keyCode == nil || flags.contains(.function)) }
    var isValid: Bool { isEnabled || keyLabel.isEmpty }
    var label: String {
        let symbols: [(NSEvent.ModifierFlags, String)] = [(.control, "⌃"), (.option, "⌥"),
            (.shift, "⇧"), (.command, "⌘"), (.function, "Fn/🌐")]
        return symbols.filter { flags.contains($0.0) }.map(\.1).joined() + (keyCode == nil ? "" : keyLabel)
    }
    var carbonModifiers: UInt32 {
        var result: UInt32 = 0
        for (flag, value) in [(NSEvent.ModifierFlags.control, controlKey), (.option, optionKey),
                              (.shift, shiftKey), (.command, cmdKey)] {
            if flags.contains(flag) { result |= UInt32(value) }
        }
        return result
    }
    func matches(_ other: Self) -> Bool { keyCode == other.keyCode && flags == other.flags }

    init(keyCode: UInt16?, flags: NSEvent.ModifierFlags, keyLabel: String = "") {
        self.keyCode = keyCode
        modifiers = flags.intersection(Self.modifierMask).rawValue
        self.keyLabel = keyLabel
    }

    static func recorded(_ event: NSEvent) -> Self {
        let code = event.keyCode
        // AppKit sets .function on F/navigation keys even without physical Fn.
        let flags = normalizedFlags(event.modifierFlags, keyCode: code)
        let text = event.charactersIgnoringModifiers?.uppercased() ?? ""
        let label = keyNames[code] ?? (text.isEmpty || text.unicodeScalars.contains { $0.value >= 0xF700 }
            ? "Key \(code)" : text)
        return Self(keyCode: code, flags: flags, keyLabel: label)
    }

    static func normalizedFlags(_ flags: NSEvent.ModifierFlags, keyCode: UInt16) -> NSEvent.ModifierFlags {
        var result = flags.intersection(modifierMask)
        if functionKeyCodes.contains(keyCode) || [123, 124, 125, 126, 115, 119, 116, 121, 117].contains(keyCode) {
            result.remove(.function)
        }
        return result
    }

    static let functionKeyCodes: [UInt16] = [122, 120, 99, 118, 96, 97, 98, 100, 101, 109,
                                           103, 111, 105, 107, 113, 106, 64, 79, 80, 90]
    static let keyNames: [UInt16: String] = {
        var names: [UInt16: String] = [36: "Return", 48: "Tab", 49: "Space", 51: "⌫", 53: "Esc",
            65: "Numpad .", 67: "Numpad *", 69: "Numpad +", 71: "Clear", 75: "Numpad /",
            76: "Numpad Enter", 78: "Numpad −", 81: "Numpad =", 82: "Numpad 0", 83: "Numpad 1",
            84: "Numpad 2", 85: "Numpad 3", 86: "Numpad 4", 87: "Numpad 5", 88: "Numpad 6",
            89: "Numpad 7", 91: "Numpad 8", 92: "Numpad 9", 114: "Help", 115: "Home",
            116: "Page Up", 117: "⌦", 119: "End", 121: "Page Down", 123: "←", 124: "→", 125: "↓", 126: "↑"]
        for (index, code) in functionKeyCodes.enumerated() { names[code] = "F\(index + 1)" }
        return names
    }()
}

struct ShortcutBindings: Codable, Equatable {
    var dictate: KeyboardShortcut
    var transform: KeyboardShortcut
    var dictateWithPostProcessing: KeyboardShortcut
    private enum CodingKeys: String, CodingKey { case dictate, transform, dictateWithPostProcessing }

    init(dictate: KeyboardShortcut, transform: KeyboardShortcut, dictateWithPostProcessing: KeyboardShortcut = .disabled) {
        self.dictate = dictate; self.transform = transform
        self.dictateWithPostProcessing = dictateWithPostProcessing
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        dictate = try values.decode(KeyboardShortcut.self, forKey: .dictate)
        transform = try values.decode(KeyboardShortcut.self, forKey: .transform)
        // Preserve existing assignments without reserving another key on upgrade.
        dictateWithPostProcessing = try values.decodeIfPresent(KeyboardShortcut.self, forKey: .dictateWithPostProcessing) ?? .disabled
    }
    static let defaults = legacy(modifiers: "option", key: "space")

    static func legacy(modifiers: String, key: String) -> Self {
        var flags: NSEvent.ModifierFlags = .option
        if modifiers == "control-option" { flags.insert(.control) }
        if modifiers == "command-option" { flags.insert(.command) }
        let code: UInt16 = key == "d" ? 2 : (key == "r" ? 15 : 49)
        let label = code == 49 ? "Space" : key.uppercased()
        return Self(dictate: .init(keyCode: code, flags: flags, keyLabel: label),
                    transform: .init(keyCode: code, flags: flags.union(.shift), keyLabel: label))
    }
    var isValid: Bool {
        let shortcuts = [dictate, transform, dictateWithPostProcessing]
        guard shortcuts.allSatisfy(\.isValid) else { return false }
        let enabled = shortcuts.filter(\.isEnabled)
        return !enabled.enumerated().contains { index, shortcut in
            enabled.dropFirst(index + 1).contains { shortcut.matches($0) }
        }
    }
}

/// Modifier-only shortcuts fire on release, only if no other key was used.
/// Stores key identities/flags only, never typed characters.
struct ShortcutGesture {
    private var armed = false
    private var down = false
    private var blocked = false
    private var previousFlags: NSEvent.ModifierFlags = []

    mutating func cancelTap() { armed = false; blocked = true }

    mutating func handle(type: NSEvent.EventType, keyCode: UInt16, flags: NSEvent.ModifierFlags,
                         isRepeat: Bool = false, shortcut: KeyboardShortcut) -> Bool {
        guard shortcut.isEnabled else { return false }
        if let code = shortcut.keyCode {
            if type == .keyUp && keyCode == code { down = false }
            guard type == .keyDown, keyCode == code,
                  KeyboardShortcut.normalizedFlags(flags, keyCode: code) == shortcut.flags,
                  !isRepeat, !down else { return false }
            down = true
            return true
        }
        if type == .keyDown { armed = false; blocked = true; return false }
        guard type == .flagsChanged else { return false }
        let current = flags.intersection(KeyboardShortcut.modifierMask)
        defer { previousFlags = current }
        // A subset means release; adding an unrelated modifier cancels the tap.
        let released = armed && previousFlags == shortcut.flags && current != previousFlags &&
            current.isSubset(of: shortcut.flags)
        if released {
            armed = false; blocked = !current.intersection(shortcut.flags).isEmpty
            return true
        }
        if current.intersection(shortcut.flags).isEmpty { blocked = false }
        else if !current.isSubset(of: shortcut.flags) { blocked = true }
        armed = !blocked && current == shortcut.flags
        return false
    }
}
