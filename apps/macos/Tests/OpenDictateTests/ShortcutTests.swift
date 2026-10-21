import XCTest
import AppKit
import Carbon
@testable import OpenDictate

final class ShortcutTests: XCTestCase {
    private func event(_ code: UInt16, flags: NSEvent.ModifierFlags = [], text: String = "") -> NSEvent {
        NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: flags, timestamp: 0,
                        windowNumber: 0, context: nil, characters: text, charactersIgnoringModifiers: text,
                        isARepeat: false, keyCode: code)!
    }

    func testEveryFunctionKeyAndNavigationHaveNamesWithoutSyntheticFn() {
        for (index, code) in KeyboardShortcut.functionKeyCodes.enumerated() {
            let shortcut = KeyboardShortcut.recorded(event(code, flags: [.function, .shift, .numericPad]))
            XCTAssertEqual(shortcut.keyCode, code)
            XCTAssertEqual(shortcut.label, "⇧F\(index + 1)")
            XCTAssertFalse(shortcut.usesEventMonitor)
        }
        XCTAssertEqual(KeyboardShortcut.recorded(event(123, flags: [.function, .numericPad])).label, "←")
        XCTAssertEqual(KeyboardShortcut.recorded(event(76)).label, "Numpad Enter")
    }

    func testArbitraryKeysAndAllStandardModifiersAreAccepted() {
        let shortcut = KeyboardShortcut.recorded(event(44, flags: [.command, .option, .shift, .control], text: "/"))
        XCTAssertEqual(shortcut.label, "⌃⌥⇧⌘/")
        XCTAssertEqual(shortcut.carbonModifiers, UInt32(controlKey | optionKey | shiftKey | cmdKey))
        XCTAssertTrue(KeyboardShortcut.recorded(event(0, text: "ф")).isValid)
        XCTAssertEqual(KeyboardShortcut.recorded(event(0, text: "ф")).keyCode, 0)
        XCTAssertTrue(KeyboardShortcut.recorded(event(49)).flags.isEmpty)
        XCTAssertTrue(KeyboardShortcut.recorded(event(0, flags: .function, text: "a")).usesEventMonitor)
    }

    func testDuplicateDetectionIgnoresLabelsAndAllowsDisabledShortcuts() {
        let a = KeyboardShortcut(keyCode: 0, flags: .command, keyLabel: "A")
        let b = KeyboardShortcut(keyCode: 0, flags: .command, keyLabel: "Ф")
        XCTAssertFalse(ShortcutBindings(dictate: a, transform: b).isValid)
        XCTAssertTrue(KeyboardShortcut.disabled.isValid)
        XCTAssertFalse(KeyboardShortcut.disabled.isEnabled)
        XCTAssertFalse(KeyboardShortcut.disabled.usesEventMonitor)
        XCTAssertTrue(ShortcutBindings(dictate: .disabled, transform: .disabled).isValid)
        XCTAssertTrue(ShortcutBindings.defaults.isValid)
        XCTAssertFalse(ShortcutBindings(dictate: a, transform: .disabled, dictateWithPostProcessing: b).isValid)
        XCTAssertFalse(ShortcutBindings(dictate: .disabled, transform: a, dictateWithPostProcessing: b).isValid)
        XCTAssertTrue(ShortcutBindings(dictate: .disabled, transform: .disabled, dictateWithPostProcessing: a).isValid)
    }

    func testOldBindingsDecodeWithNewActionUnassignedAndNewBindingRoundTrips() throws {
        let original = ShortcutBindings.defaults
        var legacy = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(original)) as? [String: Any])
        legacy.removeValue(forKey: "dictateWithPostProcessing")
        let restored = try JSONDecoder().decode(ShortcutBindings.self, from: JSONSerialization.data(withJSONObject: legacy))
        XCTAssertEqual(restored.dictate, original.dictate)
        XCTAssertEqual(restored.transform, original.transform)
        XCTAssertFalse(restored.dictateWithPostProcessing.isEnabled)
        var updated = restored
        updated.dictateWithPostProcessing = .init(keyCode: nil, flags: [.control, .function])
        XCTAssertEqual(try JSONDecoder().decode(ShortcutBindings.self, from: JSONEncoder().encode(updated)), updated)
    }

    @MainActor func testDisabledBindingsPersistAndDoNotRegisterModifierGestures() async throws {
        let domain = "com.opendictate.disabled-tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: domain)!
        defer { defaults.removePersistentDomain(forName: domain) }
        let disabled = ShortcutBindings(dictate: .disabled, transform: .disabled)
        Preferences(defaults: defaults).shortcuts = disabled
        XCTAssertEqual(Preferences(defaults: defaults).shortcuts, disabled)
        let keys = HotKeys()
        defer { keys.shutdown() }
        try keys.configure(disabled)
        var gesture = ShortcutGesture()
        XCTAssertFalse(gesture.handle(type: .flagsChanged, keyCode: 58, flags: .option, shortcut: .disabled))
        XCTAssertFalse(gesture.handle(type: .flagsChanged, keyCode: 58, flags: [], shortcut: .disabled))
        keys.setCancelEnabled(true); keys.setCancelEnabled(false)
    }

    func testFnTapFiresOnceOnReleaseAndAnotherTapCanFire() {
        let fn = KeyboardShortcut(keyCode: nil, flags: .function)
        var gesture = ShortcutGesture()
        for _ in 0..<2 {
            XCTAssertFalse(gesture.handle(type: .flagsChanged, keyCode: 63, flags: .function, shortcut: fn))
            XCTAssertFalse(gesture.handle(type: .flagsChanged, keyCode: 63, flags: .function, shortcut: fn))
            XCTAssertTrue(gesture.handle(type: .flagsChanged, keyCode: 63, flags: [], shortcut: fn))
            XCTAssertFalse(gesture.handle(type: .flagsChanged, keyCode: 63, flags: [], shortcut: fn))
        }
    }

    func testFnUsedForFunctionKeyOrTypingDoesNotDictateOnRelease() {
        let fn = KeyboardShortcut(keyCode: nil, flags: .function)
        for code: UInt16 in [122, 0, 123] {
            var gesture = ShortcutGesture()
            XCTAssertFalse(gesture.handle(type: .flagsChanged, keyCode: 63, flags: .function, shortcut: fn))
            XCTAssertFalse(gesture.handle(type: .keyDown, keyCode: code, flags: .function, shortcut: fn))
            XCTAssertFalse(gesture.handle(type: .keyUp, keyCode: code, flags: .function, shortcut: fn))
            XCTAssertFalse(gesture.handle(type: .flagsChanged, keyCode: 63, flags: [], shortcut: fn))
        }
    }

    func testShiftFnDoesNotAlsoTriggerPlainFnInEitherReleaseOrder() {
        let fn = KeyboardShortcut(keyCode: nil, flags: .function)
        let shiftFn = KeyboardShortcut(keyCode: nil, flags: [.function, .shift])
        for remaining: NSEvent.ModifierFlags in [.function, .shift] {
            var dictate = ShortcutGesture(), transform = ShortcutGesture()
            for flags: NSEvent.ModifierFlags in [.function, [.function, .shift]] {
                XCTAssertFalse(dictate.handle(type: .flagsChanged, keyCode: 63, flags: flags, shortcut: fn))
                XCTAssertFalse(transform.handle(type: .flagsChanged, keyCode: 63, flags: flags, shortcut: shiftFn))
            }
            XCTAssertFalse(dictate.handle(type: .flagsChanged, keyCode: 63, flags: remaining, shortcut: fn))
            XCTAssertTrue(transform.handle(type: .flagsChanged, keyCode: 63, flags: remaining, shortcut: shiftFn))
            XCTAssertFalse(dictate.handle(type: .flagsChanged, keyCode: 63, flags: [], shortcut: fn))
            XCTAssertFalse(transform.handle(type: .flagsChanged, keyCode: 63, flags: [], shortcut: shiftFn))
        }
    }

    func testModifierTapIsCancelledByUnrelatedModifierAndRecovers() {
        let shortcut = KeyboardShortcut(keyCode: nil, flags: .option)
        var gesture = ShortcutGesture()
        for flags: NSEvent.ModifierFlags in [.option, [.option, .command], .option, []] {
            XCTAssertFalse(gesture.handle(type: .flagsChanged, keyCode: 58, flags: flags, shortcut: shortcut))
        }
        XCTAssertFalse(gesture.handle(type: .flagsChanged, keyCode: 58, flags: .option, shortcut: shortcut))
        XCTAssertTrue(gesture.handle(type: .flagsChanged, keyCode: 58, flags: [], shortcut: shortcut))
    }

    func testFnKeyCombinationRequiresExactModifiersAndDoesNotRepeat() {
        let shortcut = KeyboardShortcut(keyCode: 0, flags: [.function, .control], keyLabel: "A")
        var gesture = ShortcutGesture()
        XCTAssertFalse(gesture.handle(type: .keyDown, keyCode: 0, flags: .function, shortcut: shortcut))
        XCTAssertTrue(gesture.handle(type: .keyDown, keyCode: 0, flags: [.function, .control], shortcut: shortcut))
        XCTAssertFalse(gesture.handle(type: .keyDown, keyCode: 0, flags: [.function, .control], isRepeat: true, shortcut: shortcut))
        XCTAssertFalse(gesture.handle(type: .keyUp, keyCode: 0, flags: [], shortcut: shortcut))
        XCTAssertTrue(gesture.handle(type: .keyDown, keyCode: 0, flags: [.function, .control], shortcut: shortcut))
    }

    func testConsumedCarbonKeyCancelsModifierTapUntilReleased() {
        let shortcut = KeyboardShortcut(keyCode: nil, flags: .option)
        var gesture = ShortcutGesture()
        XCTAssertFalse(gesture.handle(type: .flagsChanged, keyCode: 58, flags: .option, shortcut: shortcut))
        gesture.cancelTap()
        XCTAssertFalse(gesture.handle(type: .flagsChanged, keyCode: 58, flags: [], shortcut: shortcut))
        XCTAssertFalse(gesture.handle(type: .flagsChanged, keyCode: 58, flags: .option, shortcut: shortcut))
        XCTAssertTrue(gesture.handle(type: .flagsChanged, keyCode: 58, flags: [], shortcut: shortcut))
    }

    @MainActor func testPreferencesMigrateEveryLegacyCombinationAndPersistIndependentShortcuts() async throws {
        let domain = "com.opendictate.shortcut-tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: domain)!
        defer { defaults.removePersistentDomain(forName: domain) }
        for modifiers in ["option", "control-option", "command-option"] {
            for key in ["space", "d", "r"] {
                defaults.set(modifiers, forKey: "shortcutModifiers"); defaults.set(key, forKey: "shortcutKey")
                XCTAssertEqual(Preferences(defaults: defaults).shortcuts, .legacy(modifiers: modifiers, key: key))
            }
        }
        let saved = ShortcutBindings(dictate: .init(keyCode: nil, flags: .function),
                                     transform: .init(keyCode: 122, flags: [.command, .shift], keyLabel: "F1"))
        Preferences(defaults: defaults).shortcuts = saved
        XCTAssertEqual(Preferences(defaults: defaults).shortcuts, saved)
        defaults.set(Data("broken".utf8), forKey: "shortcuts")
        XCTAssertEqual(Preferences(defaults: defaults).shortcuts, .legacy(modifiers: "command-option", key: "r"))
        XCTAssertEqual(try JSONDecoder().decode(ShortcutBindings.self, from: JSONEncoder().encode(saved)), saved)
    }

    @MainActor func testRegistrationFailureRestoresPreviousShortcutsAndShutdownReleasesThem() async throws {
        let keys = HotKeys()
        defer { keys.shutdown() }
        let flags: NSEvent.ModifierFlags = [.control, .option, .command, .shift]
        let previous = ShortcutBindings(dictate: .init(keyCode: 80, flags: flags, keyLabel: "F19"),
                                        transform: .init(keyCode: 90, flags: flags, keyLabel: "F20"),
                                        dictateWithPostProcessing: .init(keyCode: 64, flags: flags, keyLabel: "F17"))
        let conflicting = KeyboardShortcut(keyCode: 79, flags: flags, keyLabel: "F18")
        var reservation: EventHotKeyRef?
        let id = EventHotKeyID(signature: 0x54455354, id: 99)
        XCTAssertEqual(RegisterEventHotKey(79, conflicting.carbonModifiers, id, GetApplicationEventTarget(),
                                          OptionBits(kEventHotKeyExclusive), &reservation), noErr)
        defer { if let reservation { UnregisterEventHotKey(reservation) } }
        try keys.configure(previous)
        XCTAssertThrowsError(try keys.configure(.init(dictate: previous.dictate, transform: previous.transform, dictateWithPostProcessing: conflicting)))
        var probe: EventHotKeyRef?
        let status = RegisterEventHotKey(80, previous.dictate.carbonModifiers, id, GetApplicationEventTarget(),
                                        OptionBits(kEventHotKeyExclusive), &probe)
        if let probe { UnregisterEventHotKey(probe) }
        XCTAssertEqual(status, OSStatus(eventHotKeyExistsErr))
        probe = nil
        XCTAssertEqual(RegisterEventHotKey(64, previous.dictateWithPostProcessing.carbonModifiers, id, GetApplicationEventTarget(),
                                           OptionBits(kEventHotKeyExclusive), &probe), OSStatus(eventHotKeyExistsErr))
        if let probe { UnregisterEventHotKey(probe) }
        keys.shutdown()
        probe = nil
        XCTAssertEqual(RegisterEventHotKey(80, previous.dictate.carbonModifiers, id, GetApplicationEventTarget(),
                                          OptionBits(kEventHotKeyExclusive), &probe), noErr)
        if let probe { UnregisterEventHotKey(probe) }
        probe = nil
        XCTAssertEqual(RegisterEventHotKey(64, previous.dictateWithPostProcessing.carbonModifiers, id, GetApplicationEventTarget(),
                                           OptionBits(kEventHotKeyExclusive), &probe), noErr)
        if let probe { UnregisterEventHotKey(probe) }
    }
}
