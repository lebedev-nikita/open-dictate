import AppKit
import SwiftUI
import XCTest
import OpenDictateCore
@testable import OpenDictate

final class TextProcessingPreferencesTests: XCTestCase {
    @MainActor func testLegacyAndSyncedFastPreferencesUseStandard() throws {
        let suite = "text-tier-\(UUID().uuidString)", defaults = UserDefaults(suiteName: suite)!
        let otherSuite = suite + "-other", otherDefaults = UserDefaults(suiteName: otherSuite)!
        defer { defaults.removePersistentDomain(forName: suite); otherDefaults.removePersistentDomain(forName: otherSuite) }
        defaults.set("gpt-6-luna", forKey: "textProcessingModel")
        defaults.set("fast", forKey: "textProcessingServiceTier")
        let local = Preferences(defaults: defaults), other = Preferences(defaults: otherDefaults)
        XCTAssertEqual(local.textProcessingSelection, TextProcessingModel.id)
        XCTAssertEqual(other.textProcessingServiceTier, .standard)
        local.textProcessingSelection = TextProcessingModel.id
        XCTAssertEqual(local.textProcessingModel, "gpt-6-luna")
        XCTAssertEqual(local.textProcessingServiceTier, .standard)
        XCTAssertEqual(Preferences(defaults: defaults).textProcessingSelection, TextProcessingModel.id)
        _ = try other.mergeSync(local.syncDocument())
        XCTAssertEqual(other.textProcessingSelection, TextProcessingModel.id)
        local.textProcessingSelection = TextProcessingModel.fastChoice
        XCTAssertEqual(local.textProcessingSelection, TextProcessingModel.id)
        var legacy = try local.syncDocument()
        legacy.entries["textProcessingServiceTier"] = .init(value: "fast", modifiedAt: 8_000_000_000_000, deviceId: "legacy")
        _ = try other.mergeSync(legacy)
        XCTAssertEqual(Preferences(defaults: otherDefaults).textProcessingServiceTier, .standard)
        XCTAssertEqual(other.textProcessingServiceTier, .standard)
        XCTAssertEqual(other.textProcessingModel, "gpt-6-luna")
        var invalid = try local.syncDocument()
        invalid.entries["textProcessingServiceTier"] = .init(value: "invalid", modifiedAt: 9_000_000_000_000, deviceId: "remote")
        invalid.entries["dictionary"] = .init(value: "Must not apply", modifiedAt: 9_000_000_000_000, deviceId: "remote")
        let dictionary = local.dictionary
        XCTAssertThrowsError(try local.mergeSync(invalid))
        XCTAssertEqual(local.dictionary, dictionary)
        XCTAssertEqual(local.textProcessingServiceTier, .standard)
    }

    @MainActor func testNativeModelPickerLayouts() throws {
        let suite = "text-tier-ui-\(UUID().uuidString)", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let model = AppModel(localOnly: true, defaults: defaults)
        for (language, dark, choice) in [("en", true, TextProcessingModel.id), ("ru", false, TextProcessingModel.id)] {
            model.preferences.interfaceLanguage = language
            model.preferences.theme = dark ? .dark : .light
            model.preferences.textProcessingSelection = choice
            let host = NSHostingView(rootView: SettingsView(model: model, preferences: model.preferences, history: model.history, replacements: model.replacements))
            host.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
            host.frame = NSRect(x: 0, y: 0, width: 788, height: 760)
            let window = NSWindow(contentRect: host.frame, styleMask: [.titled], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false; window.contentView = host; window.appearance = host.appearance
            window.makeKeyAndOrderFront(nil); host.layoutSubtreeIfNeeded()
            RunLoop.main.run(until: Date().addingTimeInterval(0.2))
            XCTAssertEqual(model.preferences.textProcessingSelection, choice)
            if let output = ProcessInfo.processInfo.environment["OPENDICTATE_VIEW_CAPTURES"] {
                let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
                host.cacheDisplay(in: host.bounds, to: bitmap)
                try XCTUnwrap(bitmap.representation(using: .png, properties: [:])).write(to:
                    URL(fileURLWithPath: output).appendingPathComponent("text-tier-macos-\(language)-\(choice == TextProcessingModel.fastChoice ? "fast" : "standard").png"))
            }
            window.close()
        }
    }
}
