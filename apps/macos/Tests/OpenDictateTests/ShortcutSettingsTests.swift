import AppKit
import SwiftUI
import XCTest
@testable import OpenDictate

final class ShortcutSettingsTests: XCTestCase {
    @MainActor func testDisablingIndividualShortcutsPreservesOtherBindingAndRejectsDuplicates() throws {
        let suite = "shortcut-settings-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        Preferences(defaults: defaults).shortcuts = .init(dictate: .disabled, transform: .disabled)
        let model = AppModel(localOnly: true, defaults: defaults)
        defer { model.shutdown() }
        // Modifier taps avoid reserving the installed application's shortcuts.
        let dictate = KeyboardShortcut(keyCode: nil, flags: [.control, .option, .function])
        let transform = KeyboardShortcut(keyCode: nil, flags: [.control, .shift, .function])
        let processed = KeyboardShortcut(keyCode: nil, flags: [.control, .option, .shift, .function])
        let bindings = ShortcutBindings(dictate: dictate, transform: transform, dictateWithPostProcessing: processed)
        XCTAssertTrue(model.setShortcuts(bindings))
        XCTAssertFalse(model.setShortcuts(.init(dictate: dictate, transform: dictate)))
        XCTAssertFalse(model.shortcutError.isEmpty)
        XCTAssertEqual(model.preferences.shortcuts, bindings)
        XCTAssertEqual(Preferences(defaults: defaults).shortcuts, bindings)
        XCTAssertFalse(model.setShortcuts(.init(dictate: dictate, transform: transform, dictateWithPostProcessing: dictate)))
        XCTAssertEqual(Preferences(defaults: defaults).shortcuts, bindings)

        XCTAssertTrue(model.setShortcuts(.init(dictate: .disabled, transform: transform, dictateWithPostProcessing: processed)))
        XCTAssertTrue(model.shortcutError.isEmpty)
        XCTAssertEqual(Preferences(defaults: defaults).shortcuts.transform, transform)
        XCTAssertEqual(Preferences(defaults: defaults).shortcuts.dictateWithPostProcessing, processed)
        XCTAssertFalse(Preferences(defaults: defaults).shortcuts.dictate.isEnabled)
        XCTAssertTrue(model.setShortcuts(.init(dictate: .disabled, transform: .disabled)))
        XCTAssertEqual(Preferences(defaults: defaults).shortcuts, .init(dictate: .disabled, transform: .disabled))
    }

    @MainActor func testNativeShortcutLayoutsAndRecorderCleanup() throws {
        let suite = "shortcut-settings-ui-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        Preferences(defaults: defaults).shortcuts = .init(dictate: .disabled, transform: .disabled)
        let model = AppModel(localOnly: true, defaults: defaults)
        defer { model.shutdown() }
        for (language, dark, width) in [("en", true, 568.0), ("ru", false, 464.0)] {
            model.preferences.interfaceLanguage = language
            let host = NSHostingView(rootView: ShortcutSettingsView(model: model, preferences: model.preferences)
                .padding(24).frame(width: width, alignment: .topLeading)
                .background(Color(nsColor: .windowBackgroundColor)))
            host.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
            host.frame = NSRect(x: 0, y: 0, width: width, height: host.fittingSize.height)
            let window = NSWindow(contentRect: host.frame, styleMask: [.titled], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false; window.contentView = host; window.appearance = host.appearance
            window.makeKeyAndOrderFront(nil); host.layoutSubtreeIfNeeded()
            RunLoop.main.run(until: Date().addingTimeInterval(0.2))
            let recorders = descendants(of: host).compactMap { $0 as? ShortcutRecorderButton }
            XCTAssertEqual(recorders.count, 3)
            let recorder = try XCTUnwrap(recorders.first)
            XCTAssertFalse(recorder.recording)
            if let output = ProcessInfo.processInfo.environment["OPENDICTATE_VIEW_CAPTURES"] {
                let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
                host.cacheDisplay(in: host.bounds, to: bitmap)
                try XCTUnwrap(bitmap.representation(using: .png, properties: [:])).write(to:
                    URL(fileURLWithPath: output).appendingPathComponent("shortcuts-macos-\(language).png"))
            }
            // Leaving the page must stop capture, so menu equivalents work again.
            recorder.toggleRecording()
            XCTAssertTrue(recorder.recording)
            window.contentView = NSView()
            XCTAssertFalse(recorder.recording)
            window.close()
        }
    }

    @MainActor private func descendants(of view: NSView) -> [NSView] {
        view.subviews.flatMap { [$0] + descendants(of: $0) }
    }
}
